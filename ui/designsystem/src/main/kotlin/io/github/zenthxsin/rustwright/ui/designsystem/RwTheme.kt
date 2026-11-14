package io.github.zenthxsin.rustwright.ui.designsystem

import arc.Core
import arc.freetype.FreeTypeFontGenerator
import arc.freetype.FreeTypeFontGenerator.FreeTypeFontParameter
import arc.graphics.Color
import arc.graphics.Pixmap
import arc.graphics.Texture
import arc.graphics.g2d.Font
import arc.graphics.g2d.NinePatch
import arc.graphics.g2d.PixmapPacker
import arc.graphics.g2d.TextureRegion
import arc.scene.style.Drawable
import arc.scene.style.NinePatchDrawable
import arc.scene.style.TextureRegionDrawable
import arc.scene.ui.Button
import arc.scene.ui.Label
import arc.scene.ui.ScrollPane
import arc.scene.ui.TextButton
import arc.scene.ui.TextField
import arc.scene.ui.layout.Scl
import arc.util.Log
import kotlin.math.sqrt

/**
 * Rustwright 设计系统：颜色、字体、代码生成的圆角块，以及注册到 Scene 的默认控件样式。
 * 必须在 Arc 初始化后（有 GL 上下文、Core.scene 已创建）调用 [load]。
 */
object RwTheme {
    /** 主题模式：跟随系统 / 亮色 / 暗色。 */
    enum class Mode(val label: String) { SYSTEM("跟随系统"), LIGHT("亮色"), DARK("暗色") }

    /**
     * 颜色令牌。对象本身不变、切换主题时原地改值，所以直接引用 `Colors.xxx` 的绘制代码会跟着变；
     * 用颜色构造的样式和页面由 [applyMode] 之后的重建刷新。
     * 取值来自在线 Codex 工作台（ZCode 主题）：中性灰阶，主操作亮色为黑、暗色为白，信息色只用于链接与提示。
     */
    object Colors {
        /** 页面底色。 */
        val bg = Color()
        /** 面板 / 卡片 / 顶栏。 */
        val surface = Color()
        /** 浅填充（输入区、悬停、代码块外框）。 */
        val surface2 = Color()
        /** 按下 / 选中。 */
        val surface3 = Color()
        val outline = Color()
        /** 聚焦描边。 */
        val outlineStrong = Color()
        /** 主操作底色（亮色黑、暗色白）。 */
        val accent = Color()
        /** 选中态浅底（中性）。 */
        val accentSoft = Color()
        /** 选中态文字。 */
        val accentText = Color()
        val onAccent = Color()
        val text = Color()
        val muted = Color()
        /** 更弱的说明 / 占位。 */
        val subtle = Color()
        /** 信息色（链接、提问卡片）。 */
        val info = Color()
        val infoSoft = Color()
        val danger = Color()
        val success = Color()
        val successSoft = Color()
        val warning = Color()
        val warningSoft = Color()
        val codeBg = Color()
        val codeText = Color()
        /** 输入区底色（暗色下比页面略亮）。 */
        val input = Color()
        /** 用户消息气泡。 */
        val userBubble = Color()
        /** 弹出菜单。 */
        val popover = Color()
        /** 推理强度、停止按钮的蓝色。 */
        val blue = Color()
        /** 侧栏渐变的两端（网页 .chatgpt-sidebar 的 160° 渐变）。 */
        val sidebarFrom = Color()
        val sidebarTo = Color()
        /** 完全访问模式的橙色。 */
        val orange = Color()
        val diffAdded = Color()
        val diffRemoved = Color()

        /** 图标底与前景（入口、文件类型），5 组；工作台风格下都是中性色。 */
        val badgeBg = List(5) { Color() }
        val badgeFg = List(5) { Color() }

        internal fun apply(dark: Boolean) {
            fun c(target: Color, light: String, darkHex: String) = target.set(Color.valueOf(if (dark) darkHex else light))
            // 取自在线 Codex 工作台实际使用的 codex-chatgpt 主题（web/src/features/codex/chatgpt.css）
            c(bg, "ffffff", "212121")
            c(surface, "ffffff", "212121")
            c(surface2, "ededee", "2f2f2f")
            c(surface3, "e9e9eb", "303030")
            c(outline, "e5e5e5", "3b3b3b")
            c(outlineStrong, "a6a6a6", "6b6b6b")
            c(accent, "0d0d0d", "ececec")
            c(accentSoft, "e9e9eb", "303030")
            c(accentText, "0d0d0d", "ececec")
            c(onAccent, "ffffff", "171717")
            c(text, "0d0d0d", "ececec")
            c(muted, "666666", "b4b4b4")
            c(subtle, "737373", "9b9b9b")
            c(info, "0066dd", "80beff")
            c(infoSoft, "ebf4ff", "0d2440")
            c(danger, "e03131", "ff6b6b")
            c(success, "1e8a3e", "46bf72")
            c(successSoft, "eaf7ee", "173322")
            c(warning, "c46a00", "f0a050")
            c(warningSoft, "fff4eb", "3a2612")
            c(codeBg, "f9f9f9", "171717")
            c(codeText, "0d0d0d", "ececec")
            c(input, "ffffff", "292929")
            c(userBubble, "f1f1f1", "303030")
            c(popover, "ffffff", "2f2f2f")
            c(blue, "3b82f6", "3b82f6")
            c(sidebarFrom, "eff3f3", "2a2e2d")
            c(sidebarTo, "f2f2f6", "28282c")
            c(orange, "e86514", "fb923c")
            c(diffAdded, "1e8a3e", "46bf72")
            c(diffRemoved, "e03131", "ff5c5c")
            for (i in 0 until 5) {
                badgeBg[i].set(surface2)
                badgeFg[i].set(text)
            }
        }
    }

    var mode = Mode.SYSTEM
        private set

    /** 其他颜色表（如融合版的 K）在深浅切换时跟着原地改值。 */
    val paletteListeners = ArrayList<(dark: Boolean) -> Unit>()
    var dark = true
        private set

    /** 设定主题模式并刷新颜色与样式（[systemDark] 为系统当前深浅）。调用方随后重建界面。 */
    fun applyMode(mode: Mode, systemDark: Boolean) {
        this.mode = mode
        dark = when (mode) { Mode.SYSTEM -> systemDark; Mode.LIGHT -> false; Mode.DARK -> true }
        Colors.apply(dark)
        paletteListeners.forEach { it(dark) }
        if (this::font.isInitialized) buildStyles()
    }

    /** 间距 / 尺寸（dp，使用时经 Scl.scl）。 */
    object Dimens {
        const val gutter = 12f
        const val gap = 6f
        const val control = 32f
        const val topBar = 44f
        const val radius = 8
    }

    /** 正文 14dp。 */
    lateinit var font: Font
        private set
    /** 标题 16dp。 */
    lateinit var title: Font
        private set
    /** 说明文字 12dp。 */
    lateinit var small: Font
        private set
    /** 13dp（工作台的模式胶囊等）。 */
    lateinit var medium: Font
        private set
    /** 16dp 正文（输入区，与网页 16px 一致；和 [title] 同字号但单独一份，避免样式互相影响）。 */
    lateinit var large: Font
        private set
    /** Material Icons 图标字体（18dp / 14dp）；字体缺失时退回正文字体。 */
    lateinit var icons: Font
        private set
    lateinit var iconsSmall: Font
        private set
    /** 24dp / 16dp 图标（输入区的加号、发送箭头）。 */
    lateinit var iconsLarge: Font
        private set
    lateinit var iconsMedium: Font
        private set
    lateinit var white: TextureRegion
        private set
    var fontSource = "（无）"
        private set

    private val roundCache = HashMap<Int, NinePatch>()
    private val outlineCache = HashMap<Int, NinePatch>()

    private var monoFont: Font? = null
    private var fontSizePx = 0

    /** 等宽字体（终端用），首次使用时加载；系统没有等宽字体时退回正文字体。 */
    val mono: Font
        get() = monoFont ?: runCatching { loadFonts(MONO_CANDIDATES, fontSizePx).first() }.getOrElse { font }.also { monoFont = it }

    private val MONO_CANDIDATES = listOf(
        FontFile("/system/fonts/DroidSansMono.ttf"),
        FontFile("/system/fonts/CutiveMono.ttf"),
        FontFile("/system/fonts/RobotoMono-Regular.ttf"),
        FontFile("/system/fonts/SourceCodePro-Regular.ttf"),
        FontFile("/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"),
        FontFile("C:/Windows/Fonts/consola.ttf"),
    )

    /** 按钮、标签等预置样式。 */
    object Styles {
        lateinit var primary: TextButton.TextButtonStyle
        lateinit var secondary: TextButton.TextButtonStyle
        lateinit var ghost: TextButton.TextButtonStyle
        lateinit var danger: TextButton.TextButtonStyle
        /** 可切换的胶囊（筛选 / 参数）。 */
        lateinit var chip: TextButton.TextButtonStyle
        /** 图标按钮（圆形，按下 / 选中时显示浅底）。 */
        lateinit var icon: TextButton.TextButtonStyle
        /** 实心强调色圆形图标按钮（发送等）。 */
        lateinit var iconPrimary: TextButton.TextButtonStyle
        /** 分段控件里的一段（选中时浮起一块底色）。 */
        lateinit var segment: TextButton.TextButtonStyle
        /** 无背景输入框（放在自绘卡片里，如聊天输入栏）。 */
        lateinit var plainField: TextField.TextFieldStyle
        /** 底部导航项。 */
        lateinit var nav: TextButton.TextButtonStyle
        lateinit var title: Label.LabelStyle
        lateinit var caption: Label.LabelStyle
        lateinit var section: Label.LabelStyle
    }

    fun load(candidates: List<FontFile>, fontSizeDp: Float = 14f) {
        Colors.apply(dark)
        white = Core.atlas.white()
        fontSizePx = Scl.scl(fontSizeDp).toInt()
        // 优先用随包的 Noto Sans SC 可变字体（真字重）；加载失败再退回系统字体
        vfGen = runCatching { FreeTypeFontGenerator(Core.files.absolute(resourceFile("/rw-fonts/NotoSansSC-VF.otf", "rw-noto-sans-sc-vf-2.004.otf").path)) }
            .onFailure { Log.err("[RwTheme] bundled font failed", it) }.getOrNull()
        val texts = vfGen?.let { g ->
            fontSource = "bundled:NotoSansSC-VF"
            listOf(fontSizePx, Scl.scl(16f).toInt(), Scl.scl(12f).toInt(), Scl.scl(13f).toInt()).map { generate(g, it, weight = 400f) }
        } ?: loadFonts(candidates, fontSizePx, Scl.scl(16f).toInt(), Scl.scl(12f).toInt(), Scl.scl(13f).toInt())
        font = texts[0]; title = texts[1]; small = texts[2]; medium = texts[3]; large = texts[1]
        textGen = vfGen ?: lastGen
        val iconFonts = runCatching { loadIconFonts(Scl.scl(18f).toInt(), Scl.scl(14f).toInt(), Scl.scl(24f).toInt(), Scl.scl(16f).toInt()) }
            .onFailure { Log.err("[RwTheme] icon font failed", it) }
            .getOrElse { listOf(font, small, title, font) }
        icons = iconFonts[0]; iconsSmall = iconFonts[1]; iconsLarge = iconFonts[2]; iconsMedium = iconFonts[3]
        buildStyles()
    }

    /** 按当前颜色生成全部样式并注册为场景默认样式。 */
    private fun buildStyles() {
        val r = Dimens.radius
        fun textStyle(up: Drawable?, down: Drawable?, color: Color, checked: Drawable? = null, f: Font = font) =
            TextButton.TextButtonStyle().apply {
                font = f
                fontColor = color
                this.up = up
                this.down = down
                this.checked = checked
                disabledFontColor = Colors.muted.cpy().a(0.6f)
            }
        val pressed = Colors.accent.cpy().lerp(Colors.surface, 0.18f)
        Styles.primary = textStyle(round(r, Colors.accent), round(r, pressed), Colors.onAccent).apply {
            disabled = round(r, Colors.surface3)
        }
        Styles.secondary = textStyle(card(Colors.surface, Colors.outline, r), card(Colors.surface2, Colors.outline, r), Colors.text,
            checked = card(Colors.surface3, Colors.outlineStrong, r)).apply { checkedFontColor = Colors.accentText }
        Styles.ghost = textStyle(null, round(r, Colors.surface2), Colors.text)
        Styles.danger = textStyle(card(Colors.surface, Colors.outline, r), card(Colors.surface2, Colors.outline, r), Colors.danger)
        Styles.chip = textStyle(card(Colors.surface, Colors.outline, r), round(r, Colors.surface3), Colors.text,
            checked = card(Colors.surface3, Colors.outlineStrong, r), f = small).apply { checkedFontColor = Colors.accentText }
        Styles.icon = textStyle(null, round(r, Colors.surface3), Colors.text, checked = round(r, Colors.surface3), f = icons).apply {
            checkedFontColor = Colors.accentText
        }
        Styles.iconPrimary = textStyle(round(16, Colors.accent), round(16, pressed), Colors.onAccent, f = icons).apply {
            disabled = round(16, Colors.surface3)
            disabledFontColor = Colors.subtle
        }
        Styles.segment = textStyle(null, null, Colors.muted, checked = card(Colors.surface, Colors.outline, 6), f = small).apply {
            checkedFontColor = Colors.text
        }
        Styles.plainField = TextField.TextFieldStyle().apply {
            font = this@RwTheme.font
            fontColor = Colors.text
            messageFontColor = Colors.subtle
            cursor = solid(Colors.text).also { it.minWidth = Scl.scl(2f) }
            selection = solid(Colors.info.cpy().a(0.3f))
        }
        Styles.nav = textStyle(null, null, Colors.muted, f = small).apply { checkedFontColor = Colors.accentText }
        Styles.title = Label.LabelStyle(title, Colors.text)
        Styles.caption = Label.LabelStyle(small, Colors.muted)
        Styles.section = Label.LabelStyle(small, Colors.subtle)

        val scene = Core.scene
        scene.addStyle(Label.LabelStyle::class.java, Label.LabelStyle().apply { font = this@RwTheme.font; fontColor = Colors.text })
        scene.addStyle(Button.ButtonStyle::class.java, Button.ButtonStyle().apply { up = round(r, Colors.surface2); down = round(r, Colors.surface3) })
        scene.addStyle(TextButton.TextButtonStyle::class.java, Styles.secondary)
        scene.addStyle(TextField.TextFieldStyle::class.java, TextField.TextFieldStyle().apply {
            font = this@RwTheme.font
            fontColor = Colors.text
            messageFontColor = Colors.subtle
            // 输入框文字和光标离边框留 12dp，不要贴边
            background = card(Colors.surface, Colors.outline, r).apply { leftWidth = Scl.scl(12f); rightWidth = Scl.scl(12f) }
            focusedBackground = card(Colors.surface, Colors.outlineStrong, r).apply { leftWidth = Scl.scl(12f); rightWidth = Scl.scl(12f) }
            cursor = solid(Colors.text).also { it.minWidth = Scl.scl(2f) }
            selection = solid(Colors.info.cpy().a(0.3f))
        })
        scene.addStyle(arc.scene.ui.Dialog.DialogStyle::class.java, arc.scene.ui.Dialog.DialogStyle().apply {
            background = card(Colors.surface, Colors.outline, 12)
            titleFont = this@RwTheme.title
            titleFontColor = Colors.text
            stageBackground = solid(Color(0f, 0f, 0f, if (dark) 0.6f else 0.35f))
        })
        scene.addStyle(ScrollPane.ScrollPaneStyle::class.java, ScrollPane.ScrollPaneStyle().apply {
            vScrollKnob = null
            hScrollKnob = round(2, Colors.muted.cpy().a(0.45f)).also { it.minHeight = Scl.scl(3f) }
        })
    }

    /** 图标字体随包放在 classpath（rw-icons/），复制到临时目录后交给 FreeType。 */
    private fun loadIconFonts(vararg sizes: Int): List<Font> {
        val res = "/rw-icons/MaterialIcons-Regular.ttf"
        val file = java.io.File(System.getProperty("java.io.tmpdir") ?: ".", "rw-icons-1.ttf")
        if (!file.isFile) {
            val bytes = io.github.zenthxsin.rustwright.core.runtime.Res.bytes(res) ?: error("缺少 $res")
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.path + ".part")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        }
        val gen = FreeTypeFontGenerator(Core.files.absolute(file.path))
        return sizes.map { generate(gen, it) }
    }

    private var textGen: FreeTypeFontGenerator? = null
    private var vfGen: FreeTypeFontGenerator? = null
    private var monoGen: FreeTypeFontGenerator? = null
    private var lastGen: FreeTypeFontGenerator? = null
    private var lucideGen: FreeTypeFontGenerator? = null
    private val fontCache = HashMap<String, Font>()

    /**
     * 按网页的 CSS 取字体：[px] 字号、[weight] 字重、[lineHeight] 行高（px，可空）。
     * 随包可变字体直接按字重生成；退回系统字体时 ≥500 用描边加粗近似。同一组参数只生成一次。
     */
    fun text(px: Float, weight: Int = 400, lineHeight: Float? = null): Font {
        val key = "t$px/$weight/$lineHeight"
        fontCache[key]?.let { return it }
        val gen = textGen ?: return font
        val size = Scl.sp(px).toInt()
        val base = shared("T$size/$weight") {
            if (vfGen != null) generate(gen, size, weight = weight.toFloat())
            else generate(gen, size, when { weight >= 600 -> Scl.scl(0.38f); weight >= 500 -> Scl.scl(0.12f); else -> 0f })
        }
        return variant(base, lineHeight, markup = false).also { fontCache[key] = it }
    }

    /**
     * 真正生成的字体按「像素字号 + 字重」各一份（[make]），同一字号的不同行高 / 颜色标记只是共享字形和图集页的轻量副本：
     * 生成一份 FreeType 字体要十几到几十毫秒、外加一张 1024×1024 的图集页，界面里几十种「字号 + 行高」组合
     * 若各生成一份，每出现一种新样式就卡一下，还白占显存。
     */
    private val baseFonts = HashMap<String, Font>()
    private fun shared(key: String, make: () -> Font): Font = baseFonts.getOrPut(key, make)
    private fun variant(base: Font, lineHeight: Float?, markup: Boolean): Font {
        if (lineHeight == null && !markup) return base
        val lh = lineHeight?.let { Scl.sp(it) }
        val f = base.withLineHeight(lh ?: base.data.lineHeight, lh ?: base.data.cssLineHeight)
        f.data.markupEnabled = markup
        return f
    }

    /** 与 [text] 相同但开启颜色标记（`[#RRGGBB]…[]`），单独一份实例，不影响普通文字里的方括号。 */
    fun richText(px: Float, weight: Int = 400, lineHeight: Float? = null): Font {
        val key = "r$px/$weight/$lineHeight"
        fontCache[key]?.let { return it }
        if (textGen == null) return font
        val base = text(px, weight, null)
        return variant(base, lineHeight, markup = true).also { fontCache[key] = it }
    }

    private var serifGen: FreeTypeFontGenerator? = null

    /**
     * 品牌衬线体 Newsreader（原型里的「Rustwright」、问候语里的名字），只含拉丁字形；
     * 光学尺寸跟随字号，与浏览器 font-optical-sizing: auto 一致。
     */
    fun serif(px: Float, weight: Int = 600, lineHeight: Float? = null): Font {
        val key = "s$px/$weight/$lineHeight"
        fontCache[key]?.let { return it }
        val gen = serifGen ?: runCatching { FreeTypeFontGenerator(Core.files.absolute(resourceFile("/rw-fonts/Newsreader-VF.ttf", "rw-newsreader-vf.ttf").path)) }
            .onFailure { Log.err("[RwTheme] newsreader failed", it) }.getOrNull()?.also { serifGen = it } ?: return text(px, weight, lineHeight)
        val f = generate(gen, Scl.sp(px).toInt(), weight = weight.toFloat(), opsz = px)
        lineHeight?.let { f.data.lineHeight = Scl.sp(it); f.data.cssLineHeight = Scl.sp(it) }
        fontCache[key] = f
        return f
    }

    /** 开启颜色标记的等宽字体（语法高亮的代码块）。 */
    fun richMono(px: Float, lineHeight: Float? = null): Font {
        val key = "rm$px/$lineHeight"
        fontCache[key]?.let { return it }
        val base = monoAt(px, null)
        return variant(base, lineHeight, markup = true).also { fontCache[key] = it }
    }

    /**
     * 编辑器缩放预览用的等宽字（单独一份，不和 [monoAt] 共用，编辑器会改它的缩放）。
     */
    fun monoMaster(px: Float): Font {
        val key = "mm$px"
        fontCache[key]?.let { return it }
        val gen = monoGen ?: runCatching { loadFonts(MONO_CANDIDATES, Scl.sp(px).toInt()); lastGen }.getOrNull()?.also { monoGen = it } ?: return monoAt(px, px * 20f / 13f)
        val f = generate(gen, Scl.sp(px).toInt(), weight = 400f, fallback = (vfGen ?: textGen).takeIf { it !== gen })
        fontCache[key] = f
        return f
    }

    /** 等宽字体的指定字号（代码、命令）。 */
    fun monoAt(px: Float, lineHeight: Float? = null): Font {
        val key = "m$px/$lineHeight"
        fontCache[key]?.let { return it }
        val gen = monoGen ?: runCatching { loadFonts(MONO_CANDIDATES, Scl.sp(px).toInt()); lastGen }.getOrNull()?.also { monoGen = it } ?: return text(px, 400, lineHeight)
        val size = Scl.sp(px).toInt()
        // 等宽字体没有中文：缺字时用正文字体渲染进同一个图集
        val base = shared("M$size") { generate(gen, size, weight = 400f, fallback = (vfGen ?: textGen).takeIf { it !== gen }) }
        return variant(base, lineHeight, markup = false).also { fontCache[key] = it }
    }

    /**
     * Lucide 图标字体（与网页 lucide-react 同一版本），[px] 为图标边长，[stroke] 为 24 视框下的线宽（字体本身是 2，
     * 其他线宽靠 FreeType 把轮廓收细 / 加粗得到，与原型 svg 的 stroke-width 对应）。
     */
    fun lucide(px: Float, stroke: Float = 2f): Font {
        val key = "l$px/$stroke"
        fontCache[key]?.let { return it }
        val gen = lucideGen ?: runCatching { FreeTypeFontGenerator(Core.files.absolute(resourceFile("/rw-icons/lucide.ttf", "rw-lucide-1.25.ttf").path)) }
            .onFailure { Log.err("[RwTheme] lucide failed", it) }.getOrNull()?.also { lucideGen = it } ?: return icons
        val size = Scl.scl(px).toInt()
        return generate(gen, size, outline = (stroke - 2f) / 24f * size).also { fontCache[key] = it }
    }

    private fun resourceFile(res: String, name: String): java.io.File {
        val file = java.io.File(System.getProperty("java.io.tmpdir") ?: ".", name)
        if (!file.isFile) {
            val bytes = io.github.zenthxsin.rustwright.core.runtime.Res.bytes(res) ?: error("缺少 $res")
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.path + ".part")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        }
        return file
    }

    /** 逐个尝试候选字体，第一个能被 FreeType 加载的胜出；同一个生成器按需生成多个字号（增量字形）。 */
    private fun loadFonts(candidates: List<FontFile>, vararg sizes: Int): List<Font> {
        for (c in candidates) {
            if (!java.io.File(c.path).isFile) continue
            try {
                val gen = FreeTypeFontGenerator(Core.files.absolute(c.path), c.index)
                lastGen = gen
                val fonts = sizes.map { generate(gen, it) }
                if (!this::font.isInitialized) fontSource = "${c.path}#${c.index}"
                Log.info("[RwTheme] font loaded: @", "${c.path}#${c.index}")
                return fonts
            } catch (e: Throwable) {
                Log.err("[RwTheme] font failed: ${c.path}", e)
            }
        }
        error("没有可用的系统字体")
    }

    private fun generate(gen: FreeTypeFontGenerator, size: Int, border: Float = 0f, weight: Float = -1f, opsz: Float = -1f, outline: Float = 0f, fallback: FreeTypeFontGenerator? = null): Font {
        val f = gen.generateFont(FreeTypeFontParameter().apply {
            this.fallbackGenerator = fallback
            this.outlineDelta = outline
            this.size = size
            this.weight = weight
            this.opticalSize = opsz
            if (border > 0f) { borderWidth = border; borderColor = Color.white; borderStraight = false }
            incremental = true
            minFilter = Texture.TextureFilter.linear
            magFilter = Texture.TextureFilter.linear
            packer = PixmapPacker(1024, 1024, 1, false)
        })
        // 增量模式：生成器不能释放，缺字时按需渲染
        f.data.markupEnabled = false
        return f
    }

    fun solid(color: Color): TextureRegionDrawable =
        TextureRegionDrawable(white).tint(color) as TextureRegionDrawable

    /** 抗锯齿圆角块九宫格（半径按 dp 缩放，同半径共享纹理）。 */
    fun round(radiusDp: Int, color: Color): Drawable {
        val r = Scl.scl(radiusDp.toFloat()).toInt().coerceAtLeast(2)
        val patch = roundCache.getOrPut(r) { roundPatch(r) }
        return NinePatchDrawable(patch).tint(color)
    }

    private fun roundPatch(r: Int): NinePatch {
        val size = r * 2 + 2
        val pix = Pixmap(size, size)
        for (y in 0 until size) for (x in 0 until size) {
            val cx = when { x < r -> r - 0.5f; x >= size - r -> size - r - 0.5f; else -> x + 0f }
            val cy = when { y < r -> r - 0.5f; y >= size - r -> size - r - 0.5f; else -> y + 0f }
            val dx = x - cx; val dy = y - cy
            val d = sqrt(dx * dx + dy * dy) - (r - 0.5f)
            pix.set(x, y, Color.rgba8888(1f, 1f, 1f, (0.5f - d).coerceIn(0f, 1f)))
        }
        val tex = Texture(pix).apply { setFilter(Texture.TextureFilter.linear) }
        pix.dispose()
        return NinePatch(TextureRegion(tex), r, r, r, r)
    }

    private val cardCache = HashMap<String, NinePatch>()

    /** 卡片：圆角填充 + 1dp 描边（new-api 的卡片样式），颜色烘焙进纹理，按值缓存。 */
    fun card(fill: Color = Colors.surface, border: Color = Colors.outline, radiusDp: Int = 12): Drawable {
        val r = Scl.scl(radiusDp.toFloat()).toInt().coerceAtLeast(3)
        val stroke = Scl.scl(1f).coerceAtLeast(1f)
        val key = "$r/${fill.rgba8888()}/${border.rgba8888()}"
        val patch = cardCache.getOrPut(key) {
            val size = r * 2 + 2
            val pix = Pixmap(size, size)
            val f = Color(fill)
            val b = Color(border)
            val out = Color()
            for (y in 0 until size) for (x in 0 until size) {
                val cx = when { x < r -> r - 0.5f; x >= size - r -> size - r - 0.5f; else -> x + 0f }
                val cy = when { y < r -> r - 0.5f; y >= size - r -> size - r - 0.5f; else -> y + 0f }
                val inCorner = (x < r || x >= size - r) && (y < r || y >= size - r)
                val d = if (inCorner) sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) - (r - 0.5f)
                else -(minOf(minOf(x, size - 1 - x), minOf(y, size - 1 - y)) + 0.5f)
                val coverage = (0.5f - d).coerceIn(0f, 1f)
                // 距外沿 stroke 以内是描边色，往里是填充色（叠在描边上，保证半透明描边也正确）
                val inner = (-d - stroke + 0.5f).coerceIn(0f, 1f)
                out.set(b).lerp(f, inner)
                out.a = (b.a * (1 - inner) + f.a * inner) * coverage
                pix.set(x, y, Color.rgba8888(out.r, out.g, out.b, out.a))
            }
            val tex = Texture(pix).apply { setFilter(Texture.TextureFilter.linear) }
            pix.dispose()
            NinePatch(TextureRegion(tex), r, r, r, r)
        }
        // 内边距只留描边宽度：内容（列表行、分隔线）自己决定留白，可以贴边
        return NinePatchDrawable(patch).apply { leftWidth = stroke; rightWidth = stroke; topHeight = stroke; bottomHeight = stroke }
    }

    /** 1dp 描边的圆角框（胶囊、输入框外框）。 */
    fun outline(radiusDp: Int, color: Color): Drawable {
        val r = Scl.scl(radiusDp.toFloat()).toInt().coerceAtLeast(2)
        val patch = outlineCache.getOrPut(r) { outlinePatch(r, Scl.scl(1f).coerceAtLeast(1f)) }
        return NinePatchDrawable(patch).tint(color)
    }

    private fun outlinePatch(r: Int, stroke: Float): NinePatch {
        val size = r * 2 + 2
        val pix = Pixmap(size, size)
        for (y in 0 until size) for (x in 0 until size) {
            val cx = when { x < r -> r - 0.5f; x >= size - r -> size - r - 0.5f; else -> x + 0f }
            val cy = when { y < r -> r - 0.5f; y >= size - r -> size - r - 0.5f; else -> y + 0f }
            val dx = x - cx; val dy = y - cy
            val inCorner = (x < r || x >= size - r) && (y < r || y >= size - r)
            val d = if (inCorner) sqrt(dx * dx + dy * dy) - (r - 0.5f) else -(minOf(minOf(x, size - 1 - x), minOf(y, size - 1 - y)) + 0.5f)
            // 外沿抗锯齿，内侧 stroke 宽度以内实心
            val a = (0.5f - d).coerceIn(0f, 1f) * (stroke + 0.5f + d).coerceIn(0f, 1f)
            pix.set(x, y, Color.rgba8888(1f, 1f, 1f, a))
        }
        val tex = Texture(pix).apply { setFilter(Texture.TextureFilter.linear) }
        pix.dispose()
        return NinePatch(TextureRegion(tex), r, r, r, r)
    }
}
