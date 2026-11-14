package io.github.zenthxsin.rustwright.fused

import io.github.zenthxsin.rustwright.core.codex.CodexProject

/** ProjectServices: a separately implementable workbench capability. */
interface ProjectServices {
    fun currentProjectId(): String?
    fun setCurrentProject(id: String)
    suspend fun createProject(): CodexProject?
    /** Plugin project actions and standard ZIP export, rename, delete. */
    fun projectActions(p: CodexProject): List<MenuHost.Item> = emptyList()
    suspend fun exportProjectSource(p: CodexProject) {}
    /** 项目 Git 状态和补丁；项目未启用 Git 时返回 null。 */
    suspend fun projectDiff(p: CodexProject): String? = null
    suspend fun renameProject(p: CodexProject) {}
    suspend fun deleteProject(p: CodexProject) {}
    /** Import a project archive using a registered project type. */
    suspend fun importProject(type: String? = null): CodexProject? = null
    /** 项目统计「14 个单位 · 22 张贴图 · 3 张地图」。 */
    fun projectStats(p: CodexProject): String = ""
    fun projectStarters(project: CodexProject): List<io.github.zenthxsin.rustwright.ProjectStarter> = emptyList()
}
