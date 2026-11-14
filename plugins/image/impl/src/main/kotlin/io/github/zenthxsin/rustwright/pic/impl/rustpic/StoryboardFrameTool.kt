package io.github.zenthxsin.rustwright.pic.impl.rustpic

import io.github.zenthxsin.rustwright.core.agent.*
import io.github.zenthxsin.rustwright.pic.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Reuses the actual image generation approval, route and project-export behavior. */
class StoryboardFrameTool(private val workspace: ImageWorkspace, private val images: PicService) :
    Tool(), ProjectAware {
    override val name = "generate_storyboard_frame"
    override val description =
        "Generate one selected frame from a persisted storyboard plan, using its exact shared prompt and shot text. Requires observed version and zero-based frame. Consumes image-model quota; free-route restrictions and existing generation approvals still apply."
    override val risk = ToolRisk.EXTERNAL
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for (key in listOf("path", "version")) putJsonObject(key) { put("type", "string") }
            putJsonObject("frame") {
                put("type", "integer")
                put("minimum", 0)
                put("maximum", 23)
            }
            putJsonObject("mode") {
                put("type", "string")
                putJsonArray("enum") {
                    add("sprite")
                    add("general")
                }
            }
            putJsonObject("sprite_size") {
                put("type", "integer")
                put("minimum", 8)
                put("maximum", 512)
            }
        }
        putJsonArray("required") {
            add("path")
            add("version")
            add("frame")
        }
        put("additionalProperties", false)
    }

    override suspend fun preview(ctx: ToolContext, args: JsonObject): String {
        val root = requireNotNull(ctx.projectDir) { "A conversation project is required" }
        val saved = workspace.readPlan(root, args.getValue("path").jsonPrimitive.content)
        require(saved.version == args.getValue("version").jsonPrimitive.content) {
            "Plan changed; read it again"
        }
        val frame = args.getValue("frame").jsonPrimitive.int
        require(frame in saved.plan.shots.indices)
        return "Generate storyboard frame ${frame+1}/${saved.plan.shots.size}\n\n" +
            saved.plan.imagePrompt +
            "\n\nFrame ${frame+1}/${saved.plan.shots.size}: " +
            saved.plan.shots[frame]
    }

    override suspend fun execute(userId: String, args: JsonObject) =
        ToolResult.failure("project_required", "A conversation project is required")

    override suspend fun executeInProject(
        userId: String,
        args: JsonObject,
        project: ToolProject,
    ): ToolResult =
        try {
            val saved =
                workspace.readPlan(project.directory, args.getValue("path").jsonPrimitive.content)
            require(saved.version == args.getValue("version").jsonPrimitive.content) {
                "Plan changed; read it again"
            }
            val frame = args.getValue("frame").jsonPrimitive.int
            require(frame in saved.plan.shots.indices)
            val request = buildJsonObject {
                put(
                    "prompt",
                    saved.plan.imagePrompt +
                        "\n\nFrame ${frame+1}/${saved.plan.shots.size}: " +
                        saved.plan.shots[frame],
                )
                put("mode", args["mode"]?.jsonPrimitive?.content ?: "general")
                put("sprite_size", args["sprite_size"]?.jsonPrimitive?.int ?: 64)
                put("count", 1)
            }
            GenerateImageTool(images).executeInProject(userId, request, project)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.failure(
                "storyboard_generation_failed",
                e.message ?: "Storyboard frame failed",
            )
        }
}
