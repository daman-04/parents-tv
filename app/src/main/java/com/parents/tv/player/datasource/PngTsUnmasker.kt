package com.parents.tv.player.datasource

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * Unmasks steganographic PNG segments served by CDN relays (e.g. TikTok CDN)
 * into clean MPEG Transport Stream (.ts) packets starting with sync byte 0x47.
 */
object PngTsUnmasker {
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
    )
    private val TIKTIK_MAGIC = "TIKTIKPX".toByteArray(Charsets.US_ASCII)

    fun isPng(data: ByteArray): Boolean {
        if (data.size < 8) return false
        for (i in 0 until 8) {
            if (data[i] != PNG_MAGIC[i]) return false
        }
        return true
    }

    fun unmask(data: ByteArray): ByteArray? {
        if (!isPng(data)) return null

        var pos = 8
        var width = 0
        var height = 0
        var colorType = 2
        val idatChunks = mutableListOf<ByteArray>()

        while (pos + 8 <= data.size) {
            val length = ((data[pos].toInt() and 0xFF) shl 24) or
                    ((data[pos + 1].toInt() and 0xFF) shl 16) or
                    ((data[pos + 2].toInt() and 0xFF) shl 8) or
                    (data[pos + 3].toInt() and 0xFF)

            if (length < 0) break

            val type0 = data[pos + 4]
            val type1 = data[pos + 5]
            val type2 = data[pos + 6]
            val type3 = data[pos + 7]

            val chunkDataStart = pos + 8
            val nextChunk = chunkDataStart + length + 4 // +4 for CRC

            if (type0 == 'I'.code.toByte() && type1 == 'H'.code.toByte() && type2 == 'D'.code.toByte() && type3 == 'R'.code.toByte()) {
                if (length >= 13 && chunkDataStart + 13 <= data.size) {
                    width = ((data[chunkDataStart].toInt() and 0xFF) shl 24) or
                            ((data[chunkDataStart + 1].toInt() and 0xFF) shl 16) or
                            ((data[chunkDataStart + 2].toInt() and 0xFF) shl 8) or
                            (data[chunkDataStart + 3].toInt() and 0xFF)

                    height = ((data[chunkDataStart + 4].toInt() and 0xFF) shl 24) or
                            ((data[chunkDataStart + 5].toInt() and 0xFF) shl 16) or
                            ((data[chunkDataStart + 6].toInt() and 0xFF) shl 8) or
                            (data[chunkDataStart + 7].toInt() and 0xFF)

                    colorType = data[chunkDataStart + 9].toInt() and 0xFF
                }
            } else if (type0 == 'I'.code.toByte() && type1 == 'D'.code.toByte() && type2 == 'A'.code.toByte() && type3 == 'T'.code.toByte()) {
                if (length > 0 && chunkDataStart + length <= data.size) {
                    val chunk = ByteArray(length)
                    System.arraycopy(data, chunkDataStart, chunk, 0, length)
                    idatChunks.add(chunk)
                }
            } else if (type0 == 'I'.code.toByte() && type1 == 'E'.code.toByte() && type2 == 'N'.code.toByte() && type3 == 'D'.code.toByte()) {
                break
            }

            pos = nextChunk
        }

        if (width <= 0 || height <= 0 || idatChunks.isEmpty()) return null

        val bpp = when (colorType) {
            2 -> 3 // RGB
            6 -> 4 // RGBA
            0 -> 1 // Grayscale
            4 -> 2 // Grayscale + Alpha
            else -> 3
        }

        val stride = width * bpp
        val totalScanlineBytes = height * (stride + 1)

        // Concatenate IDAT parts
        val totalIdatLen = idatChunks.sumOf { it.size }
        val idatBytes = ByteArray(totalIdatLen)
        var offset = 0
        for (chunk in idatChunks) {
            System.arraycopy(chunk, 0, idatBytes, offset, chunk.size)
            offset += chunk.size
        }

        // Decompress zlib
        val decompressed = ByteArray(totalScanlineBytes)
        val inflater = Inflater()
        try {
            inflater.setInput(idatBytes)
            var totalDecompressed = 0
            while (!inflater.finished() && totalDecompressed < decompressed.size) {
                val count = inflater.inflate(decompressed, totalDecompressed, decompressed.size - totalDecompressed)
                if (count == 0 && inflater.needsInput()) break
                totalDecompressed += count
            }
        } catch (e: Exception) {
            return null
        } finally {
            inflater.end()
        }

        // PNG Row Unfiltering (Types 0..4)
        val unmasked = ByteArray(height * stride)
        for (y in 0 until height) {
            val filterType = decompressed[y * (stride + 1)].toInt() and 0xFF
            val rowStart = y * (stride + 1) + 1
            val rowOutOffset = y * stride
            val prevRowOffset = (y - 1) * stride

            for (x in 0 until stride) {
                val raw = decompressed[rowStart + x].toInt() and 0xFF
                val a = if (x >= bpp) unmasked[rowOutOffset + x - bpp].toInt() and 0xFF else 0
                val b = if (y > 0) unmasked[prevRowOffset + x].toInt() and 0xFF else 0
                val c = if (y > 0 && x >= bpp) unmasked[prevRowOffset + x - bpp].toInt() and 0xFF else 0

                val out = when (filterType) {
                    0 -> raw
                    1 -> (raw + a) and 0xFF
                    2 -> (raw + b) and 0xFF
                    3 -> (raw + ((a + b) shr 1)) and 0xFF
                    4 -> {
                        val p = a + b - c
                        val pa = abs(p - a)
                        val pb = abs(p - b)
                        val pc = abs(p - c)
                        val pr = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                        (raw + pr) and 0xFF
                    }
                    else -> raw
                }
                unmasked[rowOutOffset + x] = out.toByte()
            }
        }

        // Check TIKTIKPX magic
        if (unmasked.size < 12) return null
        for (i in 0 until 8) {
            if (unmasked[i] != TIKTIK_MAGIC[i]) return null
        }

        val payloadLen = ((unmasked[8].toInt() and 0xFF) shl 24) or
                ((unmasked[9].toInt() and 0xFF) shl 16) or
                ((unmasked[10].toInt() and 0xFF) shl 8) or
                (unmasked[11].toInt() and 0xFF)

        if (payloadLen <= 0 || 12 + payloadLen > unmasked.size) return null

        // Gunzip payload -> pure MPEG-TS
        return try {
            val bais = ByteArrayInputStream(unmasked, 12, payloadLen)
            GZIPInputStream(bais).use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }
}
