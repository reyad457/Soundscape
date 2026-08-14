package com.soundscape.audio.dsd

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses DSDIFF (.dff) — Philips' IFF-style chunk container for DSD,
 * distinct from DSF: big-endian chunk sizes (8 bytes each, IFF64-style),
 * and raw audio data is truly sample-interleaved (one byte per channel,
 * alternating) rather than DSF's block-interleaved layout. Same
 * from-scratch-parser-against-a-public-spec approach as [DsfParser] —
 * no compression algorithm here to vendor.
 *
 * Deliberately unsupported: DST-compressed DSDIFF (`CMPR` chunk reports
 * `DST ` instead of `DSD `). DST is an actual lossless compression
 * codec (used by SACD masters), not just a container detail — decoding
 * it would mean vendoring a real codec, the same category of work as
 * FLAC/ALAC/WavPack/APE, not yet done. Files using it throw
 * [DffParser.UnsupportedDffException] with a clear reason rather than
 * silently producing garbage audio.
 */
object DffParser {

    data class DffInfo(
        val sampleRateHz: Int,
        val channelCount: Int,
        val dataChunkOffset: Long,
        val dataChunkSize: Long
    )

    class UnsupportedDffException(message: String) : Exception(message)

    fun parseHeader(input: InputStream): DffInfo {
        var offset = 0L

        val form = readChunkHeader(input); offset += 12
        if (form.id != "FRM8") throw UnsupportedDffException("Not a DFF file (missing 'FRM8')")
        val formType = readAscii(input, 4); offset += 4
        if (formType != "DSD ") throw UnsupportedDffException("Unexpected FRM8 form type '$formType'")

        var sampleRate = 0
        var channelCount = 0
        var compressionType = "DSD "
        var dataOffset = -1L
        var dataSize = 0L

        val formEnd = offset + (form.size - 4) // -4 for the formType we already consumed

        while (offset < formEnd) {
            val chunk = readChunkHeader(input)
            offset += 12
            val paddedSize = chunk.size + (chunk.size % 2) // chunks are padded to even length

            when (chunk.id) {
                "PROP" -> {
                    val propType = readAscii(input, 4)
                    var consumed = 4L
                    if (propType == "SND ") {
                        while (consumed < chunk.size) {
                            val sub = readChunkHeader(input)
                            consumed += 12
                            when (sub.id) {
                                "FS  " -> {
                                    sampleRate = readInt32BE(input)
                                    consumed += 4
                                }
                                "CHNL" -> {
                                    val chnlBytes = ByteArray(sub.size.toInt())
                                    readFully(input, chnlBytes)
                                    consumed += sub.size
                                    channelCount = ByteBuffer.wrap(chnlBytes, 0, 2)
                                        .order(ByteOrder.BIG_ENDIAN).short.toInt()
                                }
                                "CMPR" -> {
                                    val cmprBytes = ByteArray(sub.size.toInt())
                                    readFully(input, cmprBytes)
                                    consumed += sub.size
                                    compressionType = String(cmprBytes, 0, 4, Charsets.US_ASCII)
                                }
                                else -> {
                                    skip(input, sub.size + (sub.size % 2))
                                    consumed += sub.size + (sub.size % 2)
                                }
                            }
                        }
                    } else {
                        skip(input, chunk.size - 4)
                    }
                }
                "DSD " -> {
                    dataOffset = offset
                    dataSize = chunk.size
                    skip(input, paddedSize)
                }
                else -> skip(input, paddedSize)
            }
            offset += paddedSize
        }

        if (compressionType != "DSD ") {
            throw UnsupportedDffException("DSDIFF file uses '$compressionType' compression (DST or other) — only raw DSD is supported")
        }
        if (dataOffset < 0) throw UnsupportedDffException("No 'DSD ' data chunk found")
        if (sampleRate == 0 || channelCount == 0) throw UnsupportedDffException("Missing FS/CHNL properties")

        return DffInfo(sampleRate, channelCount, dataOffset, dataSize)
    }

    private data class ChunkHeader(val id: String, val size: Long)

    private fun readChunkHeader(input: InputStream): ChunkHeader {
        val id = readAscii(input, 4)
        val sizeBytes = ByteArray(8)
        readFully(input, sizeBytes)
        val size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.BIG_ENDIAN).long
        return ChunkHeader(id, size)
    }

    private fun readAscii(input: InputStream, length: Int): String {
        val bytes = ByteArray(length)
        readFully(input, bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readInt32BE(input: InputStream): Int {
        val bytes = ByteArray(4)
        readFully(input, bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun skip(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw UnsupportedDffException("Unexpected end of file while reading DFF header")
            offset += read
        }
    }
}
