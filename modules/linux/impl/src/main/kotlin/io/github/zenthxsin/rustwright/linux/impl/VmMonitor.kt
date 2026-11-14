package io.github.zenthxsin.rustwright.linux.impl

import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 虚拟机状态（终端页下方抽屉）：CPU、内存、存储、网络和占用最高的几个进程。
 * 能读到什么就显示什么：/proc/stat、/proc/meminfo、/proc/net/dev 读不到时那一项为 null。
 */
class VmMonitor(private val env: LinuxEnvironment, private val dataDir: File, private val scope: CoroutineScope) {
    data class Proc(val name: String, val cpu: Float, val memMb: Int)

    @Volatile var cpu: Float? = null; private set
    @Volatile var memUsedGb: Float? = null; private set
    @Volatile var memTotalGb: Float? = null; private set
    @Volatile var diskUsedGb: Float? = null; private set
    @Volatile var diskFreeGb: Float? = null; private set
    @Volatile var netMbps: Float? = null; private set
    @Volatile var procs: List<Proc> = emptyList(); private set

    private var job: Job? = null
    private var lastCpu: LongArray? = null
    private var lastNet: Long? = null
    private var lastNetAt = 0L
    private var rootfsBytes: Long? = cachedRootfs

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            // 统计一次要几秒，结果在进程内缓存，后面再开抽屉直接有值（打开时先用上次的数）
            if (env.managed && (cachedRootfs == null || System.currentTimeMillis() - cachedAt > 600_000)) launch {
                runCatching { env.toHost("/").walkTopDown().onEnter { !it.name.startsWith("proc") }.filter { it.isFile }.sumOf { it.length() } }.getOrNull()?.let {
                    rootfsBytes = it; cachedRootfs = it; cachedAt = System.currentTimeMillis()
                }
            }
            var tick = 0
            while (isActive) {
                sample()
                if (tick++ % 2 == 0) procs = runCatching { ps() }.getOrDefault(procs)
                delay(1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun sample() {
        runCatching {
            val f = File("/proc/stat").readLines().first().trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
            val idle = f[3] + f.getOrElse(4) { 0 }
            val total = f.sum()
            lastCpu?.let { (t0, i0) -> if (total > t0) cpu = 100f * (1f - (idle - i0).toFloat() / (total - t0)) }
            lastCpu = longArrayOf(total, idle)
        }.onFailure { cpu = procs.sumOf { it.cpu.toDouble() }.toFloat().takeIf { procs.isNotEmpty() }?.coerceAtMost(100f) }
        runCatching {
            val kv = File("/proc/meminfo").readLines().associate { l -> l.substringBefore(':') to l.substringAfter(':').trim().substringBefore(' ').toLong() }
            val total = kv.getValue("MemTotal")
            val avail = kv["MemAvailable"] ?: kv.getValue("MemFree")
            memTotalGb = total / 1048576f
            memUsedGb = (total - avail) / 1048576f
        }
        runCatching {
            diskFreeGb = dataDir.usableSpace / 1073741824f
            diskUsedGb = (rootfsBytes ?: return@runCatching) / 1073741824f
        }
        runCatching {
            // Android 10+ 不让普通应用读 /proc/net/dev，退回系统的 TrafficStats（反射，桌面端没有）
            val rx = runCatching { File("/proc/net/dev").readLines().drop(2).sumOf { l -> l.substringAfter(':').trim().split(Regex("\\s+")).first().toLong() } }
                .getOrElse { Class.forName("android.net.TrafficStats").getMethod("getTotalRxBytes").invoke(null) as Long }
            if (rx < 0) return@runCatching
            val now = System.currentTimeMillis()
            lastNet?.let { r0 -> if (now > lastNetAt) netMbps = (rx - r0) / 1048576f / ((now - lastNetAt) / 1000f) }
            lastNet = rx
            lastNetAt = now
        }
    }

    /** 环境里按 CPU 排序的前 3 个进程。 */
    private fun ps(): List<Proc> {
        val p = env.start("ps -eo comm=,pcpu=,rss= --sort=-pcpu 2>/dev/null | grep -vE \"^(ps|head|grep|sh) \" | head -3")
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.lines().mapNotNull { l ->
            val parts = l.trim().split(Regex("\\s+"))
            if (parts.size < 3) null else Proc(parts.dropLast(2).joinToString(" "), parts[parts.size - 2].toFloatOrNull() ?: 0f, (parts.last().toIntOrNull() ?: 0) / 1024)
        }
    }

    companion object {
        /** 环境「开机」时间：第一次打开终端或助手第一次执行命令时。 */
        val bootAt = System.currentTimeMillis()
        @Volatile private var cachedRootfs: Long? = null
        @Volatile private var cachedAt = 0L
    }
}
