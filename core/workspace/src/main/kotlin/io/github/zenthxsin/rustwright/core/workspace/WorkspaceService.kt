package io.github.zenthxsin.rustwright.core.workspace

import io.github.zenthxsin.rustwright.core.runtime.Contribution
import io.github.zenthxsin.rustwright.core.runtime.ExtensionPoint
import io.github.zenthxsin.rustwright.core.runtime.Extensions
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/** A project type owns its directories and lifecycle; the shell knows only these operations. */
interface ProjectType : Contribution {
    val title: String
    val root: File
    suspend fun create(name: String): File
    suspend fun imported(directory: File) {}
    /** Called after the prepared import has reached its final directory. */
    suspend fun published(directory: File) {}
    suspend fun renamed(directory: File, title: String) {}
    suspend fun delete(directory: File) { check(directory.deleteRecursively()) { "无法删除项目文件" } }
    fun accepts(archive: File): Boolean = false
}

object WorkspacePoints {
    val TYPES = ExtensionPoint<ProjectType>("workspace.types")
}

data class WorkspaceProject(val name: String, val directory: File, val type: String)

class WorkspaceService(private val extensions: Extensions, dataDir: File, private val preferredType: () -> String = { "directory" }) {
    private val ordinaryRoot = File(dataDir, "workspace/projects")
    private val ordinary = object : ProjectType {
        override val id = "directory"
        override val order = 100
        override val title = "普通项目"
        override val root = ordinaryRoot
        override suspend fun create(name: String): File = child(root, name).also {
            check(!it.exists()) { "项目已存在" }
            check(it.mkdirs()) { "无法创建项目目录" }
        }
    }

    fun types(): List<ProjectType> = (extensions.all(WorkspacePoints.TYPES) + ordinary).distinctBy { it.id }
    fun type(id: String): ProjectType = types().firstOrNull { it.id == id } ?: error("项目类型未安装：$id")
    fun defaultType(): String = preferredType().takeIf { id -> types().any { it.id == id } } ?: ordinary.id
    fun owner(directory: File): ProjectType? = types().firstOrNull {
        directory.canonicalFile.parentFile == it.root.canonicalFile
    }
    fun projects(): List<WorkspaceProject> = types().flatMap { type ->
        type.root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') && !Files.isSymbolicLink(it.toPath()) }
            .map { WorkspaceProject(it.name, it, type.id) }
    }

    suspend fun create(name: String, typeId: String = defaultType()): WorkspaceProject {
        validateName(name)
        val type = type(typeId)
        val dir = type.create(name)
        require(dir.canonicalFile.parentFile == type.root.canonicalFile && dir.isDirectory) { "插件返回的项目目录无效" }
        return WorkspaceProject(name, dir, type.id)
    }

    suspend fun importArchive(archive: File, typeId: String? = null): WorkspaceProject {
        val type = type(typeId ?: types().firstOrNull { it.accepts(archive) }?.id ?: ordinary.id)
        val root = type.root.canonicalFile.apply { check(isDirectory || mkdirs()) }
        val temporary = Files.createTempDirectory(root.toPath(), ".import-").toFile()
        try {
            var total = 0L
            ZipFile(archive).use { zip ->
                val entries = zip.entries().toList().filter { !it.isDirectory && !it.name.startsWith("__MACOSX/") }
                require(entries.isNotEmpty() && entries.size <= 20000) { "压缩包为空或文件数量过多" }
                entries.forEach {
                    require(!it.name.startsWith('/') && ':' !in it.name && '\\' !in it.name && it.name.split('/').none { part -> part == ".." }) { "压缩包包含越界路径" }
                }
                val tops = entries.map { it.name.substringBefore('/', "") }.toSet()
                val strip = tops.size == 1 && tops.first().isNotEmpty() && entries.all { '/' in it.name }
                for (entry in entries) {
                    val relative = if (strip) entry.name.substringAfter('/') else entry.name
                    val file = File(temporary, relative).canonicalFile
                    require(file.toPath().startsWith(temporary.toPath()) && file != temporary && !file.exists()) { "压缩包路径无效或重复" }
                    check(file.parentFile.isDirectory || file.parentFile.mkdirs())
                    zip.getInputStream(entry).use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var size = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n; size += n
                            require(size <= 512L * 1024 * 1024 && total <= 2L * 1024 * 1024 * 1024) { "解压体积超过限制" }
                            output.write(buffer, 0, n)
                        }
                    } }
                }
                val base = (if (strip) tops.first() else archive.nameWithoutExtension).removeSuffix("-工程").removeSuffix("-project")
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().takeIf { it.isNotBlank() && !it.startsWith('.') } ?: "导入的项目"
                var name = base; var number = 2
                while (child(root, name).exists()) name = "$base (${number++})"
                val target = child(root, name)
                type.imported(temporary)
                check(temporary.renameTo(target)) { "无法保存导入项目" }
                try { type.published(target) }
                catch (failure: Throwable) { target.deleteRecursively(); throw failure }
                return WorkspaceProject(name, target, type.id)
            }
        } finally { temporary.deleteRecursively() }
    }

    suspend fun rename(directory: File, title: String) {
        validateName(title)
        owner(directory)?.renamed(directory, title)
    }

    suspend fun delete(directory: File) {
        val type = owner(directory) ?: error("外部目录不能由应用删除")
        require(!Files.isSymbolicLink(directory.toPath())) { "不能删除符号链接项目" }
        type.delete(directory)
    }

    companion object {
        fun validateName(name: String) {
            require(name.isNotBlank() && !name.startsWith('.') && !Regex("[\\\\/:*?\"<>|\\p{Cntrl}]").containsMatchIn(name)) { "项目名称无效" }
        }
        private fun child(root: File, name: String): File {
            validateName(name)
            return File(root, name).also { require(it.canonicalFile.parentFile == root.canonicalFile) { "项目路径越界" } }
        }
    }
}
