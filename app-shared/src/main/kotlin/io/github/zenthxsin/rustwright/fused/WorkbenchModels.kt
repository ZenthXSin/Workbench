package io.github.zenthxsin.rustwright.fused

import arc.graphics.Color
import arc.scene.Element
import io.github.zenthxsin.rustwright.ui.kit.PI

/** 一轮结束后的自动检查结果：每个文件的问题（只含 error / warn）。 */
data class Diagnostic(val severity: String, val message: String, val line: Int = 0, val context: String? = null)

data class TurnCheck(val files: List<Pair<String, List<Diagnostic>>>) {
    val errors get() = files.sumOf { f -> f.second.count { it.severity == "error" } }
    val warns get() = files.sumOf { f -> f.second.count { it.severity == "warn" } }

    /** 发给助手的修复请求；[errorsOnly] 时只列 error（自动修只盯会让游戏跑不起来的问题）。 */
    fun fixRequest(errorsOnly: Boolean): String {
        val list = files.mapNotNull { (f, ps) ->
            val keep = ps.filter { !errorsOnly || it.severity == "error" }.take(15)
            if (keep.isEmpty()) null
            else f + "：\n" + keep.joinToString("\n") { "  " + (if (it.line > 0) "第 ${it.line} 行 " else "") + "${it.severity}：${it.message}" }
        }.joinToString("\n")
        return (if (errorsOnly) AUTO_PREFIX else "") + "项目检查发现这些问题，帮我修好并重新验证：\n$list"
    }

    companion object {
        /** 自动修复请求的开头，用来数连续自动修了几轮。 */
        const val AUTO_PREFIX = "【自动检查】"
        /** 连续自动修最多几轮，还没修好就停下交给用户。 */
        const val MAX_AUTO_ROUNDS = 2
    }
}

data class EnvStatus(val ready: Boolean, val progress: Float?, val detail: String)
data class ToolGroup(val title: String, val tint: Color, val ink: Color, val tools: List<ToolEntry>)
/** 「更多」里的工具；[open] 为 null 时显示原型的占位页（说明 +「让助手代劳」，[ask] 是交给助手时的话）。 */
data class ToolEntry(val id: String, val name: String, val desc: String, val icon: String, val ask: String = "", val open: (() -> Element?)? = null)
data class Artifact(
    val id: String, val kind: Kind, val name: String, val meta: String, val projectId: String?,
    /** 预览（贴图缩略图等），可空。 */
    val preview: (() -> Element?)? = null, val path: String? = null,
    val card: ((Artifact, (Artifact) -> Unit) -> Element?)? = null,
) {
    data class Kind(val id: String, val label: String, val icon: String, val order: Int = 100) {
        companion object {
            val IMAGE = Kind("image", "图片", PI.img, 100)
            val FILE = Kind("file", "文件", PI.file, 200)
        }
    }
}
data class BuiltinToolset(val id: String, val title: String, val description: String, val tools: Int, val enabled: Boolean)
data class SettingRow(val icon: String, val title: String, val sub: () -> String?, val open: () -> Unit)
