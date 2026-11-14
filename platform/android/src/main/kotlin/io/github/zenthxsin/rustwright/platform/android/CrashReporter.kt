package io.github.zenthxsin.rustwright.platform.android

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃报告：
 * - Java/Kotlin 未捕获异常：写入 files/crash/last.txt，复制到剪贴板，并在独立进程里打开 [CrashActivity] 显示；
 * - 原生崩溃 / ANR（Android 11+）：下次启动时从系统的进程退出记录里取出，同样显示并复制。
 * 另保留最近日志（[recent]）附在报告末尾。
 */
object CrashReporter {
    private val recentLines = ArrayDeque<String>()
    private lateinit var app: Application

    fun install(application: Application) {
        app = application
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val report = build("未捕获异常（线程 ${thread.name}）", stackTrace(error))
                save(report)
                copy(report)
                app.startActivity(Intent(app, CrashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            if (previous != null && Build.VERSION.SDK_INT < 26) previous.uncaughtException(thread, error)
            Process.killProcess(Process.myPid())
            System.exit(10)
        }
    }

    /** 记录一行日志（保留最近 200 行，附在报告末尾）。 */
    @Synchronized
    fun recent(line: String) {
        recentLines.addLast("${time()} $line")
        while (recentLines.size > 200) recentLines.removeFirst()
    }

    /** 启动时检查：上次是否有未查看的崩溃（含系统记录的原生崩溃 / ANR）。返回报告文本。 */
    fun pending(context: Context): String? {
        file(context).takeIf { it.isFile }?.let { return it.readText() }
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val am = context.getSystemService(ActivityManager::class.java)
                val prefs = context.getSharedPreferences("rw_crash", Context.MODE_PRIVATE)
                val seen = prefs.getLong("seen", 0L)
                val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                    .firstOrNull { it.timestamp > seen && it.processName == context.packageName && it.reason in REASONS }
                if (info != null) {
                    prefs.edit().putLong("seen", info.timestamp).apply()
                    val trace = runCatching { info.traceInputStream?.use { it.readBytes() } }.getOrNull()
                    val body = buildString {
                        append("原因：").append(reasonName(info.reason)).append('\n')
                        append("描述：").append(info.description ?: "无").append('\n')
                        append("时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(info.timestamp))).append('\n')
                        if (trace != null && info.reason == ApplicationExitInfo.REASON_ANR) append('\n').append(String(trace).take(20_000))
                        else if (trace != null) append("\n（原生崩溃 tombstone ").append(trace.size).append(" 字节，已省略）")
                    }
                    return build("上次运行异常退出", body).also { save(it) }
                }
            }
        }
        return null
    }

    fun clear(context: Context) { file(context).delete() }

    fun copy(text: String) {
        runCatching {
            val cm = app.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("Rustwright 崩溃报告", text))
        }
    }

    private fun build(title: String, body: String): String = buildString {
        append("Rustwright 崩溃报告 · ").append(title).append('\n')
        val pkg = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        append("版本：").append(pkg?.versionName).append(" (").append(if (Build.VERSION.SDK_INT >= 28) pkg?.longVersionCode else @Suppress("DEPRECATION") pkg?.versionCode?.toLong()).append(")\n")
        append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(") · ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
        append("时间：").append(time()).append("\n\n")
        append(body.trim()).append("\n")
        synchronized(this@CrashReporter) {
            if (recentLines.isNotEmpty()) append("\n--- 最近日志 ---\n").append(recentLines.joinToString("\n")).append('\n')
        }
    }

    private fun save(report: String) = runCatching { file(app).apply { parentFile?.mkdirs() }.writeText(report) }

    private fun file(context: Context) = File(context.filesDir, "crash/last.txt")

    private fun stackTrace(e: Throwable) = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    private fun time() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())

    private val REASONS = if (Build.VERSION.SDK_INT >= 30) setOf(
        ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    ) else emptySet()

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃（Native crash）"
        ApplicationExitInfo.REASON_ANR -> "应用无响应（ANR）"
        ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        else -> "代码 $r"
    }
}

/** 应用入口：最先安装崩溃报告。 */
open class RwApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }

    // Optional plugin-provided resource pack for this process.
    override fun getResources(): android.content.res.Resources = ProcessResources.resources ?: super.getResources()
    override fun getAssets(): android.content.res.AssetManager = ProcessResources.resources?.assets ?: super.getAssets()
}

/** 当前进程的资源替身；由需要独立资源包的插件设置。 */
object ProcessResources {
    @Volatile var resources: android.content.res.Resources? = null
}
