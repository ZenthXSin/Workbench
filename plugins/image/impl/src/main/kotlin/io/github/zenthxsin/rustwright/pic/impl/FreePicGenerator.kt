package io.github.zenthxsin.rustwright.pic.impl

import io.github.zenthxsin.rustwright.core.ai.AiChannel
import io.github.zenthxsin.rustwright.core.ai.image.ImageApiException
import io.github.zenthxsin.rustwright.core.ai.image.ImageClient
import io.github.zenthxsin.rustwright.core.ai.image.ImageOutput
import io.github.zenthxsin.rustwright.core.ai.image.ImageRequest
import io.github.zenthxsin.rustwright.pic.api.CreationMode
import io.github.zenthxsin.rustwright.pic.api.PicImage
import io.github.zenthxsin.rustwright.pic.api.PicRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** 仅在内存持有凭据；由 NewAPI 登录接口供给，不能来自全局自定义线路。 */
data class PicBenefitRelay(val baseUrl: String, val token: String, val subject: String, val imageModel: String)

@Serializable
internal data class FreePicPending(
    val key: String,
    val scope: String,
    val prompt: String,
    val finalPrompt: String,
    val mode: CreationMode,
    val spriteSize: Int,
    val baseUrl: String,
    val subject: String,
    val model: String,
    val createdAt: Long,
) {
    val imageId get() = "img_free_" + key.replace("-", "")
    fun request() = PicRequest(prompt, mode, spriteSize, free = true, requestScope = scope)
}

/** 每个会话/手动页的原请求保存在私有目录；未知结果只允许恢复同一份请求。 */
internal class FreePicGenerator(
    private val pendingDir: File,
    private val relay: suspend (String?) -> PicBenefitRelay,
    private val clientFactory: (AiChannel, () -> Boolean) -> ImageClient,
) {
    private data class Flight(val request: PicRequest, val result: CompletableDeferred<List<PicImage>> = CompletableDeferred())
    private val flights = HashMap<String, Flight>()
    private val slots = Semaphore(2)
    private val json = Json { encodeDefaults = true }

    fun pending(scope: String): PicRequest? = read(scope)?.request()

    suspend fun generate(
        request: PicRequest,
        finalPrompt: String,
        existing: (String) -> PicImage?,
        save: suspend (FreePicPending, ImageOutput.Inline) -> PicImage,
    ): List<PicImage> {
        val (flight, owner) = synchronized(flights) {
            flights[request.requestScope]?.let {
                check(it.request == request) { "原免费图片正在生成，请等待原请求完成" }
                it to false
            } ?: Flight(request).also { flights[request.requestScope] = it }.let { it to true }
        }
        if (!owner) return flight.result.await()
        try {
            return slots.withPermit { execute(request, finalPrompt, existing, save) }.also { flight.result.complete(it) }
        } catch (error: Throwable) {
            flight.result.completeExceptionally(error)
            throw error
        } finally {
            synchronized(flights) { if (flights[request.requestScope] === flight) flights.remove(request.requestScope) }
        }
    }

    private suspend fun execute(request: PicRequest, finalPrompt: String, existing: (String) -> PicImage?, save: suspend (FreePicPending, ImageOutput.Inline) -> PicImage): List<PicImage> {
        require(request.free && request.count == 1) { "免费生图每次只能生成 1 张" }
        require(request.references.isEmpty()) { "免费生图暂不支持参考图或改图" }
        require(request.requestScope.isNotBlank()) { "图片请求缺少所属会话" }
        currentCoroutineContext().ensureActive()
        val old = read(request.requestScope)
        check(old == null || old.request() == request) { "上次免费图片结果尚未确认，请先重试原请求" }
        // 重试先重新鉴权，不能通过换账号、节点或全局设置重新申请。
        val credentials = relay(old?.baseUrl?.let { URI(it).let { uri -> "${uri.scheme}://${uri.rawAuthority}" } })
        require(credentials.imageModel.startsWith(FREE_PREFIX) && credentials.token.isNotBlank() && credentials.subject.isNotBlank()) { "免费生图线路不可用" }
        val base = credentials.baseUrl.trimEnd('/')
        if (old != null) check(old.subject == credentials.subject && old.baseUrl == base && old.model == credentials.imageModel) {
            "请使用发起请求的原 NewAPI 账号、节点和免费模型重试"
        }
        val pending = old ?: FreePicPending(UUID.randomUUID().toString(), request.requestScope, request.prompt, finalPrompt,
            request.mode, request.spriteSize, base, credentials.subject, credentials.imageModel, System.currentTimeMillis()).also { write(it) }
        existing(pending.imageId)?.let {
            clear(pending.scope)
            return listOf(it)
        }
        val job = currentCoroutineContext().job
        try {
            val outputs = withContext(Dispatchers.IO) {
                val channel = AiChannel(baseUrl = pending.baseUrl, token = credentials.token, chatModel = "", imageModel = pending.model)
                clientFactory(channel) { !job.isActive }.generate(
                    ImageRequest(pending.finalPrompt, pending.model, "1024x1024", 1, quality = "medium", outputFormat = "png"),
                    maxRetries = 1, idempotencyKey = pending.key,
                )
            }
            currentCoroutineContext().ensureActive()
            check(outputs.size == 1 && outputs.single() is ImageOutput.Inline) { "免费图片响应必须是 1 张 PNG，请重试原请求" }
            // 已收到结果后原子落图库；取消时不产生半张可见图片，也不丢失已成功结果。
            return withContext(NonCancellable + Dispatchers.IO) {
                val image = save(pending, outputs.single() as ImageOutput.Inline)
                clear(pending.scope)
                listOf(image)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageApiException) {
            val released = e.type == "rustwright_benefit_replay" && e.state == "released"
            val rejected = e.type in setOf("rustwright_benefit_limit", "rustwright_benefit_invalid", "rustwright_benefit_login_required", "rustwright_benefit_disabled")
            if (released || rejected) clear(pending.scope)
            val message = when {
                released -> "上次生图已确认失败，次数已返还，可重新生成"
                e.type == "rustwright_benefit_limit" -> "图片与地图的共享次数不足，请查看福利额度"
                e.type == "rustwright_benefit_disabled" -> "免费生图尚未开放"
                e.type == "rustwright_benefit_invalid" -> "生图参数未获服务端接受，请调整后重试"
                e.type == "rustwright_benefit_login_required" -> "请先登录 NewAPI"
                else -> "免费图片结果尚未确认，请重试原请求（不会重复扣次）"
            }
            throw IllegalStateException(message, e)
        } catch (e: Exception) {
            throw IllegalStateException("免费图片结果尚未确认，请重试原请求（不会重复扣次）", e)
        }
    }

    private fun file(scope: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(pendingDir, "$name.json")
    }

    private fun read(scope: String): FreePicPending? = file(scope).takeIf { it.isFile }?.let {
        json.decodeFromString(FreePicPending.serializer(), it.readText()).also { pending ->
            check(pending.scope == scope && pending.key.matches(Regex("[A-Za-z0-9_-]{16,100}"))) { "原生图请求记录损坏，不能新建请求" }
        }
    }

    private fun write(pending: FreePicPending) = atomicWrite(file(pending.scope), json.encodeToString(FreePicPending.serializer(), pending).toByteArray())

    private fun clear(scope: String) {
        val file = file(scope)
        check(!file.exists() || file.delete()) { "图片已保存，但请求记录未能清理，请重试原请求" }
    }

    companion object {
        val FREE_PREFIX: String get() = io.github.zenthxsin.rustwright.core.runtime.net.Endpoints.benefitModelPrefix()

        internal fun atomicWrite(file: File, bytes: ByteArray) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
