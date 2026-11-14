package io.github.zenthxsin.rustwright.ui.kit

import arc.Core
import arc.graphics.Color
import arc.graphics.Texture
import arc.graphics.g2d.Shapes
import arc.graphics.g2d.TextureRegion
import arc.scene.Element
import arc.scene.event.Touchable
import arc.util.Log
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import java.io.File

/**
 * 图片缩略图：按比例放进框里居中，像素图用最近邻采样不糊。
 * 纹理按「路径 + 修改时间」缓存，最多留 [MAX] 张，旧的释放。
 * [tint] 为 true 时底色取图片主色调的深色版（原型贴图卡片的 #2E3A2A / #3A2E2A / #2A2F3A：饱和 0.28、明度 0.23）。
 */
class ImageThumb(private val file: File, private val bg: Color? = null, private val tint: Boolean = false) : Element() {
    private var region: TextureRegion? = null
    private var tried = false

    init {
        touchable = Touchable.disabled
    }

    override fun draw() {
        val a = parentAlpha * color.a
        if (!tried) {
            tried = true
            region = load(file)
        }
        (bg ?: if (tint) tints[key(file)] ?: NEUTRAL else null)?.let { Shapes.rect(x, y, width, height, 0f, Color(it).a(a)) }
        val r = region ?: return
        val pad = dp(6f)
        val s = minOf((width - pad * 2) / r.width, (height - pad * 2) / r.height)
        val w = r.width * s
        val h = r.height * s
        Shapes.texture(r, x + (width - w) / 2f, y + (height - h) / 2f, w, h, 0f, Color(1f, 1f, 1f, a))
    }

    companion object {
        private const val MAX = 48
        private val NEUTRAL = Color.valueOf("2E2E2C")
        private val tints = HashMap<String, Color>()

        private fun key(file: File) = file.absolutePath + "@" + file.lastModified()

        /** 不透明像素按饱和度加权求平均色，取其色相配固定的低饱和、低明度；几乎是灰的图用中性灰。 */
        private fun tintOf(p: arc.graphics.Pixmap): Color {
            var r = 0.0; var g = 0.0; var b = 0.0; var wsum = 0.0
            val step = maxOf(1, maxOf(p.width, p.height) / 64)
            val c = Color()
            var y = 0
            while (y < p.height) {
                var x = 0
                while (x < p.width) {
                    c.set(p.get(x, y))
                    if (c.a > 0.5f) {
                        val mx = maxOf(c.r, c.g, c.b); val mn = minOf(c.r, c.g, c.b)
                        val sat = if (mx > 0f) (mx - mn) / mx else 0f
                        val w = 0.05 + sat
                        r += c.r * w; g += c.g * w; b += c.b * w; wsum += w
                    }
                    x += step
                }
                y += step
            }
            if (wsum <= 0.0) return NEUTRAL
            val avg = Color((r / wsum).toFloat(), (g / wsum).toFloat(), (b / wsum).toFloat(), 1f)
            val hsv = FloatArray(3)
            avg.toHsv(hsv)
            if (hsv[1] < 0.12f) return NEUTRAL
            return Color().fromHsv(hsv[0], 0.28f, 0.23f).a(1f)
        }
        private val cache = object : LinkedHashMap<String, TextureRegion?>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TextureRegion?>): Boolean {
                if (size <= MAX) return false
                eldest.value?.texture?.dispose()
                return true
            }
        }

        fun load(file: File): TextureRegion? {
            if (!file.isFile) return null
            val key = key(file)
            if (cache.containsKey(key)) return cache[key]
            val r = runCatching {
                val pm = arc.graphics.Pixmap(Core.files.absolute(file.absolutePath))
                tints[key] = runCatching { tintOf(pm) }.getOrDefault(NEUTRAL)
                val t = Texture(pm)
                pm.dispose()
                t.setFilter(Texture.TextureFilter.nearest)
                TextureRegion(t)
            }.onFailure { Log.err("[ImageThumb] ${file.name}", it) }.getOrNull()
            cache[key] = r
            return r
        }
    }
}

