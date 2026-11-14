package io.github.zenthxsin.rustwright.pic.impl

import arc.graphics.Color
import arc.graphics.Pixmap
import arc.graphics.Texture
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Shapes
import arc.graphics.g2d.TextureRegion
import arc.scene.Element
import io.github.zenthxsin.rustwright.pic.imaging.RgbaImage
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.K

/**
 * 工具页里的图片预览：最近邻放大、按比例居中，圆角底；可以叠一层网格（切图的行列线）。
 * 没有图时显示 [hint]。
 */
internal class RgbaPreview(private val hint: String = "点这里选一张图") : Element() {
    private var region: TextureRegion? = null
    private var iw = 0
    private var ih = 0
    var gridCols = 0
    var gridRows = 0
    private val hintLabel = K.text(hint, 15f, K.subtle)

    fun set(img: RgbaImage?) {
        region?.texture?.dispose()
        iw = img?.width ?: 0
        ih = img?.height ?: 0
        region = img?.let {
            val pm = Pixmap(it.width, it.height)
            for (y in 0 until it.height) for (x in 0 until it.width) {
                val argb = it.pixels[y * it.width + x]
                pm.set(x, y, (argb shl 8) or (argb ushr 24))
            }
            val t = Texture(pm)
            t.setFilter(Texture.TextureFilter.nearest)
            pm.dispose()
            TextureRegion(t)
        }
    }

    override fun draw() {
        val a = parentAlpha * color.a
        Shapes.clipBegin(x, y, width, height, dp(12f))
        Shapes.rect(x, y, width, height, 0f, Color(K.input).a(a))
        val r = region
        if (r != null && iw > 0 && ih > 0) {
            val pad = dp(8f)
            val s = minOf((width - pad * 2) / iw, (height - pad * 2) / ih)
            val w = iw * s
            val h = ih * s
            val ox = x + (width - w) / 2f
            val oy = y + (height - h) / 2f
            Draw.color(1f, 1f, 1f, a)
            Draw.rect(r, ox + w / 2f, oy + h / 2f, w, h)
            Draw.reset()
            if (gridCols > 1 || gridRows > 1) {
                val line = Color(K.accent).a(0.85f * a)
                for (c in 1 until gridCols) Shapes.rect(ox + w * c / gridCols - dp(0.75f), oy, dp(1.5f), h, 0f, line)
                for (rr in 1 until gridRows) Shapes.rect(ox, oy + h * rr / gridRows - dp(0.75f), w, dp(1.5f), 0f, line)
            }
        }
        Shapes.clipEnd()
        if (r == null) {
            hintLabel.setSize(hintLabel.prefWidth, hintLabel.prefHeight)
            hintLabel.setPosition(x + (width - hintLabel.prefWidth) / 2f, y + (height - hintLabel.prefHeight) / 2f)
            hintLabel.validate()
            hintLabel.draw()
        }
    }
}
