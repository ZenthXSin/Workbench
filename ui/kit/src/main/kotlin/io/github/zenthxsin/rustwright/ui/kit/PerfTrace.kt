package io.github.zenthxsin.rustwright.ui.kit

/** 调体感用：-Drw.perfTrace=1 时把超过 2ms 的片段打出来；平时只多一次布尔判断。 */
object PerfTrace {
    @JvmField val on: Boolean = runCatching { System.getProperty("rw.perfTrace") != null }.getOrDefault(false)
    inline fun <T> span(name: String, block: () -> T): T {
        if (!on) return block()
        val t0 = System.nanoTime()
        try { return block() } finally {
            val ms = (System.nanoTime() - t0) / 1e6
            if (ms > 2) println("[trace] %-24s %.1fms".format(name, ms))
        }
    }
}
