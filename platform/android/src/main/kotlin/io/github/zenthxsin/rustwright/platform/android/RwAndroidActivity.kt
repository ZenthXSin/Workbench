package io.github.zenthxsin.rustwright.platform.android

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.widget.FrameLayout
import arc.backend.android.AndroidApplication
import arc.backend.android.AndroidApplicationConfiguration
import arc.scene.ui.TextField
import io.github.zenthxsin.rustwright.core.runtime.RwLog
import io.github.zenthxsin.rustwright.ui.designsystem.FontFile
import io.github.zenthxsin.rustwright.ui.designsystem.RwArcApp

/**
 * 所有 Rustwright Android 入口的基类：Arc GL 视图 + 内联输入法桥 + 系统字体 + 边到边 insets。
 * 子类只需提供 [createApp]。
 */
abstract class RwAndroidActivity : AndroidApplication() {
    private var arcApp: RwArcApp? = null
    private var textBridge: AndroidTextInputBridge? = null
    private var backCallback: android.window.OnBackInvokedCallback? = null

    private fun routeBack() {
        if (textBridge?.dismissKeyboardForBack() == true) return
        val app = arcApp ?: return
        arc.Core.app?.post { app.requestBack() }
    }

    // Pre-33 gestures and physical keys must not reach Activity's default finish().
    @Deprecated("Legacy Android back callback")
    override fun onBackPressed() = routeBack()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) routeBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    lateinit var platform: AndroidPlatform
        private set
    lateinit var linux: ProotLinuxEnvironment
        private set
    lateinit var secrets: KeystoreSecretStore
        private set

    protected abstract fun createApp(fonts: List<FontFile>): RwArcApp

    override fun onStart() {
        super.onStart()
        BackgroundKeeper.setForeground(true)
    }

    override fun onStop() {
        BackgroundKeeper.setForeground(false)
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        // Arc 的 pause 回调本身仍在 GL 线程里；等 GLSurfaceView 停帧后才能交出可变状态。
        if (::platform.isInitialized && !isFinishing) platform.pauseTasks()
    }

    override fun onResume() {
        if (::platform.isInitialized) platform.resumeTasks()
        super.onResume()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        backCallback = null
        arcApp = null
        textBridge = null
        if (::linux.isInitialized) linux.close()
        if (::platform.isInitialized) platform.closeTasks()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 文字跟随系统字体大小（和其他 App 的 sp 一样）；要在 Arc 生成字体之前设置
        arc.scene.ui.layout.Scl.setFontScale(resources.configuration.fontScale)
        // 上次崩溃过：先显示报告（纯 Android 页面，不进 Arc）
        if (CrashReporter.pending(this) != null) {
            super.onCreate(savedInstanceState)
            startActivity(android.content.Intent(this, CrashActivity::class.java))
            finish()
            return
        }
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)

        RwLog.sink = RwLog.Sink { level, tag, message, error ->
            val text = RwLog.redact(message)
            CrashReporter.recent("${level.name[0]}/$tag: $text" + (error?.let { " ${it::class.java.simpleName}: ${it.message}" } ?: ""))
            when (level) {
                RwLog.Level.DEBUG -> Log.d("RW/$tag", text, error)
                RwLog.Level.INFO -> Log.i("RW/$tag", text, error)
                RwLog.Level.WARN -> Log.w("RW/$tag", text, error)
                RwLog.Level.ERROR -> Log.e("RW/$tag", text, error)
            }
        }
        // Arc 自己的日志也收进崩溃报告的「最近日志」
        val arcLogger = arc.util.Log.logger
        arc.util.Log.logger = arc.util.Log.LogHandler { level, text ->
            CrashReporter.recent("arc/${level.name}: ${text.take(500)}")
            arcLogger.log(level, text)
        }
        RwLog.i("startup", "onCreate · ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}")
        BackgroundKeeper.init(this)
        platform = AndroidPlatform(this)
        linux = ProotLinuxEnvironment(this)
        secrets = KeystoreSecretStore(this)

        val container = FrameLayout(this)
        val bridge = AndroidTextInputBridge(this, container)
        textBridge = bridge
        // 必须在任何 TextField 创建前设置，否则移动端会退回原生输入对话框
        TextField.inputBridge = bridge

        val fonts = SystemFontLocator.candidates()
        RwLog.i("startup", "font candidates: " + fonts.joinToString { "${it.path}#${it.index}" })
        val app = createApp(fonts)
        arcApp = app
        val config = AndroidApplicationConfiguration().apply {
            useImmersiveMode = false
            hideStatusBar = false
            a = 8
            stencil = 8
            disableAudio = true // 不用声音，少一处原生初始化
        }
        container.addView(initializeForView(app, config), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        bridge.install()
        setContentView(container)
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = android.window.OnBackInvokedCallback { routeBack() }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
        RwLog.i("startup", "content view ready")

        container.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                bridge.keyboardVisible = insets.isVisible(WindowInsets.Type.ime())
                app.setInsets(bars.left.toFloat(), bars.top.toFloat(), bars.right.toFloat(), maxOf(bars.bottom, ime.bottom).toFloat())
            } else {
                @Suppress("DEPRECATION")
                val keyboardHeight = insets.systemWindowInsetBottom - insets.stableInsetBottom
                bridge.keyboardVisible = keyboardHeight > 80 * resources.displayMetrics.density
                @Suppress("DEPRECATION")
                app.setInsets(
                    insets.systemWindowInsetLeft.toFloat(), insets.systemWindowInsetTop.toFloat(),
                    insets.systemWindowInsetRight.toFloat(), insets.systemWindowInsetBottom.toFloat(),
                )
            }
            insets
        }
    }
}
