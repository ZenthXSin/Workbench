package io.github.zenthxsin.rustwright

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import io.github.zenthxsin.rustwright.core.agent.ProjectAware
import io.github.zenthxsin.rustwright.core.agent.Tool
import io.github.zenthxsin.rustwright.core.agent.ToolProject
import io.github.zenthxsin.rustwright.core.agent.ToolResult
import io.github.zenthxsin.rustwright.core.runtime.Extensions
import io.github.zenthxsin.rustwright.ui.kit.ProjectContext
import io.github.zenthxsin.rustwright.ui.kit.UiPoints
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

// 本体基础工具组（Toolsets.CORE）：和 App 界面打交道的工具。读写文件、执行命令 Codex 自己就会，不在这里重复。

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

/** 在 App 里给用户打开项目里的文件：文本文件进编辑器，其余打开文件管理。 */
internal class OpenInAppTool(private val ui: () -> ProjectContext?) : Tool(), ProjectAware {
    override val name = "open_in_app"
    override val description = "Suggest a file of the current project for the user to look at in the Workbench app: shows a notification the user can tap to open it (text files in the editor, other files in the file manager). It never switches pages by itself, so use it sparingly, only for a file the user should check."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "Path relative to the project root") }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult =
        ToolResult.failure("project_unavailable", "No project for this call.")

    override suspend fun executeInProject(userId: String, args: JsonObject, project: ToolProject): ToolResult {
        val rel = args.str("path")?.trim()?.trimStart('/')?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.failure("invalid_argument", "path is required")
        val root = project.directory.canonicalFile
        val file = File(root, rel).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) return ToolResult.failure("invalid_argument", "path must stay inside the project")
        if (!file.isFile) return ToolResult.failure("not_found", "no file at $rel")
        val ctx = ui() ?: return ToolResult.failure("unavailable", "the app UI is not ready")
        // 不抢界面：只在顶部提示，用户点了才打开（直接切页面会把正在看对话的用户拽走）。
        // 只投递给界面线程，不等它：App 在后台时界面线程停着，等它会卡住这一轮
        arc.Core.app.post { ctx.suggest("${AppBrand.identity.assistant}想让你看看 ${file.name}，点这里打开") { ctx.openTool("file", file) } }
        return ToolResult("Showed a notification for $rel; the user can tap it to open the file. The current page did not change.")
    }

}

/** 打开「更多」里的某个工具页。 */
internal class ShowPageTool(private val extensions: Extensions, private val ui: () -> ProjectContext?) : Tool() {
    override val name = "show_page"
    override val description: String get() = "Suggest one of the app's tool pages to the user: shows a notification the user can tap to open it; it never switches pages by itself. Available pages: " +
        extensions.all(UiPoints.TOOLS).joinToString(", ") { "${it.id} (${it.name})" } + "."
    override val parameters: JsonObject get() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("page") {
                put("type", "string")
                putJsonArray("enum") { extensions.all(UiPoints.TOOLS).forEach { add(JsonPrimitive(it.id)) } }
            }
        }
        putJsonArray("required") { add(JsonPrimitive("page")) }
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val id = args.str("page") ?: return ToolResult.failure("invalid_argument", "page is required")
        val page = extensions.get(UiPoints.TOOLS, id) ?: return ToolResult.failure("not_found", "no page $id")
        val ctx = ui() ?: return ToolResult.failure("unavailable", "the app UI is not ready")
        arc.Core.app.post { ctx.suggest("${AppBrand.identity.assistant}建议打开「${page.name}」，点这里打开") { ctx.openTool(id) } }
        return ToolResult("Showed a notification suggesting ${page.name}; the user can tap it. The current page did not change.")
    }
}

/** App 状态：内置 Linux、当前项目、可用的工具页。 */
internal class AppStatusTool(private val status: () -> String) : Tool() {
    override val name = "app_status"
    override val description = "Report Workbench's state: built-in Linux status, the project open in the app, and the tool pages available."
    override val parameters: JsonObject = buildJsonObject { put("type", "object") }
    override suspend fun execute(userId: String, args: JsonObject) = ToolResult(status())
}
