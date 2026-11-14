package io.github.zenthxsin.rustwright.pic.imaging

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** 最小 PNG 编码器：RGBA 8 位、无交错、每行 None 滤波。纯 JVM，不依赖 AWT。 */
object PngEncoder {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun encode(image: RgbaImage, level: Int = 6): ByteArray {
        val w = image.width
        val h = image.height
        require(w > 0 && h > 0) { "空图像不能编码" }
        val raw = ByteArray(h * (1 + w * 4))
        var i = 0
        for (y in 0 until h) {
            raw[i++] = 0
            for (x in 0 until w) {
                val c = image.pixels[y * w + x]
                raw[i++] = (c shr 16).toByte()
                raw[i++] = (c shr 8).toByte()
                raw[i++] = c.toByte()
                raw[i++] = (c ushr 24).toByte()
            }
        }
        val deflater = Deflater(level)
        val compressed = ByteArrayOutputStream().also { out ->
            deflater.setInput(raw)
            deflater.finish()
            val buf = ByteArray(64 * 1024)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            deflater.end()
        }.toByteArray()

        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(w); writeInt(h); writeByte(8); writeByte(6); writeByte(0); writeByte(0); writeByte(0) } }.toByteArray()
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", compressed)
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        d.write(typeBytes)
        d.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        d.writeInt(crc.value.toInt())
    }
}
