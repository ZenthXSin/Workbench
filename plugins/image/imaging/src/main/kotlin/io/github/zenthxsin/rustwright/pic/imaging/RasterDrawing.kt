package io.github.zenthxsin.rustwright.pic.imaging

/** One-shot raster drawing shares the same exact action semantics as layered sprite documents. */
object RasterDrawing {
    fun apply(
        source: RgbaImage,
        actions: List<LayeredPixelDocument.Action>,
        checkpoint: () -> Unit = {},
    ): RgbaImage {
        val document = LayeredPixelDocument(source.width, source.height)
        document.restore(
            LayeredPixelDocument.Snapshot(
                listOf(LayeredPixelDocument.Layer("Image", source.pixels)),
                0,
                Color.BLACK,
                null,
            )
        )
        document.apply(actions, checkpoint)
        return document.composite()
    }
}
