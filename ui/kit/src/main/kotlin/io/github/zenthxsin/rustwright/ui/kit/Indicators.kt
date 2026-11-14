package io.github.zenthxsin.rustwright.ui.kit

import arc.graphics.Color
import arc.graphics.g2d.Shapes
import arc.math.Interp
import arc.math.Mathf
import arc.math.Smooth
import arc.scene.Element
import arc.scene.event.Touchable
import arc.scene.ui.Label
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.designsystem.dp

/**
 * 上下文用量环：轨道 + 圆头弧线，弧长 = 用量；[busy] 时整体匀速旋转（助手工作中）。
 */
class ContextRing(private val usage: () -> Float) : Element() {
    // 只显示上下文占用（stroke-dasharray 0.6s ease），不再随运行转圈
    private val shown = Smooth(0f, 0.6f, Interp.ease)

    init {
        touchable = Touchable.disabled
    }

    override fun getPrefWidth() = dp(24f)
    override fun getPrefHeight() = dp(24f)

    override fun act(delta: Float) {
        super.act(delta)
        shown.to(usage().coerceIn(0.03f, 1f)).update(delta)
    }

    override fun draw() {
        val cx = x + width / 2f
        val cy = y + height / 2f
        val r = dp(9f)
        val t = dp(2.4f)
        val a = parentAlpha * color.a
        Shapes.arc(cx, cy, r, t, 0f, 360f, Color(K.ringTrack).a(a))
        Shapes.arc(cx, cy, r, t, 0f, 360f * shown.get(), Color(K.blue).a(a))
    }
}

/**
 * 运行中提示：三个依次闪烁的锈橙小点 + 每几秒轮换的词（思考中…、锻造中…）。
 */
class WorkingIndicator : Table() {
    private val words = listOf("思考中…", "琢磨中…", "锻造中…", "打磨中…", "淬火中…")
    private var time = 0f
    private val label: Label = K.text(word(), 16f, K.accentText, lh = 16f)

    /** 原型：词按墙上时钟每 2.6 秒轮换（Date.now() / 2600），不是从出现时算。 */
    private fun word() = words[((System.currentTimeMillis() / 2600) % words.size).toInt()]

    /** 重新挂载：小点的闪烁从头开始（animation-delay 0 / 0.2 / 0.4s）。 */
    fun restart() {
        time = 0f
        label.setText(word())
    }
    private val dots = object : Element() {
        override fun getPrefWidth() = dp(23f)
        override fun getPrefHeight() = dp(16f)
        override fun draw() {
            for (i in 0 until 3) {
                val a = pulse(time - i * 0.2f, 1.2f)
                Shapes.circle(x + dp(2.5f) + i * dp(9f), y + height / 2f, dp(2.5f), Color(K.accentText).a(a * parentAlpha * color.a))
            }
        }
    }

    init {
        touchable = Touchable.disabled
        add(dots).padRight(dp(10f))
        add(label)
    }

    override fun act(delta: Float) {
        super.act(delta)
        time += delta
        val w = word()
        // 原型：换词时 rwin 0.3s ease
        if (label.text.toString() != w) { label.setText(w); label.rwin(0.3f) }
    }
}

/** 加载转圈（锈橙弧线在灰色轨道上旋转）。 */
class Spinner(private val px: Float = 20f, private val color2: Color = K.accent) : Element() {
    private var angle = 0f

    init {
        touchable = Touchable.disabled
    }

    override fun getPrefWidth() = dp(px)
    override fun getPrefHeight() = dp(px)
    override fun act(delta: Float) {
        super.act(delta)
        angle = (angle + delta * 400f) % 360f
    }

    override fun draw() {
        val r = width / 2f - dp(1.5f)
        val a = parentAlpha * color.a
        Shapes.arc(x + width / 2f, y + height / 2f, r, dp(2.2f), 0f, 360f, Color(K.inputBorder).a(a))
        Shapes.arc(x + width / 2f, y + height / 2f, r, dp(2.2f), angle, 90f, Color(color2).a(a))
    }
}


/**
 * 原型 @keyframes rwpulse { 0%,100% { opacity:.35 } 50% { opacity:1 } }，timing ease 作用在每一段上；
 * [t] 为动画已运行时间（负数表示还在 animation-delay 里，保持不透明）。
 */
fun pulse(t: Float, period: Float): Float {
    if (t < 0f) return 1f
    val ph = (t % period) / period
    return if (ph < 0.5f) 0.35f + 0.65f * Interp.ease.apply(ph * 2f) else 1f - 0.65f * Interp.ease.apply((ph - 0.5f) * 2f)
}

/** 运行中的小标记：空心细圆圈，一段弧沿边缘匀速转（列表行最右边用，不抢眼）。 */
class SpinRing(private val px: Float = 16f, private val tint: Color = K.subtle) : Element() {
    private var angle = 0f

    init {
        touchable = Touchable.disabled
    }

    override fun getPrefWidth() = dp(px)
    override fun getPrefHeight() = dp(px)

    override fun act(delta: Float) {
        super.act(delta)
        angle = (angle - 300f * delta) % 360f
    }

    override fun draw() {
        val a = parentAlpha * color.a
        val r = minOf(width, height) / 2f - dp(1f)
        val t = dp(1.6f)
        Shapes.arc(x + width / 2f, y + height / 2f, r, t, 0f, 360f, Color(tint).a(a * 0.25f))
        Shapes.arc(x + width / 2f, y + height / 2f, r, t, angle, 100f, Color(tint).a(a))
    }
}
