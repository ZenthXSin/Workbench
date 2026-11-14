package io.github.zenthxsin.rustwright.pic.imaging

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class MakapixCodecTest {
    private fun native(name: String = "native-all-codecs.mkpx") = javaClass.getResourceAsStream("/makapix/$name")!!.use { it.readBytes() }
    @Test fun nativeFixtureHasAllFramesLayersBlendNamesMicrosecondsAndSelection() {
        val doc=MakapixCodec.decode(native()); val s=doc.snapshot()
        assertEquals(64,doc.width); assertEquals(2,s.frames.size); assertEquals(2,s.frames[0].layers.size)
        assertEquals(16667,s.frames[0].durationMicros); assertEquals(250001,s.frames[1].durationMicros)
        assertEquals(1,s.frames[0].activeLayer); assertEquals(0,s.frames[1].activeLayer)
        assertEquals(PixelBlend.MULTIPLY,s.frames[0].layers[1].blend)
        assertEquals(123,s.frames[0].layers[1].opacity); assertTrue(s.frames[0].layers[1].locked)
        assertEquals("Cloud",s.palettes.single().colors.first().name)
        assertEquals(2,s.loopMode); assertEquals(1,s.currentFrame)
        assertTrue(s.selection!![2*64+1]); assertEquals(2,s.selection.count { it })
        assertEquals(0x001f3f5f,s.layers[0].pixels[40*64+40])
    }
    @Test fun nativeFilesRoundTripPlainAndCompactWithoutDataLoss() {
        for(name in listOf("native-all-codecs.mkpx","native-gutter.mkpx")) {
            val doc=MakapixCodec.decode(native(name))
            for(compact in listOf(false,true)) {
                val encoded=MakapixCodec.encode(doc,compact)
                val copy=MakapixCodec.decode(encoded)
                assertDocument(doc,copy)
                assertArrayEquals(encoded,MakapixCodec.encode(copy,compact))
                // Cross-language verification runner consumes the plain exports after this test.
                java.io.File("build/makapix-export-${if(compact) "compact-" else ""}$name").writeBytes(encoded)
            }
        }
    }
    @Test fun upstreamMinizCompactFixtureIsInteroperable() {
        assertDocument(MakapixCodec.decode(native()),MakapixCodec.decode(native("native-compact.mkpx")))
    }
    @Test fun gutterPixelsArePreserved() {
        val doc=MakapixCodec.decode(native("native-gutter.mkpx"))
        assertEquals(0x3716212c,doc.snapshot().frames[1].layers[0].gutterPixels!![3*192+2])
    }
    @Test fun crcAndContentHashAreIndependentlyVerified() {
        val corrupted=native(); corrupted[60]=(corrupted[60].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { MakapixCodec.decode(corrupted) }
        val changedHash=native(); changedHash[41]=(changedHash[41].toInt() xor 1).toByte(); repairCrc(changedHash)
        val error=assertThrows(IllegalArgumentException::class.java) { MakapixCodec.decode(changedHash) }
        assertTrue(error.message!!.contains("content hash"))
    }
    @Test fun castagnoliKnownVector() { assertEquals(0xe3069283L,MakapixCodec.crc32c("123456789".toByteArray())) }
    @Test fun compactBombAndTruncationAreRejected() {
        val bytes=MakapixCodec.encode(MakapixCodec.decode(native()))
        val bomb=bytes.copyOf(); repeat(4) { bomb[8+it]=0x7f }
        assertThrows(IllegalArgumentException::class.java) { MakapixCodec.decode(bomb) }
        assertThrows(IllegalArgumentException::class.java) { MakapixCodec.decode(bytes.copyOf(bytes.size-1)) }
        assertThrows(IllegalArgumentException::class.java) { MakapixCodec.decode(bytes+byteArrayOf(0)) }
    }
    @Test fun interruptedWorkCancels() {
        Thread.currentThread().interrupt()
        try { assertThrows(CancellationException::class.java) { MakapixCodec.decode(native()) } }
        finally { Thread.interrupted() }
    }
    @Test fun unsupportedDimensionsAreNotSilentlyResized() {
        assertThrows(IllegalArgumentException::class.java) { MakapixCodec.encode(LayeredPixelDocument(4,4)) }
    }
    @Test fun malformedTileVarintsAndUnknownCodecsAreRejected() {
        val bytes=native(); val payload=chunkPayload(bytes,"TILE")
        val badCodec=bytes.copyOf(); badCodec[payload+1]=9; repairCrc(badCodec)
        assertThrows(IllegalArgumentException::class.java) { MakapixCodec.decode(badCodec) }
        val bomb=bytes.copyOf(); repeat(5) { bomb[payload+it]=0xff.toByte() }; repairCrc(bomb)
        assertThrows(IllegalArgumentException::class.java) { MakapixCodec.decode(bomb) }
    }
    @Test fun frameAllocationIsBoundedBeforeMaterialization() {
        val bytes=native(); val count=chunkPayload(bytes,"HEAD")+14
        repeat(4) { bytes[count+it]=0x7f }; repairCrc(bytes)
        val error=assertThrows(IllegalArgumentException::class.java) { MakapixCodec.decode(bytes) }
        assertTrue(error.message!!.contains("256 frames"))
    }
    @Test fun offCanvasSelectionFailsRatherThanDiscarding() {
        val bytes=native(); val sel=chunkPayload(bytes,"SELC")
        bytes[sel+1]=0; bytes[sel+2]=0; repairCrc(bytes)
        val error=assertThrows(IllegalArgumentException::class.java) { MakapixCodec.decode(bytes) }
        assertTrue(error.message!!.contains("off-canvas selection"))
    }
    @Test fun unsupportedAncillaryDataFailsRatherThanDiscarding() {
        val bytes=native(); val tag=chunkPayload(bytes,"UPCN")-9
        "META".toByteArray().copyInto(bytes,tag); repairCrc(bytes)
        val error=assertThrows(IllegalArgumentException::class.java) { MakapixCodec.decode(bytes) }
        assertTrue(error.message!!.contains("unsupported chunk META"))
    }
    private fun chunkPayload(bytes: ByteArray, tag: String): Int {
        var p=8
        while(p<bytes.size-13) {
            val name=bytes.copyOfRange(p,p+4).toString(Charsets.US_ASCII)
            if(name==tag) return p+9
            val size=(0..3).fold(0) { n,i -> n or ((bytes[p+5+i].toInt() and 255) shl (8*i)) }
            p+=9+size
        }
        error("No chunk $tag")
    }
    private fun repairCrc(bytes: ByteArray) { val crc=MakapixCodec.crc32c(bytes,bytes.size-13); repeat(4) { bytes[bytes.size-4+it]=(crc ushr(it*8)).toByte() } }
    private fun assertDocument(a: LayeredPixelDocument,b: LayeredPixelDocument) {
        val x=a.snapshot(); val y=b.snapshot()
        assertEquals(x.currentFrame,y.currentFrame); assertEquals(x.loopMode,y.loopMode); assertEquals(x.palettes,y.palettes)
        assertArrayEquals(x.selection,y.selection)
        x.frames.zip(y.frames).forEach { (f,g) ->
            assertEquals(f.durationMicros,g.durationMicros); assertEquals(f.activeLayer,g.activeLayer)
            f.layers.zip(g.layers).forEach { (l,m) ->
                assertEquals(l.name,m.name); assertEquals(l.visible,m.visible); assertEquals(l.locked,m.locked); assertEquals(l.opacity,m.opacity); assertEquals(l.blend,m.blend)
                assertArrayEquals(l.pixels,m.pixels); assertArrayEquals(l.gutterPixels,m.gutterPixels)
            }
        }
    }
}
