package io.github.zenthxsin.rustwright.pic.impl

import io.github.zenthxsin.rustwright.pic.imaging.*
import java.nio.ByteBuffer
import java.util.Base64
import kotlinx.serialization.json.*

/** Versioned native-JVM document codec; v1 files remain readable, v2 adds frames and palettes. */
internal object PixelDocumentCodec {
    fun decode(data: ByteArray): LayeredPixelDocument {
        val obj = Json.parseToJsonElement(data.decodeToString()).jsonObject
        val format = obj["format"]?.jsonPrimitive?.content
        require(format in setOf("rustwright-pixel-v1", "rustwright-pixel-v2"))
        val width = obj.getValue("width").jsonPrimitive.int
        val height = obj.getValue("height").jsonPrimitive.int
        val document = LayeredPixelDocument(width, height)
        var totalPixels = 0L
        fun layers(array: JsonArray): List<LayeredPixelDocument.Layer> {
            require(array.size in 1..64)
            totalPixels += array.size.toLong() * width * height
            require(totalPixels <= 4_194_304)
            return array.map { element ->
                val layer = element.jsonObject
                val encoded = layer.getValue("pixels").jsonPrimitive.content
                require(encoded.length <= ((width.toLong() * height * 4 + 2) / 3) * 4 + 4)
                val bytes = Base64.getDecoder().decode(encoded)
                require(bytes.size.toLong() == width.toLong() * height * 4)
                val packed = ByteBuffer.wrap(bytes)
                val gutter =
                    layer["gutter"]
                        ?.takeIf { it != JsonNull }
                        ?.jsonPrimitive
                        ?.content
                        ?.let { text ->
                            totalPixels += width.toLong() * height * 9
                            require(totalPixels <= 4_194_304)
                            require(
                                text.length <= ((width.toLong() * height * 9 * 4 + 2) / 3) * 4 + 4
                            )
                            val bytes = Base64.getDecoder().decode(text)
                            require(bytes.size == width * height * 9 * 4)
                            val buffer = ByteBuffer.wrap(bytes)
                            IntArray(width * height * 9) { buffer.int }
                        }
                LayeredPixelDocument.Layer(
                    layer.getValue("name").jsonPrimitive.content,
                    IntArray(width * height) { packed.int },
                    layer["visible"]?.jsonPrimitive?.boolean ?: true,
                    layer["locked"]?.jsonPrimitive?.boolean ?: false,
                    layer["opacity"]?.jsonPrimitive?.int ?: 255,
                    layer["blend"]?.jsonPrimitive?.content?.let(PixelBlend::valueOf)
                        ?: PixelBlend.NORMAL,
                    gutter,
                )
            }
        }
        val frames =
            if (format == "rustwright-pixel-v1")
                listOf(LayeredPixelDocument.Frame(layers(obj.getValue("layers").jsonArray)))
            else {
                val array = obj.getValue("frames").jsonArray
                require(array.size in 1..256)
                array.map {
                    val f = it.jsonObject
                    LayeredPixelDocument.Frame(
                        layers(f.getValue("layers").jsonArray),
                        f.getValue("duration_ms").jsonPrimitive.int,
                        f["duration_us"]?.jsonPrimitive?.int
                            ?: f.getValue("duration_ms").jsonPrimitive.int * 1000,
                        f["active_layer"]?.jsonPrimitive?.int ?: 0,
                    )
                }
            }
        val current = obj["current_frame"]?.jsonPrimitive?.int ?: 0
        require(current in frames.indices)
        val selection =
            obj["selection"]
                ?.takeIf { it != JsonNull }
                ?.jsonPrimitive
                ?.content
                ?.let { Base64.getDecoder().decode(it) }
                ?.also { require(it.size == width * height) }
                ?.let { bytes -> BooleanArray(bytes.size) { bytes[it].toInt() != 0 } }
        val palettes =
            obj["palettes"]
                ?.jsonArray
                ?.also { require(it.size in 1..32) }
                ?.map { e ->
                    val p = e.jsonObject
                    val colors = p.getValue("colors").jsonArray
                    require(colors.size <= 256)
                    LayeredPixelDocument.Palette(
                        p.getValue("name").jsonPrimitive.content,
                        colors.map { c ->
                            val color = c.jsonObject
                            LayeredPixelDocument.PaletteColor(
                                color.getValue("color").jsonPrimitive.int,
                                color["name"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
                            )
                        },
                    )
                } ?: listOf(LayeredPixelDocument.Palette("Default"))
        document.restore(
            LayeredPixelDocument.Snapshot(
                frames[current].layers,
                obj.getValue("active").jsonPrimitive.int,
                obj.getValue("color").jsonPrimitive.int,
                selection,
                frames,
                current,
                palettes,
                obj["active_palette"]?.jsonPrimitive?.int ?: 0,
                obj["loop_mode"]?.jsonPrimitive?.int ?: 0,
            )
        )
        return document
    }

    fun encode(doc: LayeredPixelDocument): ByteArray {
        val snapshot = doc.snapshot()
        fun layer(l: LayeredPixelDocument.Layer) = buildJsonObject {
            put("name", l.name)
            put("visible", l.visible)
            put("locked", l.locked)
            put("opacity", l.opacity)
            put("blend", l.blend.name)
            l.gutterPixels?.let { pixels ->
                val packed = ByteBuffer.allocate(pixels.size * 4)
                pixels.forEach(packed::putInt)
                put("gutter", Base64.getEncoder().encodeToString(packed.array()))
            }
            val buffer = ByteBuffer.allocate(l.pixels.size * 4)
            l.pixels.forEach(buffer::putInt)
            put("pixels", Base64.getEncoder().encodeToString(buffer.array()))
        }
        return buildJsonObject {
                put("format", "rustwright-pixel-v2")
                put("width", doc.width)
                put("height", doc.height)
                put("loop_mode", snapshot.loopMode)
                put("active", snapshot.active)
                put("color", snapshot.color)
                put("current_frame", snapshot.currentFrame)
                put("active_palette", snapshot.activePalette)
                put(
                    "selection",
                    snapshot.selection?.let { bits ->
                        JsonPrimitive(
                            Base64.getEncoder()
                                .encodeToString(ByteArray(bits.size) { if (bits[it]) 1 else 0 })
                        )
                    } ?: JsonNull,
                )
                putJsonArray("frames") {
                    snapshot.frames.forEach { f ->
                        addJsonObject {
                            put("duration_us", f.durationMicros)
                            put("active_layer", f.activeLayer)
                            put("duration_ms", f.durationMillis)
                            put("layers", JsonArray(f.layers.map(::layer)))
                        }
                    }
                }
                putJsonArray("palettes") {
                    snapshot.palettes.forEach { p ->
                        addJsonObject {
                            put("name", p.name)
                            putJsonArray("colors") {
                                p.colors.forEach { c ->
                                    addJsonObject {
                                        put("color", c.color)
                                        put("name", c.name?.let(::JsonPrimitive) ?: JsonNull)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .toString()
            .encodeToByteArray()
    }
}
