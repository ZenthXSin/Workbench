package io.github.zenthxsin.rustwright.core.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 检查与下载更新（移植自 RustPic UpdateRepository）：只接受 HTTPS、禁止重定向、限制体积、
 * 下载到 .part 后校验 SHA-256 再改名。阻塞调用，请在 IO 线程执行。
 */
class Updater(
    private val manifestUrl: String,
    private val cacheDir: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
    private val inactivityTimeoutMillis: Long = 60_000,
) {
    fun check(currentVersionCode: Int): UpdateCheckResult {
        val uri = URI(manifestUrl)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) { "更新清单必须使用 HTTPS" }
        val http = client.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).build()
        val text = http.newCall(Request.Builder().url(manifestUrl).header("Cache-Control", "no-cache").build()).execute().use { res ->
            check(res.isSuccessful) { "检查更新失败：HTTP ${res.code}" }
            res.body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= 65536) { "更新清单过大" }; out.write(buffer, 0, n) }
                out.toString("UTF-8")
            }
        }
        val info = UpdateInfo.parse(text)
        return if (info.versionCode > currentVersionCode) UpdateCheckResult.Available(info, info.isMandatoryFor(currentVersionCode))
        else UpdateCheckResult.UpToDate
    }

    fun download(update: UpdateInfo, onProgress: (DownloadProgress) -> Unit = {}): File {
        val uri = runCatching { URI(update.apkUrl) }.getOrElse { throw IllegalArgumentException("更新地址无效", it) }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) { "更新地址必须使用 HTTPS" }
        check(cacheDir.exists() || cacheDir.mkdirs()) { "无法创建更新缓存目录" }
        val dest = File(cacheDir, "rustwright-${update.versionCode}.apk")
        val partial = File(cacheDir, dest.name + ".part")
        val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
            .callTimeout(30, TimeUnit.MINUTES).readTimeout(inactivityTimeoutMillis, TimeUnit.MILLISECONDS).build()
        try {
            http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { res ->
                check(!res.isRedirect) { "更新地址重定向被禁止" }
                check(res.isSuccessful) { "更新下载失败：HTTP ${res.code}" }
                val body = res.body ?: error("更新下载响应为空")
                val total = body.contentLength().takeIf { it >= 0 }
                check(total == null || total <= MAX_APK_BYTES) { "更新文件过大" }
                onProgress(DownloadProgress(0, total, 0))
                body.byteStream().use { input ->
                    partial.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastBytes = 0L
                        var lastAt = System.nanoTime()
                        var speed = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            check(done <= MAX_APK_BYTES) { "更新文件过大" }
                            out.write(buf, 0, n)
                            val now = System.nanoTime()
                            if (now - lastAt >= 250_000_000L) {
                                speed = (done - lastBytes) * 1_000_000_000L / (now - lastAt)
                                onProgress(DownloadProgress(done, total, speed))
                                lastBytes = done
                                lastAt = now
                            }
                        }
                        onProgress(DownloadProgress(done, total, speed))
                    }
                }
            }
            require(sha256(partial).equals(update.sha256, ignoreCase = true)) { "更新文件校验失败" }
            check(!dest.exists() || dest.delete()) { "无法替换旧更新文件" }
            check(partial.renameTo(dest)) { "无法保存更新文件" }
            return dest
        } catch (e: Throwable) {
            partial.delete()
            throw e
        }
    }

    companion object {
        const val MAX_APK_BYTES = 512L * 1024 * 1024

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
