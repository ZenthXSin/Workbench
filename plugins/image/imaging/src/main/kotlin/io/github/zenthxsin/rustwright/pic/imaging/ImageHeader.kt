package io.github.zenthxsin.rustwright.pic.imaging

/**
 * Header-only bounds check before native decoders allocate memory. Unsupported formats fail closed.
 */
object ImageHeader {
    fun dimensions(data: ByteArray): Pair<Int, Int> {
        fun u(i: Int): Int {
            require(i in data.indices) { "Truncated image header" }
            return data[i].toInt() and 255
        }
        fun be16(i: Int) = (u(i) shl 8) or u(i + 1)
        fun le24(i: Int) = u(i) or (u(i + 1) shl 8) or (u(i + 2) shl 16)
        fun be32(i: Int) = (u(i) shl 24) or (u(i + 1) shl 16) or (u(i + 2) shl 8) or u(i + 3)
        fun ascii(i: Int, n: Int): String {
            require(i + n <= data.size)
            return data.copyOfRange(i, i + n).decodeToString()
        }
        if (data.size >= 24 && data.take(8) == listOf<Byte>(-119, 80, 78, 71, 13, 10, 26, 10)) {
            require(ascii(12, 4) == "IHDR")
            return be32(16) to be32(20)
        }
        if (data.size >= 4 && u(0) == 255 && u(1) == 216) {
            var i = 2
            while (i < data.size) {
                require(u(i++) == 255) { "Invalid JPEG marker" }
                while (u(i) == 255) i++
                val marker = u(i++)
                if (marker == 217 || marker == 218) break
                if (marker == 1 || marker in 208..215) continue
                val length = be16(i)
                require(length >= 2 && i.toLong() + length <= data.size)
                if (
                    marker in setOf(192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207)
                ) {
                    require(length >= 8)
                    return be16(i + 5) to be16(i + 3)
                }
                i += length
            }
            error("JPEG frame dimensions not found")
        }
        if (data.size >= 30 && ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP") {
            return when (ascii(12, 4)) {
                "VP8X" -> (le24(24) + 1) to (le24(27) + 1)
                "VP8L" -> {
                    require(u(20) == 47)
                    val bits =
                        u(21).toLong() or
                            (u(22).toLong() shl 8) or
                            (u(23).toLong() shl 16) or
                            (u(24).toLong() shl 24)
                    ((bits and 0x3fff) + 1).toInt() to (((bits ushr 14) and 0x3fff) + 1).toInt()
                }
                "VP8 " -> {
                    require(u(23) == 157 && u(24) == 1 && u(25) == 42)
                    (u(26) or (u(27) shl 8) and 0x3fff) to (u(28) or (u(29) shl 8) and 0x3fff)
                }
                else -> error("Unsupported WebP header")
            }
        }
        error("Only PNG, JPEG and WebP raster images are supported")
    }
}
