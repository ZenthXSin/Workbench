package io.github.zenthxsin.rustwright.fused

import java.io.File
import java.nio.file.Files

/** 产物操作按所属项目解析，不跟随当前聊天或 UI 选中的项目。 */
internal object ArtifactFiles {
    fun resolve(root: File, artifact: Artifact): File? = runCatching {
        val project = root.canonicalFile
        val file = File(project, artifact.id)
        val real = file.canonicalFile
        require(real.path.startsWith(project.path + File.separator))
        require(!Files.isSymbolicLink(file.toPath()) && file.isFile)
        artifact.path?.let { require(File(it).canonicalFile == real) }
        file
    }.getOrNull()
}
