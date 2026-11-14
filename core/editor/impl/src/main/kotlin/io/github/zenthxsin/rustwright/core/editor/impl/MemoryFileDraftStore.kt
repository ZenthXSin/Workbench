package io.github.zenthxsin.rustwright.core.editor.impl

import io.github.zenthxsin.rustwright.core.editor.api.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Bounded process memory (old FileDraftStore was not a persistent on-disk recovery store). */
class MemoryFileDraftStore : FileDraftStore() {
    private val drafts = linkedMapOf<String, FileDraft>()
    private fun resolveProjectFile(root: File, path: String): File {
        val base = root.canonicalFile
        require(path.isNotBlank() && !File(path).isAbsolute) { "需要项目内相对路径" }
        return File(base, path).canonicalFile.also { require(it.toPath().startsWith(base.toPath()) && it != base) { "文件不在项目内" } }
    }
    private fun key(root: File, path: String) = resolveProjectFile(root, path).canonicalPath
    @Synchronized override fun open(root: File, path: String): FileDraft {
        val key = key(root, path)
        return drafts[key] ?: read(root, path).let { FileDraft(root, path, it, it) }
    }
    @Synchronized override fun retain(draft: FileDraft, text: String) {
        require(text.length <= 2 * 1024 * 1024) { "草稿过大" }
        val key = key(draft.root, draft.path)
        if (text == draft.baseline) drafts.remove(key) else drafts[key] = draft.copy(text = text)
        while (drafts.size > 32 || drafts.values.sumOf { it.baseline.length.toLong() + it.text.length } > 16 * 1024 * 1024) drafts.remove(drafts.keys.first())
    }
    @Synchronized override fun discard(draft: FileDraft) { drafts.remove(key(draft.root, draft.path)) }
    @Synchronized override fun save(draft: FileDraft, text: String): FileDraft {
        require(text.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "文件过大" }
        val file = resolveProjectFile(draft.root, draft.path)
        require(read(draft.root, draft.path) == draft.baseline) { "文件已被外部修改；请保留草稿并重新载入比较后再保存" }
        val temp = Files.createTempFile(file.parentFile.toPath(), ".editor-", ".tmp")
        try {
            Files.write(temp, text.toByteArray(Charsets.UTF_8))
            require(read(draft.root, draft.path) == draft.baseline) { "保存期间文件发生外部修改" }
            try { Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
        drafts.remove(file.canonicalPath)
        return draft.copy(baseline = text, text = text)
    }
    private fun read(root: File, path: String): String {
        val file = resolveProjectFile(root, path)
        require(file.length() <= 2 * 1024 * 1024) { "文件过大" }
        file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                require(out.size() + n <= 2 * 1024 * 1024) { "文件过大" }; out.write(buffer, 0, n)
            }
            return out.toString("UTF-8")
        }
    }
}
