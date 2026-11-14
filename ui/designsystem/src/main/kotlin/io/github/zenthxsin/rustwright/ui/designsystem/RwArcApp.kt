package io.github.zenthxsin.rustwright.ui.designsystem

import arc.ApplicationListener
import arc.Core
import arc.graphics.Gl
import arc.graphics.g2d.SpriteBatch
import arc.graphics.g2d.TextureAtlas
import arc.input.KeyCode
import arc.scene.Scene
import arc.scene.event.InputEvent
import arc.scene.event.InputListener
import arc.scene.ui.TextField
import arc.util.Time

/**
 * Arc 应用骨架：创建 batch/atlas/scene，加载主题，处理系统栏与键盘边距、点空白处收起键盘，
 * 每帧清屏 → act → draw。子类在 [build] 里搭界面。
 */
abstract class RwArcApp(private val fonts: List<FontFile>) : ApplicationListener {

    /** 系统栏 + 键盘占用的像素边距（左、上、右、下）；可能在 init 之前到达，先存下、每帧应用。 */
    @Volatile private var insets = floatArrayOf(0f, 0f, 0f, 0f)

    fun setInsets(left: Float, top: Float, right: Float, bottom: Float) {
        insets = floatArrayOf(left, top, right, bottom)
    }

    protected abstract fun build(scene: Scene)

    /** Entry point shared by Android gestures, physical back, and desktop Escape. */
    fun requestBack() {
        val scene = Core.scene ?: return
        if (!BackNavigation.dismissOverlay(scene)) onBackRequested()
    }

    protected open fun onBackRequested() {}

    internal fun installBackHandling(scene: Scene) {
        val held = HashSet<KeyCode>()
        Core.input.setCatch(KeyCode.back, true)
        scene.root.addCaptureListener(object : InputListener() {
            override fun keyDown(event: InputEvent, keycode: KeyCode): Boolean {
                if (keycode != KeyCode.back && keycode != KeyCode.escape) return false
                event.stop()
                if (held.add(keycode)) requestBack()
                return true
            }
            override fun keyUp(event: InputEvent, keycode: KeyCode): Boolean {
                if (keycode != KeyCode.back && keycode != KeyCode.escape) return false
                event.stop()
                held.remove(keycode)
                return true
            }
        })
    }

    override fun init() {
        Core.batch = SpriteBatch()
        Core.atlas = TextureAtlas.blankAtlas()
        Core.scene = Scene()
        Core.input.addProcessor(Core.scene)
        installBackHandling(Core.scene)
        RwTheme.load(fonts)

        Core.scene.root.addCaptureListener(object : InputListener() {
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: KeyCode): Boolean {
                val focus = Core.scene.keyboardFocus
                if (focus is TextField && !event.targetActor.isDescendantOf(focus) && !keepsKeyboard(event.targetActor)) Core.scene.setKeyboardFocus(null)
                return false
            }
        })
        build(Core.scene)
    }

    override fun resize(width: Int, height: Int) {
        Core.scene.resize(width, height)
    }

    override fun update() {
        Time.update()
        val i = insets
        Core.scene.marginLeft = i[0]
        Core.scene.marginTop = i[1]
        Core.scene.marginRight = i[2]
        Core.scene.marginBottom = i[3]
        val bg = RwTheme.Colors.bg
        Gl.clearColor(bg.r, bg.g, bg.b, 1f)
        Gl.clear(Gl.colorBufferBit)
        // 动画按帧推进，一帧最多算 1/30 秒：建页面、生成字形卡住的那一帧不会把入场动画整段跳过
        Core.scene.act(minOf(Core.graphics.deltaTime, 1f / 30f))
        Core.scene.draw()
    }
}

/**
 * 点在带这个标记（[arc.scene.Element.userObject]）的控件或它的子控件上时，不自动收起键盘。
 * 终端用：滑动查看、长按选择、点扩展键都不该把键盘收掉，只有再点一次键盘键才收起。
 */
object KeepKeyboard

internal fun keepsKeyboard(target: arc.scene.Element?): Boolean =
    generateSequence(target) { it.parent }.any { it.userObject === KeepKeyboard }
