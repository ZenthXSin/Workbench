package io.github.zenthxsin.rustwright.pic.impl

import io.github.zenthxsin.rustwright.core.ai.AiChannel
import io.github.zenthxsin.rustwright.core.ai.image.ImageClient
import io.github.zenthxsin.rustwright.core.ai.image.ImageOutput
import io.github.zenthxsin.rustwright.core.ai.image.ImageRequest
import io.github.zenthxsin.rustwright.core.runtime.Events
import io.github.zenthxsin.rustwright.pic.api.CreationMode
import io.github.zenthxsin.rustwright.pic.api.ImagesGenerated
import io.github.zenthxsin.rustwright.pic.api.PicImage
import io.github.zenthxsin.rustwright.pic.api.PicRequest
import io.github.zenthxsin.rustwright.pic.api.PicService
import io.github.zenthxsin.rustwright.pic.imaging.PixelArtProcessor
import io.github.zenthxsin.rustwright.pic.imaging.PixelProcessingParams
import io.github.zenthxsin.rustwright.pic.imaging.PngEncoder
import io.github.zenthxsin.rustwright.pic.imaging.RgbaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URL
import java.util.Base64
import kotlin.coroutines.coroutineContext

/** PNG 解码（平台提供：Android/桌面用 Arc Pixmap，测试用 ImageIO）。 */
fun interface ImageDecoder {
    fun decode(bytes: ByteArray): RgbaImage
}

/**
 * 生图服务：调用 OpenAI 协议生图接口，原图存到 [dir]，贴图模式再像素化出 `_px.png`；
 * 每张图旁边一个 `.json` 记录提示词与模式，启动时扫描恢复图库。
 */
class PicServiceImpl(
    private val dir: File,
    private val events: Events,
    private val channel: () -> AiChannel?,
    private val decoder: ImageDecoder,
    private val clientFactory: (AiChannel, () -> Boolean) -> ImageClient = { ch, cancelled -> ImageClient(ch.token, ch.imageBaseUrl, cancelled) },
    /** 有生成任务进行中 / 全部结束（用于保持前台服务）。 */
    private val onBusy: (Boolean) -> Unit = {},
    private val benefitRelay: (suspend (String?) -> PicBenefitRelay)? = null,
    private val benefitReady: () -> Boolean = { false },
    private val benefitOption: suspend () -> String? = { null },
) : PicService {

    private val running = java.util.concurrent.atomic.AtomicInteger()
    private val galleryLock = Any()
    private val generationSlots = Semaphore(2)
    private val freeGenerator by lazy { benefitRelay?.let { FreePicGenerator(File(dir, ".free-requests"), it, clientFactory) } }

    @Serializable
    private data class Meta(val prompt: String, val mode: CreationMode, val createdAt: Long, val pixel: String? = null)

    private val json = Json { ignoreUnknownKeys = true }
    private val _images = MutableStateFlow(scan())
    override val images: StateFlow<List<PicImage>> = _images

    override val ready: Boolean get() = channel()?.imageModel?.isNotBlank() == true
    override val freeReady: Boolean get() = benefitRelay != null && benefitReady()
    override val selectedFree: Boolean get() = channel()?.imageModel?.startsWith(FreePicGenerator.FREE_PREFIX) == true
    override suspend fun freeOption(): String? = if (freeReady) benefitOption() else null
    override fun pendingFree(scope: String): PicRequest? = freeGenerator?.pending(scope)
    override suspend fun retryFree(scope: String): List<PicImage> = generate(pendingFree(scope) ?: error("没有待确认的免费图片请求"))

    override fun finalPrompt(request: PicRequest): String = SpritePrompt.build(request)

    override suspend fun generate(request: PicRequest): List<PicImage> {
        if (running.getAndIncrement() == 0) onBusy(true)
        try {
            return if (request.free) generateInner(request) else generationSlots.withPermit { generateInner(request) }
        } finally {
            if (running.decrementAndGet() == 0) onBusy(false)
        }
    }

    private suspend fun generateInner(request: PicRequest): List<PicImage> {
        require(request.prompt.isNotBlank()) { "提示词不能为空" }
        require(request.count in 1..4) { "一次最多生成 4 张" }
        require(request.spriteSize in 8..512) { "贴图尺寸需在 8-512 之间" }
        if (request.free) {
            check(freeReady) { "请先登录 NewAPI，免费生图不会使用付费线路" }
            val generator = freeGenerator ?: error("免费生图尚未配置")
            return generator.generate(request, finalPrompt(request), { id -> images.value.firstOrNull { it.id == id } }, ::saveFree)
        }
        val ch = channel()?.takeIf { !it.imageModel.isNullOrBlank() } ?: error("请先在${io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.assistant}设置里选择生图模型")
        check(!ch.imageModel!!.startsWith(FreePicGenerator.FREE_PREFIX)) { "请选择免费生图，不能用普通线路调用福利模型" }
        val job = coroutineContext.job
        val outputs = withContext(Dispatchers.IO) {
            val client = clientFactory(ch) { !job.isActive }
            val req = ImageRequest(finalPrompt(request), ch.imageModel!!, SpritePrompt.requestSize(request), request.count)
            if (request.references.isEmpty()) client.generate(req)
            else {
                require(request.references.size <= 4)
                client.edit(req, request.references.map { boundedRead(it.inputStream()) })
            }
        }
        val saved = withContext(Dispatchers.IO) {
            dir.mkdirs()
            val now = System.currentTimeMillis()
            outputs.mapIndexed { i, out ->
                val bytes = when (out) {
                    is ImageOutput.Inline -> decodeBase64(out.base64)
                    is ImageOutput.Remote -> download(out.url)
                }
                validatePng(bytes)
                val id = "img_${java.util.UUID.randomUUID()}"
                val file = galleryFile("$id.png").also { FreePicGenerator.atomicWrite(it, bytes) }
                val pixel = if (request.mode == CreationMode.GAME_SPRITE) {
                    runCatching { pixelateFile(file, request.spriteSize, File(dir, "${id}_px.png")) }.getOrNull()
                } else null
                FreePicGenerator.atomicWrite(galleryFile("$id.json"), json.encodeToString(Meta.serializer(), Meta(request.prompt, request.mode, now, pixel?.name)).toByteArray())
                PicImage(id, file, request.prompt, request.mode, now, pixel)
            }
        }
        synchronized(galleryLock) { _images.value = saved.reversed() + _images.value }
        events.post(ImagesGenerated(saved))
        return saved
    }

    private suspend fun saveFree(pending: FreePicPending, output: ImageOutput.Inline): PicImage {
        val bytes = decodeBase64(output.base64)
        require(bytes.size <= MAX_IMAGE_BYTES && bytes.size >= 24 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) { "免费图片格式不是 PNG" }
        fun int(offset: Int): Int = java.nio.ByteBuffer.wrap(bytes, offset, 4).int
        require(int(16) == 1024 && int(20) == 1024) { "免费图片尺寸不是 1024×1024" }
        val decoded = decoder.decode(bytes)
        require(decoded.width == 1024 && decoded.height == 1024) { "免费图片无法解码" }
        val id = pending.imageId
        val file = File(dir, "$id.png")
        FreePicGenerator.atomicWrite(file, bytes)
        val pixel = if (pending.mode == CreationMode.GAME_SPRITE) runCatching {
            val px = PixelArtProcessor.process(decoded, PixelProcessingParams.forGeneratedSprite(pending.spriteSize, pending.spriteSize))
            File(dir, "${id}_px.png").also { FreePicGenerator.atomicWrite(it, PngEncoder.encode(px)) }
        }.getOrNull() else null
        FreePicGenerator.atomicWrite(File(dir, "$id.json"), json.encodeToString(Meta.serializer(), Meta(pending.prompt, pending.mode, pending.createdAt, pixel?.name)).toByteArray())
        val image = PicImage(id, file, pending.prompt, pending.mode, pending.createdAt, pixel)
        synchronized(galleryLock) { _images.value = listOf(image) + _images.value.filterNot { it.id == id } }
        events.post(ImagesGenerated(listOf(image)))
        return image
    }

    override suspend fun pixelate(image: PicImage, size: Int): PicImage = withContext(Dispatchers.Default) {
        require(size in 8..512) { "贴图尺寸需在 8-512 之间" }
        val bytes = boundedRead(galleryFile("${image.id}.png").inputStream())
        validatePng(bytes)
        val px = PixelArtProcessor.process(decoder.decode(bytes), PixelProcessingParams.forGeneratedSprite(size, size))
        synchronized(galleryLock) {
        check(_images.value.any { it.id == image.id }) { "图片已被删除" }
        val out = galleryFile("${image.id}_px.png").also { FreePicGenerator.atomicWrite(it, PngEncoder.encode(px)) }
        val meta = Meta(image.prompt, image.mode, image.createdAt, out.name)
        FreePicGenerator.atomicWrite(galleryFile("${image.id}.json"), json.encodeToString(Meta.serializer(), meta).toByteArray())
        val updated = image.copy(pixel = out)
        _images.value = _images.value.map { if (it.id == image.id) updated else it }
        updated
        }
    }

    override fun delete(image: PicImage) = synchronized(galleryLock) {
        listOf("${image.id}.png", "${image.id}_px.png", "${image.id}.json").forEach { galleryFile(it).delete() }
        _images.value = _images.value.filterNot { it.id == image.id }
    }

    private fun pixelateFile(src: File, size: Int, dest: File): File {
        val bytes = boundedRead(src.inputStream())
        validatePng(bytes)
        val decoded = decoder.decode(bytes)
        val px = PixelArtProcessor.process(decoded, PixelProcessingParams.forGeneratedSprite(size, size))
        FreePicGenerator.atomicWrite(dest, PngEncoder.encode(px))
        return dest
    }

    private suspend fun download(url: String): ByteArray {
        require(url.startsWith("https://")) { "图片地址必须是 HTTPS" }
        return io.github.zenthxsin.rustwright.core.runtime.net.HttpFetch.get(url, MAX_IMAGE_BYTES, 30_000)
    }

    private fun galleryFile(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9_.-]+")) && name != "." && name != "..")
        return File(dir, name).canonicalFile.also { require(it.parentFile == dir.canonicalFile) { "图库路径越界" } }
    }

    private fun decodeBase64(value: String): ByteArray {
        require(value.length.toLong() <= (MAX_IMAGE_BYTES.toLong() + 2) / 3 * 4) { "图片过大" }
        return Base64.getDecoder().decode(value).also { require(it.size <= MAX_IMAGE_BYTES) }
    }

    private fun boundedRead(input: java.io.InputStream): ByteArray = input.use {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2)
        while (true) {
            check(System.nanoTime() < deadline) { "图片读取超时" }
            val count = it.read(buffer); if (count < 0) break
            require(result.size() + count <= MAX_IMAGE_BYTES) { "图片过大" }; result.write(buffer, 0, count)
        }
        result.toByteArray()
    }

    private fun validatePng(bytes: ByteArray) {
        require(bytes.size in 24..MAX_IMAGE_BYTES && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) { "图片格式不是 PNG" }
        val width = java.nio.ByteBuffer.wrap(bytes, 16, 4).int
        val height = java.nio.ByteBuffer.wrap(bytes, 20, 4).int
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216) { "图片像素数量过大" }
    }

    private fun scan(): List<PicImage> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { metaFile ->
            val id = metaFile.name.removeSuffix(".json")
            val file = runCatching { galleryFile("$id.png") }.getOrNull()?.takeIf { it.isFile } ?: return@mapNotNull null
            if (metaFile.canonicalFile.parentFile != dir.canonicalFile || metaFile.length() > 65536) return@mapNotNull null
            val meta = runCatching { json.decodeFromString(Meta.serializer(), metaFile.readText()) }.getOrNull() ?: return@mapNotNull null
            PicImage(id, file, meta.prompt, meta.mode, meta.createdAt,
                meta.pixel?.takeIf { it == "${id}_px.png" }?.let { runCatching { galleryFile(it) }.getOrNull() }?.takeIf { it.isFile })
        }.sortedByDescending { it.createdAt }

    private companion object {
        const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
    }
}
