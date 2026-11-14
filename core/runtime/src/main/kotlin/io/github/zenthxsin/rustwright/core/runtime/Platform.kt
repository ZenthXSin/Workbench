package io.github.zenthxsin.rustwright.core.runtime

import kotlinx.coroutines.CoroutineDispatcher
import java.io.File

/**
 * 平台能力接口（参考 Mindustry 的 Platform）：core 与模块只通过它触达系统，
 * Android 实现在 platform/android，测试里可用假实现。
 */
interface Platform {
    /** 应用私有数据目录（工程、会话、数据库）。 */
    val dataDir: File

    /** 可随时清理的缓存目录。 */
    val cacheDir: File

    val appVersion: String

    /** 用系统浏览器打开链接。 */
    fun openUri(uri: String)

    /** 系统分享一个文件。 */
    fun shareFile(file: File, mimeType: String)

    fun clipboardText(): String?
    fun setClipboardText(text: String)

    /** 短提示。 */
    fun toast(message: String)

    /** 让用户选择要导入的文件，返回已复制到 [cacheDir] 的本地副本；取消返回 null。 */
    suspend fun pickFile(mimeTypes: List<String>): File?

    /** 多选文件；返回本地副本，取消返回空列表。 */
    suspend fun pickFiles(mimeTypes: List<String>): List<File> = listOfNotNull(pickFile(mimeTypes))

    /** 用相机拍一张照片，返回 [cacheDir] 里的 JPEG；不支持或取消返回 null。 */
    suspend fun takePhoto(): File? = null

    /** 把文件导出到用户选择的位置；取消返回 false。 */
    suspend fun exportFile(file: File, suggestedName: String, mimeType: String): Boolean

    /**
     * 安装已下载并校验过 SHA-256 的更新包。实现需再校验包名、版本号高于当前、签名与当前应用一致，
     * 不满足时抛 [IllegalArgumentException]。
     */
    fun installUpdate(apk: File, expectedVersionCode: Int)

    /** 当前应用的版本号（versionCode）。 */
    val appVersionCode: Int

    /** 打开随包资源（Android assets）；不存在返回 null。 */
    fun openAsset(path: String): java.io.InputStream?

    /** 列出随包资源目录下的直接子项名。 */
    fun listAssets(dir: String): List<String>

    /**
     * 标记有需要在后台继续的工作（助手回合、生图等）。[key] 区分来源；任一来源活跃时平台保持前台服务，
     * 全部结束后停止。默认无操作。
     */
    fun backgroundWork(key: String, active: Boolean, label: String = "") {}

    /** 后台保活要用户打开的几项（Android：悬浮窗、电池优化、通知、厂商自启动）；桌面为空。 */
    fun keepAliveItems(): List<KeepAliveItem> = emptyList()

    /**
     * 安装包签名是不是官方正式签名（调试版、桌面版不检查，返回 true）。
     * 对不上说明包被改过重签：不登录、不发令牌、不启动助手。
     */
    val signatureOk: Boolean get() = true

    /** 打开某一项对应的系统设置或权限请求。 */
    fun openKeepAliveItem(id: String) {}

    /**
     * 需要在 UI 暂停后继续的状态任务。前台与 [foreground] 同线程；后台必须与 UI 安全交接，
     * 不能同时读写共享状态。UI 操作本身始终使用原 [foreground]。默认不切换线程。
     */
    fun persistentDispatcher(foreground: CoroutineDispatcher): CoroutineDispatcher = foreground

    /** 系统当前是否为深色模式（主题「跟随系统」时用）。 */
    val systemDarkMode: Boolean get() = false

    /** 应用主题切换后调整系统状态栏 / 导航栏的图标深浅；[background] 为 ARGB。 */
    fun applySystemBars(dark: Boolean, background: Int) {}
}

/**
 * 一项后台保活设置。[granted] 为 null 表示系统不让查（如厂商自启动），只能引导用户去看。
 * [essential] 的项没开时，任务开始会提醒一次。
 */
data class KeepAliveItem(val id: String, val title: String, val description: String, val granted: Boolean?, val essential: Boolean = false)
