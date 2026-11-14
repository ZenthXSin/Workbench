package io.github.zenthxsin.rustwright.core.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 更新清单（发布到公开下载站的 `api/<channel>.json`）：
 * ```json
 * {"versionCode":2,"versionName":"0.2.0","apkUrl":"https://…/rustwright-0.2.0.apk",
 *  "sha256":"…64位十六进制…","notes":["…"],"minSupportedVersionCode":1,"publishedAt":"2026-10-24"}
 * ```
 */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val title: String = "",
    val notes: List<String> = emptyList(),
    val minSupportedVersionCode: Int = 0,
    val publishedAt: String = "",
) {
    /** 当前版本低于最低支持版本时必须更新。 */
    fun isMandatoryFor(currentVersionCode: Int) = currentVersionCode < minSupportedVersionCode

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val SHA256 = Regex("[0-9a-fA-F]{64}")

        fun parse(text: String): UpdateInfo = json.decodeFromString(serializer(), text).also {
            require(it.versionCode > 0) { "更新版本号无效" }
            require(it.sha256.matches(SHA256)) { "更新摘要格式无效" }
            require(it.apkUrl.startsWith("https://")) { "更新地址必须使用 HTTPS" }
        }
    }
}

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val update: UpdateInfo, val mandatory: Boolean) : UpdateCheckResult
}

data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long?, val bytesPerSecond: Long) {
    val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}
