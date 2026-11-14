package io.github.zenthxsin.rustwright.core.agent

/**
 * 会话内「记住授权」（移植自助手 SessionApprovalRules）：只在当前会话有效，不落盘。
 * 按工具 + 工作区相对路径记忆；自动模式下还会记住写入文件的父目录前缀。
 * 无路径的调用永不匹配（必须重新询问）。
 */
class SessionApprovalRules {
    private val lock = Any()
    private val exact = HashMap<String, MutableSet<String>>()
    private val dirPrefixes = HashMap<String, MutableSet<String>>()

    fun clear() = synchronized(lock) { exact.clear(); dirPrefixes.clear() }

    fun remember(tool: String, paths: List<String>, includeParentDirs: Boolean = false) {
        val normalized = paths.map(::normalize)
        if (normalized.isEmpty() || normalized.any { it.isEmpty() }) return
        synchronized(lock) {
            exact.getOrPut(tool) { LinkedHashSet() }.addAll(normalized)
            if (includeParentDirs) {
                normalized.mapNotNull { p -> p.lastIndexOf('/').takeIf { it > 0 }?.let { p.substring(0, it + 1) } }
                    .let { dirPrefixes.getOrPut(tool) { LinkedHashSet() }.addAll(it) }
            }
        }
    }

    fun allows(tool: String, paths: List<String>): Boolean {
        val normalized = paths.map(::normalize)
        if (normalized.isEmpty() || normalized.any { it.isEmpty() }) return false
        synchronized(lock) {
            val known = exact[tool].orEmpty()
            val prefixes = dirPrefixes[tool].orEmpty()
            return normalized.all { p -> p in known || prefixes.any { p.startsWith(it) } }
        }
    }

    companion object {
        fun normalize(path: String): String {
            val parts = path.trim().replace('\\', '/').split('/')
            if (parts.any { it == ".." || it.any(Char::isISOControl) }) return ""
            return parts.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        }
    }
}
