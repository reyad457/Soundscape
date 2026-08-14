package com.soundscape.audio.dsd

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the DSF container (Sony's "DSD Stream File" format) — a simple,
 * publicly documented chunk header followed by raw uncompressed 1-bit
 * DSD data. This is a from-scratch parser against the published header
 * layout, not a vendored reference decoder: unlike FLAC/ALAC/WavPack/APE,
 * there's no compression algorithm here to get subtly wrong — DSD is
 * raw, and DSF is a header describing how it's laid out. Same category
 * of task as WAV header parsing, not codec vendoring.
 *
 * DSF interleaves audio **by block**, not by sample: each channel
 * contributes one [blockSizePerChannel]-byte block in turn, not one byte
 * at a time. [DsdBlockReader] handles de-interleaving that back into a
 * per-channel bitstream for [DopPacker].
 */
object DsfParser {

    data class DsfInfo(
        val sampleRateHz: Int,       // DSD bit rate, e.g. 2_822_400 for DSD64
        val channelCount: Int,
        val blockSizePerChannel: Int,
        val sampleCountPerChannel: Long,
        val dataChunkOffset: Long,   // absolute byte offset where raw DSD data begins
        val dataChunkSize: Long
    )

    class UnsupportedDsfException(message: String) : Exception(message)

    fun parseHeader(input: InputStream): DsfInfo {
        val header = ByteArray(28)
        readFully(input, header)
        val headerBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        val chunkId = String(header, 0, 4, Charsets.US_ASCII)
        if (chunkId != "DSD ") throw UnsupportedDsfException("Not a DSF file (missing 'DSD ' chunk)")
        headerBuf.position(20)
        val fileSize = headerBuf.long
        val metadataOffset = headerBuf.long

        val fmtHeader = ByteArray(12)
        readFully(input, fmtHeader)
        val fmtId = String(fmtHeader, 0, 4, Charsets.US_ASCII)
        if (fmtId != "fmt ") throw UnsupportedDsfException("Expected 'fmt ' chunk, got '$fmtId'")
        val fmtChunkSize = ByteBuffer.wrap(fmtHeader, 4, 8).order(ByteOrder.LITTLE_ENDIAN).long

        val fmtBody = ByteArray((fmtChunkSize - 12).toInt())
        readFully(input, fmtBody)
        val fmtBuf = ByteBuffer.wrap(fmtBody).order(ByteOrder.LITTLE_ENDIAN)

        val formatVersion = fmtBuf.int
        val formatId = fmtBuf.int
        if (formatId != 0) throw UnsupportedDsfException("Unsupported DSF formatID $formatId (only raw DSD/0 is handled)")
        @Suppress("UNUSED_VARIABLE") val channelType = fmtBuf.int
        val channelCount = fmtBuf.int
        val samplingFrequency = fmtBuf.int
        val bitsPerSample = fmtBuf.int // 1 or 8 — bit ordering within each byte; see DsdBlockReader
        val sampleCountPerChannel = fmtBuf.long
        val blockSizePerChannel = fmtBuf.int

        val dataHeader = ByteArray(12)
        readFully(input, dataHeader)
        val dataId = String(dataHeader, 0, 4, Charsets.US_ASCII)
        if (dataId != "data") throw UnsupportedDsfException("Expected 'data' chunk, got '$dataId'")
        val dataChunkSize = ByteBuffer.wrap(dataHeader, 4, 8).order(ByteOrder.LITTLE_ENDIAN).long

        // 28 (DSD chunk) + 12+fmtBody (fmt chunk) + 12 (data chunk header) = current offset
        val dataOffset = 28L + fmtChunkSize + 12L

        return DsfInfo(
            sampleRateHz = samplingFrequency,
            channelCount = channelCount,
            blockSizePerChannel = blockSizePerChannel,
            sampleCountPerChannel = sampleCountPerChannel,
            dataChunkOffset = dataOffset,
            dataChunkSize = dataChunkSize - 12L
        ).also {
            if (bitsPerSample != 1 && bitsPerSample != 8) {
                throw UnsupportedDsfException("Unexpected DSF bitsPerSample=$bitsPerSample")
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw UnsupportedDsfException("Unexpected end of file while reading DSF header")
            offset += read
        }
    }
}
