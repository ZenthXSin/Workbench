package io.github.zenthxsin.rustwright.core.runtime

/**
 * 全局日志出口。默认打到标准输出，平台层（Android）在启动时替换 [sink]。
 * 文本里不要放令牌等敏感信息；需要时用 [redact]。
 */
object RwLog {
    fun interface Sink {
        fun log(level: Level, tag: String, message: String, error: Throwable?)
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }

    @Volatile
    var sink: Sink = Sink { level, tag, message, error ->
        println("[${level.name[0]}][$tag] $message")
        error?.printStackTrace()
    }

    fun d(tag: String, message: String) = sink.log(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = sink.log(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = sink.log(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = sink.log(Level.ERROR, tag, message, error)

    private val bearer = Regex("(?i)Bearer\\s+[^\\s,;]+")
    private val skKey = Regex("sk-[A-Za-z0-9_-]{8,}")

    /** 抹掉 Bearer 令牌与 sk- 密钥。 */
    fun redact(text: String): String = skKey.replace(bearer.replace(text, "Bearer ***"), "sk-***")
}
