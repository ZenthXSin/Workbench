package io.github.zenthxsin.rustwright

import io.github.zenthxsin.rustwright.core.agent.BundledSkill
import io.github.zenthxsin.rustwright.core.agent.Skills
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest

/** Owns only hash-verified rw-builtin-* directories. Hand-written and learned skills are never replaced. */
class BundledSkillSync(private val root: File, trustedHome: File = root.absoluteFile.parentFile) {
    // 系统可通过符号链接暴露应用目录；仅信任调用方指定的 home，检查其下的技能路径。
    private val boundary = trustedHome.toPath().toAbsolutePath().normalize()
    data class Result(val installed: Int, val removed: Int, val warnings: List<String>)

    fun sync(skills: List<BundledSkill>, disabled: Set<String>): Result = synchronized(LOCK) {
        require(skills.map { it.id }.distinct().size == skills.size) { "Duplicate bundled skill ids" }
        try {
            checkSafe(root)
            root.mkdirs(); check(root.isDirectory) { "Cannot create skills directory" }
        } catch (e: Exception) {
            return@synchronized Result(0, 0, listOf("随包技能同步失败：${e.message}"))
        }
        val warnings = mutableListOf<String>()
        var installed = 0; var removed = 0
        val enabled = skills.filter { it.toolset !in disabled }.associateBy { "rw-builtin-${it.id}" }
        root.listFiles().orEmpty().filter { it.name.startsWith("rw-builtin-") && it.name !in enabled }.forEach { dir ->
            // 删不掉（权限、占用）只记一条警告，不能让整次同步中断：后面启用的技能还要照常安装 / 更新
            val outcome = runCatching { if (ownedAndUnchanged(dir)) dir.deleteRecursively() else null }
            when (outcome.getOrNull()) {
                true -> removed++
                false -> warnings += "过期技能没删干净，下次再试：${dir.name}"
                null -> warnings += outcome.exceptionOrNull()?.let { "过期技能删除失败：${dir.name}（${it.message}）" } ?: "保留未经托管或已修改的技能：${dir.name}"
            }
        }
        enabled.forEach { (name, skill) ->
            val dest = File(root, name)
            try {
                checkSafe(dest)
                if (dest.exists() && !ownedAndUnchanged(dest)) {
                    warnings += "保留未经托管或已修改的技能：$name"; return@forEach
                }
                val files = skill.files().toMutableMap()
                require(files.size in 1..500) { "Invalid skill resource count" }
                require(files.values.sumOf { it.size.toLong() } <= 16 * 1024 * 1024) { "Skill resources too large" }
                files.keys.forEach(::checkRelative)
                require(MANIFEST !in files) { "Reserved skill resource" }
                val raw = files["SKILL.md"]?.toString(Charsets.UTF_8) ?: error("SKILL.md missing")
                val parsed = Skills.parse(name, raw) ?: error("Skill description missing")
                // Codex skill names must be ASCII slugs. JSON quoting is also valid YAML scalar quoting.
                files["SKILL.md"] = ("---\nname: $name\ndescription: ${JsonPrimitive(parsed.description)}\n---\n\n" + parsed.body + "\n").toByteArray()
                val hashes = files.mapValues { hash(it.value) }
                if (dest.exists() && readManifest(dest) == hashes) return@forEach
                val stage = Files.createTempDirectory(root.toPath(), ".rw-skill-").toFile()
                var backup: File? = null
                try {
                    files.forEach { (relative, bytes) -> File(stage, relative).apply { parentFile.mkdirs(); writeBytes(bytes) } }
                    File(stage, MANIFEST).writeText(buildJsonObject { put("owner", OWNER); putJsonObject("files") { hashes.forEach { (path, value) -> put(path, value) } } }.toString())
                    // Recheck immediately before replacement so changes during resource loading are preserved.
                    check(!dest.exists() || ownedAndUnchanged(dest)) { "Skill changed while synchronizing" }
                    if (dest.exists()) {
                        backup = File(root, ".rw-skill-backup-${java.util.UUID.randomUUID()}")
                        Files.move(dest.toPath(), backup.toPath(), ATOMIC_MOVE)
                    }
                    try { Files.move(stage.toPath(), dest.toPath(), ATOMIC_MOVE) }
                    catch (e: Exception) { backup?.let { Files.move(it.toPath(), dest.toPath(), ATOMIC_MOVE) }; backup = null; throw e }
                    backup?.deleteRecursively(); installed++
                } finally { stage.deleteRecursively() }
            } catch (e: Exception) { warnings += "$name：${e.message}" }
        }
        return Result(installed, removed, warnings)
    }

    private fun checkRelative(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid skill resource path" }
    }
    private fun checkSafe(file: File) {
        var path = file.toPath().toAbsolutePath().normalize()
        require(path.startsWith(boundary)) { "技能路径超出用户目录" }
        while (path != boundary) {
            require(!Files.isSymbolicLink(path)) { "技能目录内不允许符号链接：${path.fileName}" }
            path = path.parent
        }
    }
    private fun readManifest(dir: File): Map<String, String>? = runCatching {
        val path = File(dir, MANIFEST)
        checkSafe(path)
        if (!path.isFile || path.length() > 128 * 1024) return null
        val json = Json.parseToJsonElement(path.readText()).jsonObject
        if (json["owner"]?.jsonPrimitive?.content != OWNER) return null
        json["files"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }.also { it.keys.forEach(::checkRelative) }
    }.getOrNull()
    private fun ownedAndUnchanged(dir: File): Boolean = runCatching {
        checkSafe(dir)
        if (!dir.isDirectory) return false
        val hashes = readManifest(dir) ?: return false
        val actual = mutableSetOf<String>()
        Files.walk(dir.toPath()).use { paths -> paths.forEach { path ->
            if (Files.isSymbolicLink(path)) error("Symlink in skill")
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) actual += dir.toPath().relativize(path).toString().replace(File.separatorChar, '/')
        } }
        if (actual != hashes.keys + MANIFEST) return false
        hashes.all { (relative, expected) ->
            val file = File(dir, relative)
            Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= 16 * 1024 * 1024 && hash(file.readBytes()) == expected
        }
    }.getOrDefault(false)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    companion object {
        private val LOCK = Any()
        private const val OWNER = "rustwright-bundled-skills-v1"
        private const val MANIFEST = ".rustwright-skill.json"
    }
}
