package io.github.zenthxsin.rustwright.fused

import arc.graphics.Color
import arc.graphics.g2d.Shapes
import arc.math.Interp
import arc.scene.Element
import arc.scene.event.Touchable
import arc.scene.ui.layout.Table
import arc.scene.ui.layout.WidgetGroup
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.AnvilLogo
import io.github.zenthxsin.rustwright.ui.kit.K

/**
 * 启动动画：铁砧标志淡入放大、品牌字、一条细进度条。
 * 动画期间逐帧跑预加载任务（每帧最多占约 10ms，动画不掉帧），进度条下面一行小字写正在准备什么。
 * 全部跑完且至少展示 [MIN_SHOW] 秒后（最多 [MAX_SHOW] 秒，剩下的交给 [onLeftover]），
 * 标志轻微放大淡出，[onDone] 让主页内容再入场。期间挡住所有触摸。
 */
internal class SplashView(
    private val tasks: List<Pair<String, () -> Unit>>,
    /** 到 [MAX_SHOW] 还没做完的任务交回去，空闲时再做。 */
    private val onLeftover: (List<() -> Unit>) -> Unit,
    private val onDone: () -> Unit,
) : WidgetGroup() {
    private val logo = AnvilLogo(68f)
    private val title = K.serif(io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.name, 26f, K.text, 600, 30f)
    private val brand = Table().apply {
        add(logo).size(dp(68f)).row()
        add(title).padTop(dp(16f))
        isTransform = true
    }
    private val bar = object : Element() {
        override fun draw() {
            val a = parentAlpha * color.a
            Shapes.rect(x, y, width, height, height / 2f, Color(K.line).a(a))
            val w = width * shown
            if (w > 0.5f) Shapes.rect(x, y, w.coerceAtLeast(height), height, height / 2f, Color(K.accent).a(a))
        }
    }
    private val status = K.text("", 12f, K.subtle, lh = 12f)
    private var index = 0
    private var time = 0f
    private var outT = -1f
    /** 进度条显示值（追着真实进度走，不跳）。 */
    private var shown = 0f
    private var firstFrame = true
    /** 真实经过时间的起点：动画时间按帧封顶，一帧卡 0.8 秒也只算 1/30 秒，不能拿来限时。 */
    private val bornAt = System.nanoTime()
    private fun wall() = (System.nanoTime() - bornAt) / 1e9f

    init {
        setFillParent(true)
        touchable = Touchable.enabled
        addChild(brand)
        addChild(bar)
        addChild(status)
        status.color.a = 0f
        brand.color.a = 0f
        bar.color.a = 0f
    }

    override fun act(delta: Float) {
        super.act(delta)
        val d = minOf(delta, 1f / 30f)
        time += d
        // 第一帧先把启动画面画出来，再开始干活
        if (firstFrame) firstFrame = false
        else if (index < tasks.size && outT < 0f) {
            if (wall() >= MAX_SHOW) {
                // 到点了：剩下的交回去空闲时做，进度直接走满
                onLeftover(tasks.subList(index, tasks.size).map { it.second })
                index = tasks.size
            } else {
                val start = System.nanoTime()
                do {
                    val t0 = System.nanoTime()
                    tasks[index++].second()
                    if (System.getProperty("rw.navperf") != null) arc.util.Log.info("[splashtask] ${tasks[index - 1].first} ${(System.nanoTime() - t0) / 1_000_000}ms")
                } while (index < tasks.size && System.nanoTime() - start < BUDGET_NS && wall() < MAX_SHOW)
            }
        }
        val label = tasks.getOrNull(index)?.first?.let { "正在准备$it…" } ?: "准备好了"
        if (status.text.toString() != label) status.setText(label)
        val progress = if (tasks.isEmpty()) 1f else index / tasks.size.toFloat()
        shown += (progress - shown) * (1f - Math.exp(-12.0 * d).toFloat())
        if (progress >= 1f && progress - shown < 0.01f) shown = 1f

        // 入场：0.45s 淡入 + 0.92→1 放大；进度条晚 0.15s 出现
        val inP = Interp.pow3Out.apply((time / 0.45f).coerceIn(0f, 1f))
        var alpha = inP
        var scale = 0.92f + 0.08f * inP
        bar.color.a = Interp.smooth.apply(((time - 0.15f) / 0.3f).coerceIn(0f, 1f))
        status.color.a = bar.color.a

        if (outT < 0f && index >= tasks.size && shown >= 1f && time >= MIN_SHOW) {
            outT = 0f
            onDone()
        }
        if (outT >= 0f) {
            outT += d
            val o = Interp.pow2In.apply((outT / OUT).coerceIn(0f, 1f))
            alpha *= 1f - o
            scale += 0.06f * o
            color.a = 1f - Interp.smooth.apply((outT / OUT).coerceIn(0f, 1f))
            if (outT >= OUT) { remove(); return }
        }
        brand.color.a = alpha
        brand.setScale(scale)
        invalidate()
    }

    override fun layout() {
        brand.pack()
        brand.setOrigin(brand.width / 2f, brand.height / 2f)
        brand.setPosition((width - brand.width) / 2f, height * 0.52f - brand.height / 2f)
        val bw = dp(120f)
        bar.setBounds((width - bw) / 2f, brand.y - dp(34f), bw, dp(3f))
        status.pack()
        status.setPosition((width - status.width) / 2f, bar.y - dp(14f) - status.height)
    }

    override fun draw() {
        Shapes.rect(x, y, width, height, 0f, Color(K.bg).a(color.a * parentAlpha))
        super.draw()
    }

    override fun hit(x: Float, y: Float, touchable: Boolean): Element? = if (outT >= 0f) null else this

    companion object {
        /** 至少展示多久（秒），太快一闪而过反而像出错。 */
        const val MIN_SHOW = 0.9f
        const val OUT = 0.35f
        /** 最多展示多久（秒）：手机上全部预加载可能要好几秒，到点就进主界面，剩下的后台做。 */
        const val MAX_SHOW = 2.5f
        const val BUDGET_NS = 10_000_000L
    }
}
