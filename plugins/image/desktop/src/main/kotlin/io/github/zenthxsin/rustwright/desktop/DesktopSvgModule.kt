package io.github.zenthxsin.rustwright.desktop

import io.github.zenthxsin.rustwright.core.runtime.RwContext
import io.github.zenthxsin.rustwright.core.runtime.RwModule
import io.github.zenthxsin.rustwright.pic.api.SvgRasterizer
import io.github.zenthxsin.rustwright.pic.imaging.RgbaImage
import io.github.zenthxsin.rustwright.pic.imaging.SvgSanitizer
import org.apache.batik.bridge.UserAgent
import org.apache.batik.bridge.UserAgentAdapter
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.ImageTranscoder
import org.apache.batik.util.ParsedURL
import java.awt.image.BufferedImage
import java.io.StringReader

object DesktopSvgModule : RwModule {
    override val id = "desktop.svg"
    override fun register(ctx: RwContext) { ctx.services.provide<SvgRasterizer>(DesktopSvgRasterizer()) }
}

/** Desktop adapter also runs headless for geometry/alpha regression tests. */
class DesktopSvgRasterizer : SvgRasterizer {
    override fun render(svg: String, longSide: Int): RgbaImage {
        require(longSide in 1..2048)
        val clean = SvgSanitizer.sanitize(svg)
        // The sanitizer always serializes attributes with double quotes.
        val viewBox = requireNotNull(Regex("\\sviewBox=\"([^\"]+)\"").find(clean)) { "SVG 缺少 viewBox" }
            .groupValues[1].trim().split(Regex("[\\s,]+")).map(String::toDouble)
        require(viewBox.size == 4 && viewBox.all(Double::isFinite) && viewBox[2] > 0 && viewBox[3] > 0)
        val aspect = viewBox[2] / viewBox[3]
        require(aspect.isFinite() && aspect > 0)
        val width = if (aspect >= 1) longSide else (longSide * aspect).toInt().coerceAtLeast(1)
        val height = if (aspect >= 1) (longSide / aspect).toInt().coerceAtLeast(1) else longSide
        var result: RgbaImage? = null
        val transcoder = object : ImageTranscoder() {
            override fun createUserAgent(): UserAgent = object : UserAgentAdapter() {
                override fun checkLoadExternalResource(resourceURL: ParsedURL?, docURL: ParsedURL?) {
                    throw SecurityException("SVG external resources are disabled")
                }
                override fun checkLoadScript(scriptType: String?, scriptURL: ParsedURL?, docURL: ParsedURL?) {
                    throw SecurityException("SVG scripts are disabled")
                }
            }
            override fun createImage(w: Int, h: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            override fun writeImage(image: BufferedImage, output: TranscoderOutput?) {
                result = RgbaImage(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
                image.flush()
            }
        }
        transcoder.addTranscodingHint(ImageTranscoder.KEY_WIDTH, width.toFloat())
        transcoder.addTranscodingHint(ImageTranscoder.KEY_HEIGHT, height.toFloat())
        transcoder.transcode(TranscoderInput(StringReader(clean)), TranscoderOutput())
        return checkNotNull(result)
    }
}
