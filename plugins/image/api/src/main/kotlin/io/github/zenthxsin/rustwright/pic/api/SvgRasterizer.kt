package io.github.zenthxsin.rustwright.pic.api

import io.github.zenthxsin.rustwright.pic.imaging.RgbaImage

/** Platform renderer; callers sanitize SVG and validate output size before saving. */
fun interface SvgRasterizer {
    fun render(svg: String, longSide: Int): RgbaImage
}
