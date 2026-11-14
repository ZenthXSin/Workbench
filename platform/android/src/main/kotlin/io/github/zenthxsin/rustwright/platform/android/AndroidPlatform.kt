package io.github.zenthxsin.rustwright.platform.android

import kotlinx.coroutines.ensureActive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import arc.backend.android.AndroidApplication
import io.github.zenthxsin.rustwright.core.runtime.KeepAliveItem
import io.github.zenthxsin.rustwright.core.runtime.LifecycleDispatcher
import io.github.zenthxsin.rustwright.core.runtime.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** [Platform] 的 Android 实现；文件选择/导出走 SAF，结果经 Arc 的 activity result 监听回传。 */
class AndroidPlatform(private val activity: AndroidApplication) : Platform {

    override val signatureOk: Boolean by lazy { SignatureCheck.ok(activity) }

    private val stateThread = lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "rw-background-state").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    }
    private var stateDispatcher: LifecycleDispatcher? = null
    private var tasksPaused = false

    @Synchronized
    override fun persistentDispatcher(foreground: CoroutineDispatcher): CoroutineDispatcher =
        stateDispatcher ?: LifecycleDispatcher(foreground, stateThread.value).also {
            if (tasksPaused) it.pause()
            stateDispatcher = it
        }

    /** 只在 GLSurfaceView.onPause 已返回、GL 不再读写状态后调用。 */
    internal fun pauseTasks() {
        val dispatcher = synchronized(this) { tasksPaused = true; stateDispatcher }
        dispatcher?.pause()
    }

    /** 在重新启用 GL 之前等待后台当前片段结束，再把队列交还 Arc。 */
    internal fun resumeTasks() {
        // 等待执行片段时不持有平台锁，任务本身仍可能读取平台能力。
        val dispatcher = synchronized(this) { tasksPaused = false; stateDispatcher }
        dispatcher?.resume()
    }

    internal fun closeTasks() {
        if (stateThread.isInitialized()) stateThread.value.close()
    }

    override val dataDir: File = activity.filesDir
    override val cacheDir: File = activity.cacheDir

    override val appVersion: String =
        runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: "?"

    override val appVersionCode: Int =
        runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode } }.getOrDefault(0)

    override fun backgroundWork(key: String, active: Boolean, label: String) { BackgroundKeeper.update(key, active, label) }

    override fun keepAliveItems(): List<KeepAliveItem> = KeepAliveSettings.items(activity)

    override fun openKeepAliveItem(id: String) = ui { KeepAliveSettings.open(activity, id) }

    override val systemDarkMode: Boolean
        get() = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    override fun applySystemBars(dark: Boolean, background: Int) = ui {
        val window = activity.window
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = background
            window.navigationBarColor = background
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (dark) 0 else
                (android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (android.os.Build.VERSION.SDK_INT >= 26) android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0))
        }
    }

    override fun installUpdate(apk: File, expectedVersionCode: Int) {
        ApkInstaller.validate(activity, apk, expectedVersionCode)
        ui { activity.startActivity(ApkInstaller.intent(activity, apk)) }
    }

    override fun openAsset(path: String): java.io.InputStream? = runCatching { activity.assets.open(path) }.getOrNull()

    override fun listAssets(dir: String): List<String> = runCatching { activity.assets.list(dir)?.toList() }.getOrNull().orEmpty()

    private val clipboard get() = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun openUri(uri: String) = ui {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun shareFile(file: File, mimeType: String) = ui {
        val uri = RwFileProvider.uriFor(activity, file)
        val send = Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(send, null))
    }

    override fun clipboardText(): String? = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()

    override fun setClipboardText(text: String) = ui { clipboard.setPrimaryClip(ClipData.newPlainText("${io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.name}", text)) }

    override fun toast(message: String) = ui { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }

    override suspend fun pickFile(mimeTypes: List<String>): File? = pickDocuments(mimeTypes, false).firstOrNull()

    override suspend fun pickFiles(mimeTypes: List<String>): List<File> = pickDocuments(mimeTypes, true)

    private suspend fun pickDocuments(mimeTypes: List<String>, multiple: Boolean): List<File> {
        // GET_CONTENT + chooser 允许系统 DocumentsUI 和第三方文件管理器提供临时读取授权。
        val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).apply {
            type = mimeTypes.singleOrNull() ?: "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val result = startForResult(Intent.createChooser(intent, "选择文件")) ?: return emptyList()
        val uris = linkedSetOf<Uri>()
        result.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
        result.data?.let { uris += it }
        if (uris.isEmpty()) return emptyList()
        require(uris.size <= 128) { "一次最多选择 128 个文件" }
        return withContext(Dispatchers.IO) {
            val parent = File(cacheDir, "imports").apply { mkdirs() }
            val batch = java.nio.file.Files.createTempDirectory(parent.toPath(), "selection-").toFile()
            try {
                var total = 0L
                uris.take(if (multiple) 128 else 1).map { uri ->
                    val original = runCatching { displayName(uri) }.getOrNull()
                    val name = original?.replace(Regex("[\\\\/\u0000-\u001f]"), "_")
                        ?.take(180)?.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "file.bin"
                    val out = io.github.zenthxsin.rustwright.core.runtime.ImportedFiles.available(batch, name)
                    val input = activity.contentResolver.openInputStream(uri) ?: error("无法读取 $name")
                    input.use {
                        out.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val n = it.read(buffer)
                                if (n < 0) break
                                total += n
                                require(total <= 512L * 1024 * 1024) { "所选文件总大小超过 512 MB" }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    out
                }
            } catch (e: Throwable) { batch.deleteRecursively(); throw e }
        }
    }

    /** 系统相机拍照：输出到 cache/camera/ 下（经 [RwFileProvider] 授权写入），不需要相机权限。 */
    override suspend fun takePhoto(): File? {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val out = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        val uri = RwFileProvider.uriFor(activity, out)
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = android.content.ClipData.newRawUri("photo", uri)
        // 没装相机应用时直接返回（清单里 <queries> 声明了 IMAGE_CAPTURE，Android 11+ 也查得到）
        if (intent.resolveActivity(activity.packageManager) == null) return null
        val ok = runCatching { startForResult(intent) }.isSuccess
        return out.takeIf { ok && it.isFile && it.length() > 0 }
    }

    override suspend fun exportFile(file: File, suggestedName: String, mimeType: String): Boolean {
        val exportType = mimeType
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(exportType).putExtra(Intent.EXTRA_TITLE, suggestedName)
        val uri = startForResult(intent)?.data ?: return false
        withContext(Dispatchers.IO) {
            activity.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        }
        return true
    }

    private suspend fun startForResult(intent: Intent): Intent? {
        val result = CompletableDeferred<Intent?>()
        ui {
            activity.addResultListener({ code -> activity.startActivityForResult(intent, code) }) { resultCode, data ->
                result.complete(if (resultCode == android.app.Activity.RESULT_OK) data else null)
            }
        }
        return result.await()
    }

    private fun displayName(uri: Uri): String? =
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun ui(block: () -> Unit) = activity.runOnUiThread(block)
}
