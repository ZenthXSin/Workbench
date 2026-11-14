package io.github.zenthxsin.rustwright.pic.impl.rustpic

import io.github.zenthxsin.rustwright.core.agent.PathAware
import io.github.zenthxsin.rustwright.core.agent.ProjectAware
import io.github.zenthxsin.rustwright.core.agent.ToolProject
import io.github.zenthxsin.rustwright.pic.api.CreationMode
import io.github.zenthxsin.rustwright.pic.api.PicRequest
import io.github.zenthxsin.rustwright.pic.api.PicService
import io.github.zenthxsin.rustwright.core.agent.Tool
import io.github.zenthxsin.rustwright.core.agent.ToolContext
import io.github.zenthxsin.rustwright.core.agent.ToolResult
import io.github.zenthxsin.rustwright.core.agent.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull

/**
 * 助手工具：生图。风险为 EXTERNAL（产生费用），审批卡片会展示模型、张数和发给服务的最终提示词——
 * 这就是 RustPic 的「生成前确认」，任何模式下都不会静默执行。
 */
class GenerateImageTool(private val service: PicService, private val projectDir: () -> File? = { null }) : Tool(), ProjectAware {
    override val name = "generate_image"
    override val description = "Generate images. Sprite mode also creates a pixelated PNG. Free conversations use the logged-in NewAPI image benefit: one 1024 PNG, no references, shares daily map/image count. If a free request is pending, use only retry=true to recover it; never change parameters to retry. Other models follow the selected paid image route."
    override val risk = ToolRisk.EXTERNAL
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("prompt") { put("type", "string"); put("description", "Settled visual description of the subject, in English or Chinese") }
            putJsonObject("mode") { put("type", "string"); putJsonArray("enum") { add(JsonPrimitive("sprite")); add(JsonPrimitive("general")) } }
            putJsonObject("sprite_size") { put("type", "integer"); put("description", "Logical pixel size for sprites, e.g. 32/48/64/96; default 64") }
            putJsonObject("count") { put("type", "integer"); put("description", "1-4, default 1") }
            putJsonObject("reference_ids") {
                put("type", "array"); putJsonObject("items") { put("type", "string") }
                put("description", "Gallery image ids (from list_images) to edit or use as references, max 4")
            }
            putJsonObject("retry") { put("type", "boolean"); put("description", "Recover the original pending free request; omit all other fields.") }
        }
    }

    private fun request(args: JsonObject): PicRequest {
        val ids = (args["reference_ids"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val refs = ids.take(4).mapNotNull { id -> service.images.value.firstOrNull { it.id == id }?.file }
        return PicRequest(
            prompt = args.str("prompt").orEmpty(),
            mode = if (args.str("mode") == "general") CreationMode.GENERAL else CreationMode.GAME_SPRITE,
            spriteSize = args.int("sprite_size") ?: 64,
            count = args.int("count") ?: 1,
            references = refs,
        )
    }

    override suspend fun preview(ctx: ToolContext, args: JsonObject): String {
        val r = request(args)
        return "生成 ${r.count} 张${if (r.mode == CreationMode.GAME_SPRITE) " ${r.spriteSize}px 贴图" else "图片"}" +
            (if (r.references.isNotEmpty()) "（参考图 ${r.references.size} 张）" else "") + "\n\n${r.prompt}"
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult = generate(args, projectDir(), service.selectedFree, "manual")

    override suspend fun executeInProject(userId: String, args: JsonObject, project: ToolProject): ToolResult =
        generate(args, project.directory, project.freeBenefits || service.selectedFree, "conversation:" + project.conversationId.orEmpty())

    private suspend fun generate(args: JsonObject, dir: File?, free: Boolean, scope: String): ToolResult {
        currentCoroutineContext().ensureActive()
        if (dir != null && !dir.isDirectory) return ToolResult.failure("project_unavailable", "The project directory is unavailable. Reopen the project before generating images.")
        if (free && !service.freeReady) return ToolResult.failure("login_required", "Sign in to NewAPI to use free images. The paid route will not be used.")
        if (!free && !service.ready) return ToolResult.failure("not_configured", "no image model is configured", suggestions = listOf("Ask the user to pick an image model in ${io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.assistant} settings"))
        val retry = (args["retry"] as? JsonPrimitive)?.booleanOrNull == true
        if (retry && (!free || args.keys.any { it != "retry" })) return ToolResult.failure("invalid_args", "Only retry=true is allowed when recovering a pending free request.")
        if (free && (args["reference_ids"] as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true) return ToolResult.failure("invalid_args", "Free images do not support references or edits.")
        val r = request(args).copy(free = free, requestScope = scope)
        return runCatching { if (retry) service.retryFree(scope) else service.generate(r) }.fold(
            { imgs ->
                currentCoroutineContext().ensureActive()
                // 使用调用所属项目；生成期间 UI 切项目也不会改变目标。贴图模式存像素化后的那张。
                val failures = ArrayList<String>()
                val saved = imgs.map { img ->
                    val src = img.pixel ?: img.file
                    dir?.let { d -> runCatching {
                        check(d.isDirectory) { "Project directory is no longer available" }
                        val dest = File(d, "images/${src.name}")
                        if (src.canonicalFile != dest.canonicalFile) src.copyTo(dest, overwrite = true)
                        "images/${src.name}"
                    }.onFailure { failures += "${img.id}: ${it.message}" }.getOrNull() }
                }
                val summary = imgs.zip(saved).joinToString("\n", prefix = "Generated ${imgs.size} image(s):\n") { (img, rel) ->
                    "- ${img.id}" + (img.pixel?.let { p -> " (sprite ${p.name})" } ?: "") + (rel?.let { r -> " saved to project as $r" } ?: "")
                }
                if (failures.isEmpty()) ToolResult(summary) else ToolResult.failure(
                    "project_save_failed", "$summary\nCould not save to the project: ${failures.joinToString("; ")}",
                    suggestions = listOf("The generated images are still in the gallery. Save them after fixing the project directory; do not regenerate them."),
                )
            },
            {
                if (it is CancellationException) throw it
                ToolResult.failure("generation_failed", it.message ?: "image generation failed", retryable = !free,
                    suggestions = if (free) listOf("If pending, use only retry=true to recover the original image, or save the existing image from the gallery. Do not regenerate with new parameters.") else emptyList())
            },
        )
    }
}

/** 助手工具：列出图库。 */
class ListImagesTool(private val service: PicService) : Tool() {
    override val name = "list_images"
    override val description = "List images in the 生图 gallery (newest first) with their ids and prompts."
    override val parameters: JsonObject = buildJsonObject { put("type", "object") }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val list = service.images.value.take(30)
        if (list.isEmpty()) return ToolResult("The gallery is empty.")
        return ToolResult(list.joinToString("\n") { "- ${it.id} [${it.mode}]${if (it.pixel != null) " sprite" else ""}: ${it.prompt.take(80)}" })
    }
}
