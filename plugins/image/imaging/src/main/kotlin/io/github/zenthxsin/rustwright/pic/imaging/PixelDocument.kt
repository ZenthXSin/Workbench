package io.github.zenthxsin.rustwright.pic.imaging

/**
 * 像素编辑文档：一张 ARGB 图 + 撤销 / 重做。每一笔（按下到抬起）开始前调用 [beginStroke] 存快照，
 * 贴图通常不超过 512×512，整图快照足够便宜。
 */
class PixelDocument(image: RgbaImage, private val maxHistory: Int = 64) {
    var image: RgbaImage = image
        private set
    private val undo = ArrayDeque<IntArray>()
    private val redo = ArrayDeque<IntArray>()
    /** 每次像素变化加一，渲染侧据此刷新纹理。 */
    var revision = 0L
        private set
    /** 左右镜像绘制。 */
    var mirrorX = false

    val width get() = image.width
    val height get() = image.height
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun beginStroke() {
        undo.addLast(image.pixels.copyOf())
        if (undo.size > maxHistory) undo.removeFirst()
        redo.clear()
    }

    fun undo(): Boolean {
        val prev = undo.removeLastOrNull() ?: return false
        redo.addLast(image.pixels.copyOf())
        prev.copyInto(image.pixels)
        revision++
        return true
    }

    fun redo(): Boolean {
        val next = redo.removeLastOrNull() ?: return false
        undo.addLast(image.pixels.copyOf())
        next.copyInto(image.pixels)
        revision++
        return true
    }

    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height

    fun get(x: Int, y: Int): Int = if (inBounds(x, y)) image.getPixel(x, y) else Color.TRANSPARENT

    fun plot(x: Int, y: Int, color: Int, size: Int = 1) {
        val r0 = -(size - 1) / 2
        for (dy in r0 until r0 + size) for (dx in r0 until r0 + size) {
            set(x + dx, y + dy, color)
            if (mirrorX) set(width - 1 - (x + dx), y + dy, color)
        }
    }

    private fun set(x: Int, y: Int, color: Int) {
        if (!inBounds(x, y)) return
        if (image.getPixel(x, y) != color) { image.setPixel(x, y, color); revision++ }
    }

    /** Bresenham 直线（拖动时连接相邻两次采样点，避免断笔）。 */
    fun line(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, size: Int = 1) {
        var x = x0; var y = y0
        val dx = kotlin.math.abs(x1 - x0); val sx = if (x0 < x1) 1 else -1
        val dy = -kotlin.math.abs(y1 - y0); val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            plot(x, y, color, size)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /** 四连通填充（扫描线，非递归）。 */
    fun fill(x: Int, y: Int, color: Int) {
        if (!inBounds(x, y)) return
        val target = image.getPixel(x, y)
        if (target == color) return
        val px = image.pixels
        val w = width
        val stack = ArrayDeque<Int>()
        stack.addLast(y * w + x)
        while (stack.isNotEmpty()) {
            val p = stack.removeLast()
            var lx = p % w
            val py = p / w
            while (lx > 0 && px[py * w + lx - 1] == target) lx--
            var spanUp = false; var spanDown = false
            var cx = lx
            while (cx < w && px[py * w + cx] == target) {
                px[py * w + cx] = color
                if (py > 0) {
                    val up = px[(py - 1) * w + cx] == target
                    if (up && !spanUp) stack.addLast((py - 1) * w + cx)
                    spanUp = up
                }
                if (py < height - 1) {
                    val down = px[(py + 1) * w + cx] == target
                    if (down && !spanDown) stack.addLast((py + 1) * w + cx)
                    spanDown = down
                }
                cx++
            }
        }
        revision++
    }

    /** 把一种颜色整体替换为另一种（换色 / 去底色）。 */
    fun replaceColor(from: Int, to: Int) {
        val px = image.pixels
        for (i in px.indices) if (px[i] == from) px[i] = to
        revision++
    }

    /** 画布尺寸调整（左上角对齐，新增区域透明）。 */
    fun resize(w: Int, h: Int) {
        beginStroke()
        val out = RgbaImage(w, h)
        for (y in 0 until minOf(h, height)) for (x in 0 until minOf(w, width)) out.setPixel(x, y, image.getPixel(x, y))
        image = out
        undo.clear(); redo.clear()
        revision++
    }

    fun flipHorizontal() {
        beginStroke()
        val px = image.pixels
        for (y in 0 until height) for (x in 0 until width / 2) {
            val a = y * width + x; val b = y * width + (width - 1 - x)
            val t = px[a]; px[a] = px[b]; px[b] = t
        }
        revision++
    }

    /** 所用颜色（按出现次数降序，最多 [limit] 个，忽略全透明）。 */
    fun palette(limit: Int = 32): List<Int> =
        image.pixels.filter { Color.alpha(it) > 0 }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(limit).map { it.key }
}
