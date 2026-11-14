package io.github.zenthxsin.rustwright.ui.kit

import arc.graphics.Color
import arc.graphics.g2d.Shapes
import arc.input.KeyCode
import arc.math.geom.Vec2
import arc.scene.Element
import arc.scene.event.InputEvent
import arc.scene.event.InputListener
import arc.scene.event.Touchable
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import java.io.File
import kotlin.math.exp
import kotlin.math.hypot

/** 图片预览的几何状态；zoom 相对于适应视口的大小。 */
internal class ImageTransform {
    var zoom = 1f; private set
    var offsetX = 0f; private set
    var offsetY = 0f; private set
    private var imageWidth = 1f
    private var imageHeight = 1f
    private var viewportWidth = 1f
    private var viewportHeight = 1f
    val scale get() = minOf(viewportWidth / imageWidth, viewportHeight / imageHeight) * zoom
    val width get() = imageWidth * scale
    val height get() = imageHeight * scale

    fun resize(imageWidth: Float, imageHeight: Float, viewportWidth: Float, viewportHeight: Float) {
        if (minOf(imageWidth, imageHeight, viewportWidth, viewportHeight) <= 0f) return
        this.imageWidth = imageWidth; this.imageHeight = imageHeight
        this.viewportWidth = viewportWidth; this.viewportHeight = viewportHeight
        clamp()
    }
    fun zoomAt(value: Float, x: Float, y: Float) {
        if (!value.isFinite()) return
        val next = value.coerceIn(1f, 32f)
        val ratio = next / zoom
        offsetX = x - viewportWidth / 2f - (x - viewportWidth / 2f - offsetX) * ratio
        offsetY = y - viewportHeight / 2f - (y - viewportHeight / 2f - offsetY) * ratio
        zoom = next
        clamp()
    }
    fun pan(dx: Float, dy: Float) { offsetX += dx; offsetY += dy; clamp() }
    fun reset() { zoom = 1f; offsetX = 0f; offsetY = 0f }
    private fun clamp() {
        val x = ((width - viewportWidth) / 2f).coerceAtLeast(0f)
        val y = ((height - viewportHeight) / 2f).coerceAtLeast(0f)
        offsetX = offsetX.coerceIn(-x, x); offsetY = offsetY.coerceIn(-y, y)
    }
}

/** 双指缩放、单指平移、双击复位；桌面滚轮以指针为中心缩放。纹理复用缩略图缓存。 */
class ZoomImage(private val file: File) : Element() {
    internal val transform = ImageTransform()
    val zoomFactor get() = transform.zoom
    private val pointers = LinkedHashMap<Int, Vec2>()
    private var moved = false
    private var lastTap = 0L
    private val lastTapPoint = Vec2()
    private val downPoint = Vec2()

    init {
        name = "图片缩放预览"
        touchable = Touchable.enabled
        addListener(object : InputListener() {
            override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Element?) {
                if (pointer == -1) scene?.setScrollFocus(this@ZoomImage)
            }
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: KeyCode): Boolean {
                scene?.setScrollFocus(this@ZoomImage)
                if (pointers.isEmpty()) { downPoint.set(x, y); moved = false }
                pointers[pointer] = Vec2(x, y)
                if (pointers.size > 1) moved = true
                event.stop()
                return true
            }
            override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                val point = pointers[pointer] ?: return
                if (hypot(x - downPoint.x, y - downPoint.y) > dp(6f)) moved = true
                if (pointers.size == 1) {
                    transform.pan(x - point.x, y - point.y)
                    point.set(x, y)
                } else {
                    val pair = pointers.values.take(2)
                    val midX = (pair[0].x + pair[1].x) / 2f
                    val midY = (pair[0].y + pair[1].y) / 2f
                    val distance = pair[0].dst(pair[1])
                    point.set(x, y)
                    val nextDistance = pair[0].dst(pair[1])
                    if (distance >= dp(2f)) transform.zoomAt(transform.zoom * nextDistance / distance, midX, midY)
                    transform.pan((pair[0].x + pair[1].x) / 2f - midX, (pair[0].y + pair[1].y) / 2f - midY)
                }
                event.stop()
            }
            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: KeyCode) {
                pointers.remove(pointer)
                if (event.isTouchFocusCancel) { moved = true; lastTap = 0L; return }
                if (pointers.isEmpty() && !moved) {
                    val now = System.currentTimeMillis()
                    if (now - lastTap < 300 && lastTapPoint.dst(x, y) < dp(24f)) {
                        transform.reset(); lastTap = 0L
                    } else { lastTap = now; lastTapPoint.set(x, y) }
                }
                event.stop()
            }
            override fun scrolled(event: InputEvent, x: Float, y: Float, amountX: Float, amountY: Float): Boolean {
                transform.zoomAt(transform.zoom * exp(-amountY * 0.15f), x, y)
                event.stop()
                return true
            }
        })
    }

    override fun draw() {
        if (width <= 0f || height <= 0f) return
        // 每帧重新取缓存项，避免列表缩略图淘汰缓存后仍持有已释放纹理。
        val region = ImageThumb.load(file) ?: return
        transform.resize(region.width.toFloat(), region.height.toFloat(), width, height)
        if (!clipBegin()) return
        try {
            Shapes.texture(region,
                x + (width - transform.width) / 2f + transform.offsetX,
                y + (height - transform.height) / 2f + transform.offsetY,
                transform.width, transform.height, 0f, Color(1f, 1f, 1f, color.a * parentAlpha))
        } finally { clipEnd() }
    }
}
