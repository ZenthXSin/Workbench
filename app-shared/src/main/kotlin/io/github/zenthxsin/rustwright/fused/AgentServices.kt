package io.github.zenthxsin.rustwright.fused

import java.io.File
import io.github.zenthxsin.rustwright.core.codex.CodexTurn
import io.github.zenthxsin.rustwright.core.codex.CodexWorkbench

/** AgentServices: a separately implementable workbench capability. */
interface AgentServices {
    val wb: CodexWorkbench
    /** 自动检查这一轮改过的 INI 文件；没有要检查的文件返回 null。 */
    suspend fun checkTurn(turn: CodexTurn): TurnCheck? = null
    /** 检查项目里所有 INI 类文件：相对路径 → (错误数, 提醒数)；没有检查器时为 null。 */
    suspend fun lintProject(dir: File): Map<String, Pair<Int, Int>>? = null
    /** 让模型把第一条消息概括成 10 字内的标题；失败返回 null。 */
    suspend fun makeTitle(prompt: String): String? = null
    /** 向量模型（长期记忆、语义检索）；空字符串为关闭。 */
    fun embeddingModel(): String = ""
    fun embeddingModels(): List<String> = emptyList()
    fun setEmbeddingModel(id: String) {}
    fun imageModel(): String
    fun imageModels(): List<String>
    /** 可选对话模型：app-server 的 model/list，加上中转站 /models 里的对话模型（Codex 还没启动时也有）。 */
    fun chatModels(): List<io.github.zenthxsin.rustwright.core.codex.CodexModel> = wb.models
    fun setImageModel(id: String)
}
