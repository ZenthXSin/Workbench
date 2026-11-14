/*
 * Makapix v10 format adapter, derived from Makapix engine io.rs / buffer.rs / util.rs.
 * Upstream revision e2d427f881dd561aae9d6091af7d54ca4bb264a6, Apache-2.0.
 * See src/test/resources/makapix/NOTICE and LICENSE for attribution and license.
 */
package io.github.zenthxsin.rustwright.pic.imaging

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Native .mkpx v10 plain/compact codec. Unsupported editor states fail explicitly, never flatten. */
object MakapixCodec {
    private val signature = byteArrayOf(0x89.toByte(), 77, 75, 80, 88, 13, 10, 26)
    private val compactSignature = signature.copyOf().also { it[4] = 90 }
    private const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_PIXELS = 4_194_304L
    private const val MAX_TILES = 8192
    private fun checkpoint() { if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Makapix cancelled") }
    private class TileKey(val pixels: IntArray) {
        override fun hashCode() = pixels.contentHashCode()
        override fun equals(other: Any?) = other is TileKey && pixels.contentEquals(other.pixels)
    }
    private fun invalid(message: String): Nothing = throw IllegalArgumentException("Makapix: $message")
    private fun checkThat(value: Boolean, message: String) { if (!value) invalid(message) }

    private class Reader(val bytes: ByteArray) {
        var pos = 0
        val remaining get() = bytes.size - pos
        fun u8(): Int { checkThat(remaining > 0, "truncated input"); return bytes[pos++].toInt() and 255 }
        fun u16() = u8() or (u8() shl 8)
        fun u32(): Long = (0..3).fold(0L) { v, i -> v or (u8().toLong() shl (i * 8)) }
        fun take(n: Int): ByteArray { checkThat(n >= 0 && n <= remaining, "truncated field"); return bytes.copyOfRange(pos, pos + n).also { pos += n } }
        fun varint(): Int {
            var n = 0L
            for (i in 0..4) {
                val b = u8(); n = n or ((b and 127).toLong() shl (i * 7))
                if (b < 128) {
                    checkThat((i == 0 || b != 0) && n <= Int.MAX_VALUE, "nonminimal or oversized varint")
                    return n.toInt()
                }
            }
            invalid("varint overflow")
        }
        fun str(): String { val n = varint(); checkThat(n <= 4096, "string too long"); return take(n).toString(Charsets.UTF_8) }
        fun rgba(): Int { val r = u8(); val g = u8(); val b = u8(); val a = u8(); return (a shl 24) or (r shl 16) or (g shl 8) or b }
        fun end() = checkThat(remaining == 0, "unexpected trailing field bytes")
    }
    private class Writer {
        val out = ByteArrayOutputStream()
        fun bytes(b: ByteArray) { out.write(b); checkThat(out.size() <= MAX_BYTES, "encoded input exceeds 32 MiB") }
        fun u8(n: Int) { out.write(n) }
        fun u16(n: Int) { u8(n); u8(n ushr 8) }
        fun u32(n: Long) { repeat(4) { u8((n ushr (it * 8)).toInt()) } }
        fun varint(value: Int) { var n = value; do { val b = n and 127; n = n ushr 7; u8(b or if (n != 0) 128 else 0) } while (n != 0) }
        fun str(s: String) { val b = s.toByteArray(); checkThat(b.size <= 4096, "string too long"); varint(b.size); bytes(b) }
        fun rgba(c: Int) { u8(c ushr 16); u8(c ushr 8); u8(c); u8(c ushr 24) }
        fun result() = out.toByteArray()
    }
    /** Android API 26 compatible Castagnoli CRC; does not use java.util.zip.CRC32C. */
    private val crcTable = IntArray(256) { value ->
        var c = value
        repeat(8) { c = (c ushr 1) xor if (c and 1 != 0) 0x82f63b78.toInt() else 0 }
        c
    }
    internal fun crc32c(bytes: ByteArray, length: Int = bytes.size): Long {
        var crc = -1
        repeat(length) { i ->
            if (i % 4096 == 0) checkpoint()
            crc = (crc ushr 8) xor crcTable[(crc xor bytes[i].toInt()) and 255]
        }
        return crc.inv().toLong() and 0xffffffffL
    }
    private class Hash {
        var a = 0xcbf29ce484222325UL.toLong()
        var b = (0x84222325cbf29ce4UL xor 0x9e3779b97f4a7c15UL).toLong()
        var n = 0L
        fun write(bytes: ByteArray) { bytes.forEach { byte ->
            val v = byte.toLong() and 255
            a = (a xor v) * 0x100000001b3L
            b = (b xor (v + n * 0x9e3779b9L)) * 0x100000001b3L
            n++
        } }
        fun u32(value: Int) = write(Writer().apply { u32(value.toLong()) }.result())
        fun result(): ByteArray = Writer().apply {
            repeat(8) { u8((b ushr (it * 8)).toInt()) }
            repeat(8) { u8((a ushr (it * 8)).toInt()) }
        }.result()
    }
    private fun tileBytes(tile: IntArray) = Writer().apply { tile.forEach(::rgba) }.result()
    private fun pixelHash(w: Int, h: Int, grid: IntArray, tiles: List<IntArray>): ByteArray = Hash().apply {
        u32(w); u32(h)
        grid.forEachIndexed { i, index ->
            write(byteArrayOf(if (index == 0) 0 else 1))
            if (index != 0) { u32(i); write(tileBytes(tiles[index - 1])) }
        }
    }.result()
    private fun layerHash(layer: LayeredPixelDocument.Layer, pixels: ByteArray) = Hash().apply {
        write(layer.name.toByteArray()); write(byteArrayOf(if (layer.visible) 1 else 0, if (layer.locked) 1 else 0, layer.opacity.toByte()))
        if (layer.blend.ordinal != 0) write(byteArrayOf(layer.blend.ordinal.toByte()))
        write(pixels)
    }.result()

    private fun unwrap(input: ByteArray): ByteArray {
        checkThat(input.size in 8..MAX_BYTES, "file size exceeds limit or is truncated")
        if (input.copyOfRange(0, 8).contentEquals(signature)) return input
        checkThat(input.copyOfRange(0, 8).contentEquals(compactSignature), "unsupported signature (only v10 supported)")
        val r = Reader(input); r.take(8); val size = r.u32()
        checkThat(size in 21..MAX_BYTES.toLong(), "compact inflated size exceeds limit")
        val inflater = Inflater()
        try {
            inflater.setInput(input, 12, input.size - 12)
            val output = ByteArray(size.toInt()); var filled = 0
            while (filled < output.size && !inflater.finished()) {
                checkpoint()
                val n = inflater.inflate(output, filled, minOf(8192, output.size - filled))
                checkThat(n > 0, "truncated or stalled DEFLATE stream"); filled += n
            }
            // A full output buffer must not conceal extra decompressed bytes.
            if (!inflater.finished()) checkThat(inflater.inflate(ByteArray(1)) == 0, "DEFLATE exceeds declared length")
            checkThat(inflater.finished() && filled == output.size && inflater.remaining == 0, "DEFLATE length or trailing bytes mismatch")
            checkThat(output.copyOfRange(0, 8).contentEquals(signature), "compact payload is not plain v10")
            return output
        } catch (e: java.util.zip.DataFormatException) { invalid("corrupt DEFLATE: ${e.message}") }
        finally { inflater.end() }
    }
    private fun wrap(plain: ByteArray): ByteArray {
        val d = Deflater(6)
        try {
            d.setInput(plain); d.finish()
            val out = Writer(); out.bytes(compactSignature); out.u32(plain.size.toLong())
            val buf = ByteArray(8192)
            while (!d.finished()) { checkpoint(); val count = d.deflate(buf); checkThat(count > 0, "DEFLATE stalled"); out.bytes(buf.copyOf(count)) }
            return out.result()
        } finally { d.end() }
    }
    private fun decodeTile(r: Reader): IntArray {
        checkpoint()
        val result = IntArray(1024)
        when (r.u8()) {
            0 -> repeat(1024) { result[it] = r.rgba() }
            1 -> { var i = 0; while (i < 1024) { val run = r.varint(); checkThat(run in 1..(1024-i), "invalid pixel run"); val c = r.rgba(); result.fill(c, i, i+run); i += run } }
            2 -> {
                val n = r.u8() + 1; val palette = IntArray(n) { r.rgba() }; val bits = if (n == 1) 0 else 32 - Integer.numberOfLeadingZeros(n-1)
                val packed = r.take(128 * bits)
                repeat(1024) { i ->
                    var index = 0
                    repeat(bits) { k -> val bit = i * bits + k; index = (index shl 1) or ((packed[bit / 8].toInt() ushr (7-bit%8)) and 1) }
                    checkThat(index < n, "invalid indexed color"); result[i] = palette[index]
                }
            }
            else -> invalid("unknown tile codec")
        }
        return result
    }
    private fun encodeTile(tile: IntArray): ByteArray {
        checkpoint()
        val raw = Writer().apply { u8(0); tile.forEach(::rgba) }.result()
        val rle = Writer().apply {
            u8(1); var i = 0
            while (i < 1024) { var end = i+1; while (end < 1024 && tile[end] == tile[i]) end++; varint(end-i); rgba(tile[i]); i=end }
        }.result()
        val colors = linkedMapOf<Int, Int>(); tile.forEach { if (it !in colors) colors[it] = colors.size }
        val indexed = if (colors.size <= 256) Writer().apply {
            u8(2); u8(colors.size-1); colors.keys.forEach(::rgba)
            val bits = if (colors.size == 1) 0 else 32-Integer.numberOfLeadingZeros(colors.size-1)
            val packed = ByteArray(128 * bits)
            tile.forEachIndexed { i,c -> repeat(bits) { k ->
                val bit = i*bits+k; val v = (colors.getValue(c) ushr (bits-k-1)) and 1
                packed[bit/8] = (packed[bit/8].toInt() or (v shl (7-bit%8))).toByte()
            } }
            bytes(packed)
        }.result() else null
        return listOfNotNull(raw, rle, indexed).minBy { it.size }
    }

    fun decode(bytes: ByteArray): LayeredPixelDocument {
        checkpoint()
        val plain = unwrap(bytes)
        checkThat(plain.size >= 21, "truncated plain file")
        val bodyEnd = plain.size - 13
        val trailer = Reader(plain.copyOfRange(bodyEnd, plain.size))
        checkThat(trailer.take(4).toString(Charsets.US_ASCII) == "INTG" && trailer.u8() == 1 && trailer.u32() == 4L, "missing integrity trailer")
        checkThat(trailer.u32() == crc32c(plain, bodyEnd), "CRC32C mismatch")
        val r = Reader(plain); r.take(8)
        val chunks = linkedMapOf<String, ByteArray>()
        while (r.pos < bodyEnd) {
            checkThat(bodyEnd-r.pos >= 9, "truncated chunk")
            val tag = r.take(4).toString(Charsets.US_ASCII); val flags = r.u8(); val len = r.u32()
            checkThat(flags in 0..1 && len <= bodyEnd-r.pos, "invalid chunk framing")
            checkThat(chunks.isNotEmpty() || tag == "HEAD", "HEAD must be first")
            checkThat(tag !in chunks, "duplicate $tag")
            checkThat(tag in setOf("HEAD", "TILE", "FRMS", "UPAL", "UPCN", "SELC"), "unsupported chunk $tag; cannot preserve its data")
            checkThat(flags == if (tag in setOf("HEAD", "TILE", "FRMS")) 1 else 0, "wrong critical flag")
            chunks[tag] = r.take(len.toInt())
        }
        fun chunk(tag: String) = Reader(chunks[tag] ?: invalid("missing $tag"))
        val h = chunk("HEAD"); checkThat(h.u16() == 10, "unsupported version")
        val w = h.u16(); val height = h.u16(); checkThat(w in 8..256 && height in 8..256, "canvas out of v10 range")
        checkThat(h.u16() == w && h.u16() == height && h.u16() == w && h.u16() == height, "unsupported gutter geometry")
        val frameCount = h.u32(); checkThat(frameCount in 1..256, "editor supports at most 256 frames")
        val activeFrame = h.u16(); val activePalette = h.u16(); val loop = h.u8(); checkThat(loop in 0..2, "unknown loop mode")
        val flags = h.u8(); checkThat(flags in 0..1, "unsupported HEAD flags")
        val expectedHash = h.take(16); h.end()
        val tr = chunk("TILE"); val tileCount = tr.varint(); checkThat(tileCount <= MAX_TILES && tileCount <= tr.remaining/2, "tile dictionary exceeds budget")
        val tiles = List(tileCount) { decodeTile(tr) }; tr.end()
        val sw = w*3; val sh = height*3; val tx = (sw+31)/32; val ty = (sh+31)/32; val cells = tx*ty
        val fr = chunk("FRMS"); checkThat(fr.varint().toLong() == frameCount, "frame count mismatch")
        val docHash = Hash().apply { u32(w); u32(height) }; var pixels = 0L
        val frames = List(frameCount.toInt()) {
            checkpoint()
            fr.u32(); val micros = fr.u32(); checkThat(micros in 16667..1_000_000L, "unsupported frame duration")
            val active = fr.u16(); val layerCount = fr.u16(); checkThat(layerCount in 1..64, "layer count")
            pixels += layerCount.toLong()*w*height; checkThat(pixels <= MAX_PIXELS, "document exceeds editor pixel budget")
            val fh = Hash().apply { u32(micros.toInt()) }
            val layers = List(layerCount) {
                checkpoint()
                fr.u32(); val name = fr.str(); checkName(name)
                val lf = fr.u8(); checkThat(lf in 0..3, "unknown layer flags")
                val opacity = fr.u8(); val blend = fr.u8(); checkThat(blend in PixelBlend.entries.indices, "unsupported blend mode")
                val grid = IntArray(cells); var filled = 0
                while (filled < cells) { val run = fr.varint(); val index = fr.varint(); checkThat(run in 1..(cells-filled) && index <= tileCount, "invalid tile references"); grid.fill(index, filled, filled+run); filled += run }
                val data = IntArray(w*height)
                var gutter: IntArray? = null
                grid.forEachIndexed { cell,index -> if (index != 0) {
                    val tile = tiles[index-1]
                    repeat(1024) { local ->
                        val x = (cell%tx)*32+local%32; val y = (cell/tx)*32+local/32; val color = tile[local]
                        if (x in w until 2*w && y in height until 2*height) data[(y-height)*w+x-w] = color
                        else if (color != 0) {
                            checkThat(x < sw && y < sh, "nonzero storage-external edge pixels")
                            if (gutter == null) {
                                pixels += sw.toLong()*sh
                                checkThat(pixels <= MAX_PIXELS, "document with gutter exceeds pixel budget")
                                gutter = IntArray(sw*sh)
                            }
                            gutter!![y*sw+x] = color
                        }
                    }
                } }
                LayeredPixelDocument.Layer(name, data, lf and 1 != 0, lf and 2 != 0, opacity, PixelBlend.entries[blend], gutterPixels=gutter).also {
                    fh.write(layerHash(it, pixelHash(sw,sh,grid,tiles)))
                }
            }
            checkThat(active < layerCount, "active layer out of range")
            docHash.write(fh.result())
            LayeredPixelDocument.Frame(layers, micros.toInt()/1000, micros.toInt(), active)
        }; fr.end()
        checkThat(docHash.result().contentEquals(expectedHash), "content hash mismatch")
        val palettes = chunks["UPAL"]?.let { pl ->
            val pr = Reader(pl); val count = pr.varint(); checkThat(count in 1..32, "unsupported palette count")
            List(count) { val name=pr.str(); checkName(name); val n=pr.u16(); checkThat(n<=256, "editor supports 256 palette colors"); LayeredPixelDocument.Palette(name,List(n) { LayeredPixelDocument.PaletteColor(pr.rgba()) }) }.also { pr.end() }
        } ?: listOf(defaultPalette())
        val namedPalettes = chunks["UPCN"]?.let { bytes ->
            val nr = Reader(bytes); checkThat(nr.varint() == palettes.size, "palette names count mismatch")
            palettes.map { p -> checkThat(nr.u16() == p.colors.size, "color names count mismatch"); p.copy(colors=p.colors.map { c -> val name=nr.str(); checkThat(name.length<=64, "palette color name too long"); c.copy(name=name.takeIf { it.isNotEmpty() }) }) }.also { nr.end() }
        } ?: palettes
        val selection = chunks["SELC"]?.let { decodeSelection(it,w,height) }
        checkThat((flags and 1 != 0) == (selection != null), "selection flag mismatch")
        checkThat(activeFrame < frames.size && (activePalette == 65535 || activePalette < palettes.size), "active index out of range")
        return LayeredPixelDocument(w,height).apply { restore(LayeredPixelDocument.Snapshot(
            layers=frames[activeFrame].layers, active=frames[activeFrame].activeLayer, color=0xff000000.toInt(), selection=selection,
            frames=frames,currentFrame=activeFrame,palettes=namedPalettes,activePalette=if(activePalette==65535) 0 else activePalette,loopMode=loop,
        )) }
    }
    private fun checkName(name: String) = checkThat(name.isNotBlank() && name.length <= 64, "editor name limit (1..64 characters)")
    private fun defaultPalette() = LayeredPixelDocument.Palette("Default", listOf(
        0xff140c1c.toInt(),0xff442434.toInt(),0xff30346d.toInt(),0xff4e4a4e.toInt(),0xff854c30.toInt(),0xff346524.toInt(),0xffd04648.toInt(),0xff757161.toInt(),0xff597dce.toInt(),0xffd27d2c.toInt(),0xff8595a1.toInt(),0xff6daa2c.toInt(),0xffd2aa99.toInt(),0xff6dc2ca.toInt(),0xffdad45e.toInt(),0xffdeeed6.toInt()
    ).map { LayeredPixelDocument.PaletteColor(it) })
    private fun decodeSelection(bytes: ByteArray,w: Int,h: Int): BooleanArray {
        val r = Reader(bytes); val tag = r.u8(); val out = BooleanArray(w*h)
        if (tag == 2) { r.end(); return out }
        checkThat(tag in 0..1, "unknown selection codec")
        val x=r.u16(); val y=r.u16(); val bw=r.u16(); val bh=r.u16()
        checkThat(bw>0 && bh>0 && x.toLong()+bw<=w*3 && y.toLong()+bh<=h*3, "selection bounds")
        val bits = if (tag == 1) r.take((bw*bh+7)/8) else null
        repeat(bw*bh) { i -> if (bits == null || (bits[i/8].toInt() and (1 shl (i%8))) != 0) {
            val px=x+i%bw; val py=y+i/bw
            checkThat(px in w until w*2 && py in h until h*2, "off-canvas selection cannot be preserved")
            out[(py-h)*w+px-w]=true
        } }; r.end(); return out
    }

    fun encode(document: LayeredPixelDocument, compact: Boolean = true): ByteArray {
        checkpoint()
        val w=document.width; val h=document.height
        checkThat(w in 8..256 && h in 8..256, "v10 canvas must be 8..256 per axis")
        val s=document.snapshot(); val frames=s.frames.ifEmpty { listOf(LayeredPixelDocument.Frame(s.layers)) }
        val sw=w*3; val sh=h*3; val tx=(sw+31)/32; val cells=tx*((sh+31)/32)
        val tiles=mutableListOf<IntArray>(); val lookup=HashMap<TileKey,Int>(); val grids=mutableListOf<IntArray>()
        val dh=Hash().apply { u32(w); u32(h) }
        frames.forEach { frame ->
            checkpoint()
            checkThat(frame.durationMicros in 16667..1_000_000, "v10 frame duration must be 16667..1000000 microseconds")
            val fh=Hash().apply { u32(frame.durationMicros) }
            frame.layers.forEach { layer ->
                checkpoint()
                val grid=IntArray(cells)
                repeat(cells) { cell ->
                    checkpoint()
                    val tile=IntArray(1024)
                    repeat(1024) { local -> val x=cell%tx*32+local%32-w; val y=cell/tx*32+local/32-h; if(x in 0 until w && y in 0 until h) tile[local]=layer.pixels[y*w+x] else if (x+w in 0 until sw && y+h in 0 until sh) tile[local]=layer.gutterPixels?.get((y+h)*sw+x+w) ?: 0 }
                    if(tile.any { it != 0 }) {
                        grid[cell]=lookup.getOrPut(TileKey(tile)) { checkThat(tiles.size<MAX_TILES,"tile budget"); tiles+=tile; tiles.size }
                    }
                }
                grids+=grid; fh.write(layerHash(layer,pixelHash(sw,sh,grid,tiles)))
            }; dh.write(fh.result())
        }
        val head=Writer().apply {
            u16(10); u16(w); u16(h); u16(w); u16(h); u16(w); u16(h); u32(frames.size.toLong())
            u16(s.currentFrame); u16(s.activePalette); u8(s.loopMode); u8(if(s.selection!=null) 1 else 0); bytes(dh.result())
        }.result()
        val tile=Writer().apply { varint(tiles.size); tiles.forEach { bytes(encodeTile(it)) } }.result()
        val fr=Writer().apply {
            varint(frames.size); var nextGrid=0; var lid=0L
            frames.forEachIndexed { i,f ->
                u32(i.toLong()); u32(f.durationMicros.toLong()); u16(if(i==s.currentFrame) s.active else f.activeLayer); u16(f.layers.size)
                f.layers.forEach { l ->
                    u32(lid++); str(l.name); u8((if(l.visible) 1 else 0) or (if(l.locked) 2 else 0)); u8(l.opacity); u8(l.blend.ordinal)
                    val grid=grids[nextGrid++]; var start=0
                    while(start<cells) { var end=start+1; while(end<cells && grid[end]==grid[start]) end++; varint(end-start); varint(grid[start]); start=end }
                }
            }
        }.result()
        val out=Writer().apply { bytes(signature) }
        fun chunk(tag: String,critical: Boolean,payload: ByteArray) { out.bytes(tag.toByteArray()); out.u8(if(critical) 1 else 0); out.u32(payload.size.toLong()); out.bytes(payload) }
        chunk("HEAD",true,head); chunk("TILE",true,tile); chunk("FRMS",true,fr)
        chunk("UPAL",false,Writer().apply { varint(s.palettes.size); s.palettes.forEach { p -> str(p.name); u16(p.colors.size); p.colors.forEach { rgba(it.color) } } }.result())
        if(s.palettes.any { p -> p.colors.any { it.name!=null } }) chunk("UPCN",false,Writer().apply { varint(s.palettes.size); s.palettes.forEach { p -> u16(p.colors.size); p.colors.forEach { str(it.name.orEmpty()) } } }.result())
        s.selection?.let { selection -> chunk("SELC",false,Writer().apply {
            if(selection.none { it }) u8(2) else {
                u8(1); u16(w); u16(h); u16(w); u16(h)
                val bits=ByteArray((w*h+7)/8); selection.forEachIndexed { i,v -> if(v) bits[i/8]=(bits[i/8].toInt() or (1 shl(i%8))).toByte() }; bytes(bits)
            }
        }.result()) }
        chunk("INTG",true,Writer().apply { u32(crc32c(out.result())) }.result())
        val plain=out.result()
        // Decoder validation is also an encode-time semantic and bounds guard.
        decode(plain)
        return if(compact) wrap(plain) else plain
    }
}
