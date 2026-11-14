package io.github.zenthxsin.rustwright.pic.impl.draw

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

/** 助手工具：把图库中的图片像素化成指定尺寸的贴图。 */
class PixelateImageTool(private val service: PicService) : Tool(), PathAware {
    override val name = "pixelate_image"
    override val description = "Convert a gallery image into a pixel-art sprite of the given size (removes white background)."
    override val risk = ToolRisk.WRITE
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("id") { put("type", "string") }
            putJsonObject("size") { put("type", "integer"); put("description", "8-512, default 64") }
        }
        putJsonArray("required") { add(JsonPrimitive("id")) }
    }

    override fun touchedPaths(args: JsonObject) = listOf("images/${args.str("id")}_px.png")

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val img = service.images.value.firstOrNull { it.id == args.str("id") }
            ?: return ToolResult.failure("not_found", "no image with id ${args.str("id")}", suggestions = listOf("Call list_images to get ids"))
        val out = service.pixelate(img, args.int("size") ?: 64)
        return ToolResult("Saved sprite ${out.pixel?.name}.")
    }
}
