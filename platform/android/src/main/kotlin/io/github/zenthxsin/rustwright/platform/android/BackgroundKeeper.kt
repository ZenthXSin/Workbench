package io.github.zenthxsin.rustwright.platform.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.zenthxsin.rustwright.core.runtime.RwLog
import kotlin.math.abs

/**
 * 后台保活：助手回合、生图、装环境这类任务进行中时，切到后台也要继续跑。
 *
 * - **悬浮窗**（主力）：任务进行中、App 不在前台时显示一个小圆悬浮窗。有可见悬浮窗的进程在各家系统里都算「用户看得见」，
 *   不会被冻结或清理；它也让系统允许从后台启动前台服务、点它回到 App。
 * - **前台服务**（[KeepAliveService]）：带通知，任务期间一直挂着；从后台启动被拒时，悬浮窗出现后再补一次。
 * - **唤醒锁**：锁屏后 CPU 不休眠，流式回复和 Linux 里的进程不停。
 *
 * 任务结束后悬浮窗再留 [LINGER_MS]（转圈变成一圈淡绿），自动重试、排队的下一条消息在这段时间里能接着开始。
 * 所有状态只在主线程改。
 */
@SuppressLint("StaticFieldLeak")
object BackgroundKeeper {
    private val main = Handler(Looper.getMainLooper())
    private val active = LinkedHashMap<String, String>()
    private var app: Context? = null
    private var foreground = true
    private var lingerUntil = 0L
    private var lastLabel = ""
    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceOn = false
    private var bubble: Bubble? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** 某来源的任务开始 / 结束（来自 Platform.backgroundWork，任意线程）。 */
    fun update(key: String, on: Boolean, label: String) { main.post {
        val wasBusy = active.isNotEmpty()
        if (on) active[key] = label.ifBlank { "正在后台处理" } else active.remove(key)
        if (wasBusy && active.isEmpty()) {
            lingerUntil = SystemClock.uptimeMillis() + LINGER_MS
            main.postDelayed({ refresh() }, LINGER_MS + 50)
        }
        refresh()
    } }

    /** Activity 可见性变化（onStart / onStop）。 */
    fun setForeground(visible: Boolean) { main.post {
        foreground = visible
        refresh()
    } }

    private fun refresh() {
        val ctx = app ?: return
        val busy = active.isNotEmpty()
        if (busy) lastLabel = active.values.last()
        holdWakeLock(ctx, busy)
        val lingering = !busy && SystemClock.uptimeMillis() < lingerUntil
        val showBubble = !foreground && (busy || lingering) && canDrawOverlays(ctx)
        if (showBubble) {
            (bubble ?: Bubble(ctx).also { bubble = it }).show(busy)
        } else {
            bubble?.hide()
        }
        // 悬浮窗先出来，再启动前台服务：Android 12 起后台启动前台服务只对有可见悬浮窗的 App 放行
        if (busy) startService(ctx, lastLabel) else stopService(ctx)
    }

    private fun startService(ctx: Context, label: String) {
        val intent = Intent(ctx, KeepAliveService::class.java).putExtra(KeepAliveService.EXTRA_LABEL, label)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
            serviceOn = true
        }.onFailure { RwLog.w("keepalive", "前台服务启动失败（悬浮窗出现后会重试）：${it.javaClass.simpleName}") }
    }

    private fun stopService(ctx: Context) {
        if (!serviceOn) return
        serviceOn = false
        ctx.stopService(Intent(ctx, KeepAliveService::class.java))
    }

    private fun holdWakeLock(ctx: Context, on: Boolean) {
        if (on) {
            val lock = wakeLock ?: (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rustwright:task").apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            // 带上限，万一状态没复位也不会一直耗电；任务还在时每次刷新都会续上
            lock.acquire(WAKE_LOCK_MS)
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
    }

    fun canDrawOverlays(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    /**
     * 一个不起眼的小圆：半透明底、中间是 App 图标，干活时边缘一段细弧在转，完成后变成一圈淡绿。
     * 可拖动，松手贴到最近的左右边；点一下回到 App。
     */
    private class Bubble(private val ctx: Context) {
        private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val dp = ctx.resources.displayMetrics.density
        private val size = (40 * dp).toInt()
        private val view = RingView(ctx, runCatching { ctx.packageManager.getApplicationIcon(ctx.packageName) }.getOrNull(), dp)
        private val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ctx.resources.displayMetrics.widthPixels - size - (6 * dp).toInt()
            y = (ctx.resources.displayMetrics.heightPixels * 0.28f).toInt()
        }
        private var shown = false

        init {
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
            view.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (abs(dx) > 8 * dp || abs(dy) > 8 * dp) moved = true
                        if (moved) {
                            params.x = (startX + dx).toInt()
                            params.y = (startY + dy).toInt().coerceAtLeast(0)
                            runCatching { wm.updateViewLayout(view, params) }
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) openApp() else {
                        // 贴边，免得挡住屏幕中间
                        val w = ctx.resources.displayMetrics.widthPixels
                        val margin = (6 * dp).toInt()
                        params.x = if (params.x + size / 2 < w / 2) margin else w - size - margin
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                }
                true
            }
        }

        fun show(busy: Boolean) {
            view.busy = busy
            if (shown) return
            shown = runCatching { wm.addView(view, params); true }.getOrElse {
                RwLog.w("keepalive", "悬浮窗显示失败：${it.message}")
                false
            }
        }

        fun hide() {
            if (!shown) return
            shown = false
            runCatching { wm.removeView(view) }
        }

        private fun openApp() {
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { ctx.startActivity(launch) }
        }
    }

    /** 圆底 + 图标 + 边缘的转圈细弧。 */
    private class RingView(ctx: Context, private val icon: android.graphics.drawable.Drawable?, private val dp: Float) : View(ctx) {
        var busy = true
            set(v) { field = v; invalidate() }
        private val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xB31F1F1D.toInt() }
        private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2 * dp
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val oval = android.graphics.RectF()
        private val clip = android.graphics.Path()
        private val start = SystemClock.uptimeMillis()

        init { alpha = 0.85f }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = minOf(w, h) / 2f
            canvas.drawCircle(w / 2f, h / 2f, r, bg)
            // 细圈贴着外沿，图标铺满圈内只留 1dp 缝：应用图标是自带底色的自适应图标，裁成圆后放大一点让中间的铁砧更大
            val inset = ring.strokeWidth / 2f + 0.5f * dp
            val inner = r - ring.strokeWidth - 1.5f * dp
            icon?.let {
                // 自适应图标的铁砧画在偏上的位置：整体缩小一点、往下挪一点，看起来在圆心
                val half = inner * 1.06f
                val dy = inner * 0.05f
                it.setBounds((w / 2f - half).toInt(), (h / 2f - half + dy).toInt(), (w / 2f + half).toInt(), (h / 2f + half + dy).toInt())
                clip.reset()
                clip.addCircle(w / 2f, h / 2f, inner, android.graphics.Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clip)
                it.draw(canvas)
                canvas.restore()
            }
            oval.set(inset, inset, w - inset, h - inset)
            if (busy) {
                ring.color = 0xFFC2603A.toInt()
                val angle = ((SystemClock.uptimeMillis() - start) % 1100L) / 1100f * 360f
                canvas.drawArc(oval, angle - 90f, 80f, false, ring)
                postInvalidateOnAnimation()
            } else {
                ring.color = 0x996FBF73.toInt()
                canvas.drawArc(oval, 0f, 360f, false, ring)
            }
        }
    }

    private const val LINGER_MS = 60_000L
    private const val WAKE_LOCK_MS = 3 * 60 * 60_000L
}
