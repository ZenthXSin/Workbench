package io.github.zenthxsin.rustwright.pic.imaging

import kotlin.math.max
import kotlin.math.min

/**
 * Platform-free layered editor. A batch either succeeds wholly or leaves document/history
 * untouched.
 */
class LayeredPixelDocument(val width: Int, val height: Int) {
    data class Layer(
        val name: String,
        val pixels: IntArray,
        val visible: Boolean = true,
        val locked: Boolean = false,
        val opacity: Int = 255,
        val blend: PixelBlend = PixelBlend.NORMAL,
        /**
         * Preserved Makapix 3w by 3h outer plane; center must be zero. Editing affects canvas only.
         */
        val gutterPixels: IntArray? = null,
    )

    data class Frame(
        val layers: List<Layer>,
        val durationMillis: Int = 100,
        val durationMicros: Int = durationMillis * 1000,
        val activeLayer: Int = 0,
    )

    data class PaletteColor(val color: Int, val name: String? = null)

    data class Palette(val name: String, val colors: List<PaletteColor> = emptyList())

    data class LayerInfo(
        val name: String,
        val visible: Boolean,
        val locked: Boolean,
        val opacity: Int,
        val blend: PixelBlend,
    )

    data class Point(val x: Int, val y: Int)

    data class Action(
        val type: String,
        val points: List<Point> = emptyList(),
        val color: Int = 0xff000000.toInt(),
        val size: Int = 1,
        val filled: Boolean = false,
        val index: Int = 0,
        val name: String = "Layer",
        val axis: String = "horizontal",
        val enabled: Boolean = true,
        val target: Int = 0,
        val opacity: Int = 255,
        val blend: PixelBlend = PixelBlend.NORMAL,
        val durationMillis: Int = 100,
        val dx: Int = 0,
        val dy: Int = 0,
        val targets: List<Int> = emptyList(),
    )

    data class Snapshot(
        val layers: List<Layer>,
        val active: Int,
        val color: Int,
        val selection: BooleanArray?,
        val frames: List<Frame> = emptyList(),
        val currentFrame: Int = 0,
        val palettes: List<Palette> = listOf(Palette("Default")),
        val activePalette: Int = 0,
        val loopMode: Int = 0,
    )

    private var state: Snapshot
    private val undo = ArrayDeque<Snapshot>()
    private val redo = ArrayDeque<Snapshot>()
    var revision = 0L
        private set

    val layers
        get() =
            state.layers.map {
                it.copy(pixels = it.pixels.copyOf(), gutterPixels = it.gutterPixels?.copyOf())
            }

    val layerNames
        get() = state.layers.map { it.name }

    val layerInfo
        get() = state.layers.map { LayerInfo(it.name, it.visible, it.locked, it.opacity, it.blend) }

    val frameCount
        get() = allFrames(state).size

    val currentFrame
        get() = state.currentFrame

    val frameDurations
        get() = allFrames(state).map { it.durationMillis }

    val palettes
        get() = state.palettes

    val activePalette
        get() = state.activePalette

    val activeLayer
        get() = state.active

    val retainedPixels
        get() = pixelCount(state) + undo.sumOf(::pixelCount) + redo.sumOf(::pixelCount)

    val canUndo
        get() = undo.isNotEmpty()

    val canRedo
        get() = redo.isNotEmpty()

    init {
        require(width in 1..1024 && height in 1..1024)
        state =
            Snapshot(
                listOf(Layer("Background", IntArray(width * height))),
                0,
                0xff000000.toInt(),
                null,
            )
    }

    fun snapshot() = copy(state)

    private fun allFrames(s: Snapshot): List<Frame> =
        if (s.frames.isEmpty()) listOf(Frame(s.layers, activeLayer = s.active))
        else
            s.frames.mapIndexed { i, f ->
                if (i == s.currentFrame) f.copy(layers = s.layers, activeLayer = s.active) else f
            }

    private fun pixelCount(s: Snapshot) =
        allFrames(s).sumOf { f -> f.layers.sumOf { layerPixels(it) } }

    private fun layerPixels(l: Layer) = l.pixels.size.toLong() + (l.gutterPixels?.size ?: 0)

    private fun validate(s: Snapshot) {
        val frames = allFrames(s)
        require(frames.size in 1..256 && s.currentFrame in frames.indices)
        require(s.layers.size in 1..64 && s.active in s.layers.indices)
        require(pixelCount(s) <= 4_194_304)
        require(
            frames.all { f ->
                f.durationMicros in 10_000..60_000_000 &&
                    f.durationMillis == f.durationMicros / 1000 &&
                    f.activeLayer in f.layers.indices &&
                    f.layers.size in 1..64 &&
                    f.layers.all {
                        it.pixels.size == width * height &&
                            (it.gutterPixels == null ||
                                it.gutterPixels.size == width * height * 9) &&
                            it.name.isNotBlank() &&
                            it.name.length <= 64 &&
                            it.opacity in 0..255
                    }
            }
        )
        frames.forEach { frame ->
            frame.layers.forEach { layer ->
                layer.gutterPixels?.let { gutter ->
                    for (y in 0 until height) for (x in 0 until width) require(
                        gutter[(y + height) * width * 3 + x + width] == 0
                    ) {
                        "Gutter center must be zero"
                    }
                }
            }
        }
        require(s.loopMode in 0..2)
        require(s.selection == null || s.selection.size == width * height)
        require(s.palettes.size in 1..32 && s.activePalette in s.palettes.indices)
        require(
            s.palettes.all {
                it.name.isNotBlank() &&
                    it.name.length <= 64 &&
                    it.colors.size <= 256 &&
                    it.colors.all { c -> c.name == null || c.name.length <= 64 }
            }
        )
    }

    fun restore(snapshot: Snapshot) {
        validate(snapshot)
        state = copy(snapshot)
        undo.clear()
        redo.clear()
        revision++
    }

    private fun copy(s: Snapshot): Snapshot {
        val frames =
            allFrames(s).map { f ->
                f.copy(
                    layers =
                        f.layers.map {
                            it.copy(
                                pixels = it.pixels.copyOf(),
                                gutterPixels = it.gutterPixels?.copyOf(),
                            )
                        }
                )
            }
        return s.copy(
            layers = frames[s.currentFrame].layers,
            frames = frames,
            selection = s.selection?.copyOf(),
        )
    }

    private fun setFrames(frames: List<Frame>, active: Int = state.currentFrame) {
        state =
            state.copy(
                frames = frames,
                currentFrame = active,
                layers = frames[active].layers,
                active = frames[active].activeLayer.coerceAtMost(frames[active].layers.lastIndex),
            )
    }

    fun apply(actions: List<Action>, checkpoint: () -> Unit = {}) {
        require(actions.size in 1..128 && actions.sumOf { it.points.size.toLong() } <= 16_384)
        if (actions.size == 1 && actions[0].type in setOf("undo", "redo")) {
            val backward = actions[0].type == "undo"
            val source = if (backward) undo else redo
            val target = if (backward) redo else undo
            if (source.isNotEmpty()) {
                target.addLast(state)
                state = source.removeLast()
                revision++
            }
            return
        }
        val work =
            actions.sumOf { a ->
                if (a.type == "polygon" && a.filled) width.toLong() * height * a.points.size
                else if (a.type in setOf("pencil", "brush", "eraser", "line", "polygon")) {
                    val distance =
                        a.points.zipWithNext().sumOf { (p, q) ->
                            max(
                                kotlin.math.abs(p.x.toLong() - q.x),
                                kotlin.math.abs(p.y.toLong() - q.y),
                            ) + 1
                        }
                    maxOf(1, distance) * a.size.coerceIn(1, 64) * a.size.coerceIn(1, 64)
                } else width.toLong() * height
            }
        require(work <= 16_777_216) {
            "Drawing batch exceeds work budget; split into smaller strokes"
        }
        checkpoint()
        val previous = state
        state = copy(state)
        try {
            actions.forEach {
                checkpoint()
                act(it, checkpoint)
                state = state.copy(frames = allFrames(state))
                validate(state)
            }
        } catch (e: Exception) {
            state = previous
            throw e
        }
        undo.addLast(previous)
        redo.clear()
        // Bound undo memory independently from layer count (8M retained pixels ~32 MB).
        while (undo.size > 32 || undo.sumOf(::pixelCount) > 8_388_608) undo.removeFirst()
        revision++
    }

    private fun act(a: Action, checkpoint: () -> Unit) {
        require(a.size in 1..64)
        a.points.forEach {
            require(it.x in 0 until width && it.y in 0 until height) { "Point outside canvas" }
        }
        fun layerIndex() {
            require(a.index in state.layers.indices) { "Observe document to obtain layer index" }
        }
        fun validName() {
            require(a.name.isNotBlank() && a.name.length <= 64 && a.name.none(Char::isISOControl))
        }
        fun selected() {
            require(state.selection?.any { it } == true) { "Select pixels first" }
        }
        when (a.type) {
            "set_color" -> state = state.copy(color = a.color)
            "select_frame" -> {
                val frames = allFrames(state)
                require(a.index in frames.indices)
                setFrames(frames, a.index)
            }
            "add_frame",
            "duplicate_frame" -> {
                val frames = allFrames(state).toMutableList()
                require(frames.size < 256)
                val source =
                    if (a.type == "duplicate_frame") {
                        require(a.index in frames.indices)
                        frames[a.index]
                    } else
                        Frame(
                            listOf(Layer("Background", IntArray(width * height))),
                            a.durationMillis,
                        )
                require(pixelCount(state) + source.layers.sumOf { layerPixels(it) } <= 4_194_304)
                frames +=
                    source.copy(
                        layers =
                            source.layers.map {
                                it.copy(
                                    pixels = it.pixels.copyOf(),
                                    gutterPixels = it.gutterPixels?.copyOf(),
                                )
                            }
                    )
                setFrames(frames, frames.lastIndex)
            }
            "delete_frame" -> {
                val fs = allFrames(state).toMutableList()
                require(fs.size > 1 && a.index in fs.indices)
                fs.removeAt(a.index)
                setFrames(
                    fs,
                    (if (a.index < state.currentFrame) state.currentFrame - 1
                        else state.currentFrame)
                        .coerceAtMost(fs.lastIndex),
                )
            }
            "move_frame" -> {
                val fs = allFrames(state).toMutableList()
                require(a.index in fs.indices && a.target in fs.indices)
                val current = fs[state.currentFrame]
                fs.add(a.target, fs.removeAt(a.index))
                setFrames(fs, fs.indexOf(current))
            }
            "set_frame_duration" -> {
                val fs = allFrames(state)
                require(a.index in fs.indices && a.durationMillis in 10..60_000)
                setFrames(
                    fs.mapIndexed { i, f ->
                        if (i == a.index)
                            f.copy(
                                durationMillis = a.durationMillis,
                                durationMicros = a.durationMillis * 1000,
                            )
                        else f
                    }
                )
            }
            "duplicate_layer_to_frames" -> {
                layerIndex()
                val targets = a.targets.distinct()
                val fs = allFrames(state)
                require(
                    targets.isNotEmpty() &&
                        targets.size <= 256 &&
                        targets.all { it in fs.indices && fs[it].layers.size < 64 }
                )
                require(
                    pixelCount(state) +
                        targets.size.toLong() * layerPixels(state.layers[a.index]) <= 4_194_304
                )
                val source = state.layers[a.index]
                setFrames(
                    fs.mapIndexed { i, f ->
                        if (i in targets)
                            f.copy(
                                layers =
                                    f.layers +
                                        source.copy(
                                            pixels = source.pixels.copyOf(),
                                            gutterPixels = source.gutterPixels?.copyOf(),
                                        )
                            )
                        else f
                    }
                )
            }
            "duplicate_palette" -> {
                require(a.index in state.palettes.indices && state.palettes.size < 32)
                val ps = state.palettes.toMutableList()
                ps.add(a.index + 1, ps[a.index].copy(colors = ps[a.index].colors.toList()))
                state = state.copy(palettes = ps, activePalette = a.index + 1)
            }
            "move_palette" -> {
                val ps = state.palettes.toMutableList()
                require(a.index in ps.indices && a.target in ps.indices)
                val active = state.activePalette
                ps.add(a.target, ps.removeAt(a.index))
                val next =
                    when {
                        active == a.index -> a.target
                        a.index < active && a.target >= active -> active - 1
                        a.index > active && a.target <= active -> active + 1
                        else -> active
                    }
                state = state.copy(palettes = ps, activePalette = next)
            }
            "clear_palette" -> {
                require(a.index in state.palettes.indices)
                state =
                    state.copy(
                        palettes =
                            state.palettes.mapIndexed { i, p ->
                                if (i == a.index) p.copy(colors = emptyList()) else p
                            }
                    )
            }
            "select_palette" -> {
                require(a.index in state.palettes.indices)
                state = state.copy(activePalette = a.index)
            }
            "add_palette" -> {
                validName()
                require(state.palettes.size < 32)
                state =
                    state.copy(
                        palettes = state.palettes + Palette(a.name),
                        activePalette = state.palettes.size,
                    )
            }
            "delete_palette" -> {
                require(state.palettes.size > 1 && a.index in state.palettes.indices)
                state =
                    state.copy(
                        palettes = state.palettes.filterIndexed { i, _ -> i != a.index },
                        activePalette =
                            (if (a.index < state.activePalette) state.activePalette - 1
                                else state.activePalette)
                                .coerceAtMost(state.palettes.size - 2),
                    )
            }
            "rename_palette" -> {
                validName()
                require(a.index in state.palettes.indices)
                state =
                    state.copy(
                        palettes =
                            state.palettes.mapIndexed { i, p ->
                                if (i == a.index) p.copy(name = a.name) else p
                            }
                    )
            }
            "palette_add",
            "palette_edit",
            "palette_delete",
            "palette_duplicate",
            "palette_swap",
            "palette_name",
            "palette_sort" -> {
                val p = state.palettes[state.activePalette]
                val colors = p.colors.toMutableList()
                if (a.type !in setOf("palette_add", "palette_sort"))
                    require(a.index in colors.indices)
                when (a.type) {
                    "palette_add" -> {
                        require(colors.size < 256)
                        colors += PaletteColor(a.color)
                    }
                    "palette_edit" -> colors[a.index] = colors[a.index].copy(color = a.color)
                    "palette_delete" -> colors.removeAt(a.index)
                    "palette_duplicate" -> {
                        require(colors.size < 256)
                        colors.add(a.index + 1, colors[a.index])
                    }
                    "palette_swap" -> {
                        require(a.target in colors.indices)
                        val old = colors[a.index]
                        colors[a.index] = colors[a.target]
                        colors[a.target] = old
                    }
                    "palette_name" -> {
                        require(a.name.length <= 64)
                        colors[a.index] = colors[a.index].copy(name = a.name.ifBlank { null })
                    }
                    "palette_sort" ->
                        colors.sortBy {
                            .299 * Color.red(it.color) +
                                .587 * Color.green(it.color) +
                                .114 * Color.blue(it.color)
                        }
                }
                state =
                    state.copy(
                        palettes =
                            state.palettes.mapIndexed { i, v ->
                                if (i == state.activePalette) v.copy(colors = colors) else v
                            }
                    )
            }
            "set_layer_opacity",
            "set_layer_blend" -> {
                layerIndex()
                require(a.opacity in 0..255)
                state =
                    state.copy(
                        layers =
                            state.layers.mapIndexed { i, l ->
                                if (i != a.index) l
                                else if (a.type == "set_layer_opacity") l.copy(opacity = a.opacity)
                                else l.copy(blend = a.blend)
                            }
                    )
            }
            "merge_layer_down" -> {
                layerIndex()
                require(a.index > 0)
                val top = state.layers[a.index]
                val bottom = state.layers[a.index - 1]
                require(!top.locked && !bottom.locked && top.visible && bottom.visible)
                require(top.gutterPixels == null && bottom.gutterPixels == null) {
                    "Cannot merge preserved off-canvas pixels"
                }
                require(bottom.blend == PixelBlend.NORMAL) {
                    "Flatten non-normal backdrop explicitly before merging"
                }
                val pixels =
                    IntArray(width * height) { i ->
                        top.blend.composite(
                            PixelBlend.NORMAL.composite(0, bottom.pixels[i], bottom.opacity),
                            top.pixels[i],
                            top.opacity,
                        )
                    }
                val ls = state.layers.toMutableList()
                ls[a.index - 1] =
                    bottom.copy(pixels = pixels, opacity = 255, blend = PixelBlend.NORMAL)
                ls.removeAt(a.index)
                state = state.copy(layers = ls, active = a.index - 1)
            }
            "nudge_layer" -> {
                layerIndex()
                val layer = state.layers[a.index]
                require(
                    !layer.locked &&
                        layer.visible &&
                        kotlin.math.abs(a.dx.toLong()) <= width &&
                        kotlin.math.abs(a.dy.toLong()) <= height
                )
                val pixels = IntArray(width * height)
                for (y in 0 until height) {
                    checkpoint()
                    for (x in 0 until width) {
                        val nx = x + a.dx
                        val ny = y + a.dy
                        if (nx in 0 until width && ny in 0 until height)
                            pixels[ny * width + nx] = layer.pixels[y * width + x]
                    }
                }
                state =
                    state.copy(
                        layers =
                            state.layers.mapIndexed { i, l ->
                                if (i == a.index) l.copy(pixels = pixels) else l
                            }
                    )
            }

            "select_layer" -> {
                layerIndex()
                state = state.copy(active = a.index)
            }
            "add_layer",
            "duplicate_layer" -> {
                require(
                    state.layers.size < 64 &&
                        pixelCount(state) + width.toLong() * height <= 4_194_304
                )
                val layer =
                    if (a.type == "duplicate_layer") {
                        layerIndex()
                        state.layers[a.index].let { source ->
                            require(pixelCount(state) + layerPixels(source) <= 4_194_304)
                            source.copy(
                                pixels = source.pixels.copyOf(),
                                gutterPixels = source.gutterPixels?.copyOf(),
                            )
                        }
                    } else {
                        validName()
                        Layer(a.name, IntArray(width * height))
                    }
                state = state.copy(layers = state.layers + layer, active = state.layers.size)
            }
            "rename_layer" -> {
                layerIndex()
                validName()
                state =
                    state.copy(
                        layers =
                            state.layers.mapIndexed { i, l ->
                                if (i == a.index) l.copy(name = a.name) else l
                            }
                    )
            }
            "set_layer_visible",
            "set_layer_locked" -> {
                layerIndex()
                state =
                    state.copy(
                        layers =
                            state.layers.mapIndexed { i, l ->
                                if (i != a.index) l
                                else if (a.type == "set_layer_visible") l.copy(visible = a.enabled)
                                else l.copy(locked = a.enabled)
                            }
                    )
            }
            "delete_layer" -> {
                layerIndex()
                require(state.layers.size > 1)
                state =
                    state.copy(
                        layers = state.layers.filterIndexed { i, _ -> i != a.index },
                        active =
                            (if (a.index < state.active) state.active - 1 else state.active)
                                .coerceAtMost(state.layers.size - 2),
                    )
            }
            "move_layer" -> {
                layerIndex()
                require(a.target in state.layers.indices)
                val ls = state.layers.toMutableList()
                val active = ls[state.active]
                ls.add(a.target, ls.removeAt(a.index))
                state = state.copy(layers = ls, active = ls.indexOf(active))
            }
            "select_all" -> state = state.copy(selection = BooleanArray(width * height) { true })
            "clear_selection" -> state = state.copy(selection = null)
            "invert_selection" ->
                state =
                    state.copy(
                        selection =
                            BooleanArray(width * height) { !(state.selection?.get(it) ?: false) }
                    )
            "rectangle_selection" -> {
                require(a.points.size == 2)
                val p = a.points[0]
                val q = a.points[1]
                state =
                    state.copy(
                        selection =
                            BooleanArray(width * height) {
                                it % width in min(p.x, q.x)..max(p.x, q.x) &&
                                    it / width in min(p.y, q.y)..max(p.y, q.y)
                            }
                    )
            }
            else -> {
                val layer = state.layers[state.active]
                require(layer.visible && !layer.locked) { "Active layer hidden or locked" }
                val pixels = layer.pixels
                val mask = state.selection
                fun set(x: Int, y: Int, c: Int = state.color) {
                    if (
                        x in 0 until width &&
                            y in 0 until height &&
                            (mask == null || mask[y * width + x])
                    )
                        pixels[y * width + x] = c
                }
                fun plot(p: Point, c: Int = state.color) {
                    val lo = -(a.size - 1) / 2
                    for (dy in lo until lo + a.size) for (dx in lo until lo + a.size) set(
                        p.x + dx,
                        p.y + dy,
                        c,
                    )
                }
                fun line(p: Point, q: Point, c: Int = state.color) {
                    var x = p.x
                    var y = p.y
                    val dx = kotlin.math.abs(q.x - x)
                    val sx = if (x < q.x) 1 else -1
                    val dy = -kotlin.math.abs(q.y - y)
                    val sy = if (y < q.y) 1 else -1
                    var err = dx + dy
                    while (true) {
                        if ((x + y) and 255 == 0) checkpoint()
                        plot(Point(x, y), c)
                        if (x == q.x && y == q.y) break
                        val e = 2 * err
                        if (e >= dy) {
                            err += dy
                            x += sx
                        }
                        if (e <= dx) {
                            err += dx
                            y += sy
                        }
                    }
                }
                when (a.type) {
                    "pencil",
                    "brush",
                    "eraser",
                    "line" -> {
                        require(a.points.isNotEmpty() && a.points.size <= 4096)
                        val color = if (a.type == "eraser") 0 else state.color
                        if (a.points.size == 1) plot(a.points[0], color)
                        else a.points.zipWithNext().forEach { (p, q) -> line(p, q, color) }
                    }
                    "polygon" -> {
                        require(a.points.size in 3..4096)
                        if (!a.filled)
                            (a.points + a.points.first()).zipWithNext().forEach { (p, q) ->
                                line(p, q)
                            }
                        else {
                            val minX = a.points.minOf { it.x }
                            val maxX = a.points.maxOf { it.x }
                            val minY = a.points.minOf { it.y }
                            val maxY = a.points.maxOf { it.y }
                            for (y in minY..maxY) {
                                checkpoint()
                                for (x in minX..maxX) {
                                    var inside = false
                                    var previous = a.points.last()
                                    for (current in a.points) {
                                        if (
                                            (current.y > y) != (previous.y > y) &&
                                                x <
                                                    (previous.x - current.x).toDouble() *
                                                        (y - current.y) / (previous.y - current.y) +
                                                        current.x
                                        )
                                            inside = !inside
                                        previous = current
                                    }
                                    if (inside) set(x, y)
                                }
                            }
                            (a.points + a.points.first()).zipWithNext().forEach { (p, q) ->
                                line(p, q)
                            }
                        }
                    }
                    "rectangle",
                    "ellipse" -> {
                        require(a.points.size == 2)
                        val p = a.points[0]
                        val q = a.points[1]
                        val x0 = min(p.x, q.x)
                        val x1 = max(p.x, q.x)
                        val y0 = min(p.y, q.y)
                        val y1 = max(p.y, q.y)
                        val rx = (x1 - x0 + 1) / 2.0
                        val ry = (y1 - y0 + 1) / 2.0
                        val cx = (x0 + x1) / 2.0
                        val cy = (y0 + y1) / 2.0
                        for (y in y0..y1) for (x in x0..x1) {
                            val outer =
                                if (a.type == "rectangle") true
                                else
                                    ((x - cx) / rx) * ((x - cx) / rx) +
                                        ((y - cy) / ry) * ((y - cy) / ry) <= 1
                            val inner =
                                if (a.type == "rectangle")
                                    x >= x0 + a.size &&
                                        x <= x1 - a.size &&
                                        y >= y0 + a.size &&
                                        y <= y1 - a.size
                                else
                                    rx > a.size &&
                                        ry > a.size &&
                                        ((x - cx) / (rx - a.size)) * ((x - cx) / (rx - a.size)) +
                                            ((y - cy) / (ry - a.size)) *
                                                ((y - cy) / (ry - a.size)) < 1
                            if (outer && (a.filled || !inner)) set(x, y)
                        }
                    }
                    "fill_selection",
                    "clear_pixels" -> {
                        selected()
                        for (i in pixels.indices) if (mask!![i])
                            pixels[i] = if (a.type == "clear_pixels") 0 else state.color
                    }
                    "flip_selection" -> {
                        selected()
                        require(a.axis in setOf("horizontal", "vertical"))
                        val indices = pixels.indices.filter { mask!![it] }
                        val x0 = indices.minOf { it % width }
                        val x1 = indices.maxOf { it % width }
                        val y0 = indices.minOf { it / width }
                        val y1 = indices.maxOf { it / width }
                        val old = pixels.copyOf()
                        for (i in indices) {
                            val x = if (a.axis == "horizontal") x0 + x1 - i % width else i % width
                            val y = if (a.axis == "vertical") y0 + y1 - i / width else i / width
                            pixels[i] = if (mask!![y * width + x]) old[y * width + x] else 0
                        }
                    }
                    "flood_fill" -> {
                        require(a.points.size == 1)
                        val p = a.points[0]
                        val target = pixels[p.y * width + p.x]
                        if (target == state.color || (mask != null && !mask[p.y * width + p.x]))
                            return
                        val queue = ArrayDeque<Int>()
                        queue.add(p.y * width + p.x)
                        var steps = 0
                        while (queue.isNotEmpty()) {
                            if (steps++ and 1023 == 0) checkpoint()
                            val i = queue.removeLast()
                            if (pixels[i] != target || (mask != null && !mask[i])) continue
                            pixels[i] = state.color
                            val x = i % width
                            val y = i / width
                            if (x > 0) queue.add(i - 1)
                            if (x < width - 1) queue.add(i + 1)
                            if (y > 0) queue.add(i - width)
                            if (y < height - 1) queue.add(i + width)
                        }
                    }
                    else -> error("Unsupported pixel action: ${a.type}")
                }
            }
        }
    }

    fun compositeFrame(index: Int): RgbaImage {
        val frames = allFrames(state)
        require(index in frames.indices)
        return compositeLayers(frames[index].layers)
    }

    fun composite(): RgbaImage = compositeLayers(state.layers)

    private fun compositeLayers(layers: List<Layer>): RgbaImage =
        RgbaImage(width, height).also { out ->
            layers
                .filter { it.visible }
                .forEach { l ->
                    for (i in out.pixels.indices) out.pixels[i] =
                        l.blend.composite(out.pixels[i], l.pixels[i], l.opacity)
                }
        }
}
