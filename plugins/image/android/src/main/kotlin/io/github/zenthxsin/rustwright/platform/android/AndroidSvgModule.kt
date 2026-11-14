package io.github.zenthxsin.rustwright.platform.android

import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import io.github.zenthxsin.rustwright.core.runtime.RwContext
import io.github.zenthxsin.rustwright.core.runtime.RwModule
import io.github.zenthxsin.rustwright.pic.api.SvgRasterizer
import io.github.zenthxsin.rustwright.pic.imaging.RgbaImage
import io.github.zenthxsin.rustwright.pic.imaging.SvgSanitizer

/** 平台只负责 Canvas 栅格化；文件、版本与工具协议仍属于 pic 模块。 */
object AndroidSvgModule : RwModule {
    override val id = "android.svg"
    override fun register(ctx: RwContext) { ctx.services.provide<SvgRasterizer>(AndroidSvgRasterizer()) }
}

class AndroidSvgRasterizer : SvgRasterizer {
    override fun render(svg: String, longSide: Int): RgbaImage {
        require(longSide in 1..2048)
        val document = SVG.getFromString(SvgSanitizer.sanitize(svg))
        val bounds = requireNotNull(document.documentViewBox) { "SVG 缺少 viewBox" }
        val aspect = bounds.width() / bounds.height()
        require(bounds.width().isFinite() && bounds.height().isFinite() && aspect.isFinite() && aspect > 0f)
        val width = if (aspect >= 1f) longSide else (longSide * aspect).toInt().coerceAtLeast(1)
        val height = if (aspect >= 1f) (longSide / aspect).toInt().coerceAtLeast(1) else longSide
        document.setDocumentWidth("100%")
        document.setDocumentHeight("100%")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            document.renderToCanvas(Canvas(bitmap))
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            return RgbaImage(width, height, pixels)
        } finally { bitmap.recycle() }
    }
}
