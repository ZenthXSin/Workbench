package io.github.zenthxsin.rustwright.fused

import java.io.File
import arc.scene.Element
import io.github.zenthxsin.rustwright.core.codex.CodexProject
import io.github.zenthxsin.rustwright.core.codex.CodexTurn

/** FileServices: a separately implementable workbench capability. */
interface FileServices {
    /** 助手给的文件链接（/workspace/...、sandbox:/mnt/data/...、相对路径）→ 手机上的文件；找不到为 null。 */
    fun resolveFile(link: String): java.io.File? = null
    /** 保存到手机（系统的保存位置选择）。 */
    suspend fun saveFile(f: java.io.File) {}
    fun copy(text: String)
    /** 给贡献项用的外壳能力。 */
    fun editorSupport(file: File): io.github.zenthxsin.rustwright.ui.editor.api.EditorSupport? = null
    val ui: io.github.zenthxsin.rustwright.ui.kit.ProjectContext? get() = null
    fun filePresentation(file: File): io.github.zenthxsin.rustwright.FilePresentation? = null
    fun fileKinds(): List<Artifact.Kind> = listOf(Artifact.Kind.IMAGE, Artifact.Kind.FILE)
    fun openRegistered(file: File): Boolean = false
    fun workbenchHome(shell: FusedShell): Element? = null
    /** 产物（贴图、地图、单位文件……）。 */
    fun library(): List<Artifact>
    /** 某个项目的产物（产物页按项目筛选 / 全部项目时用）。 */
    fun libraryOf(p: CodexProject): List<Artifact> = emptyList()
    /** 重新验证产物与所属项目的路径对应关系。 */
    fun artifactFile(a: Artifact): File? = null
    suspend fun deleteArtifact(a: Artifact): Boolean = false
    fun openArtifact(a: Artifact): Element?
    /** 用像素编辑器打开一张图。 */
    fun editPixel(f: File) {}
    /** 这一轮做出来的产物（轮次期间改过的单位 / 贴图 / 地图），对话里显示成卡片。 */
    fun produced(turn: CodexTurn): List<Artifact> = emptyList()
}
