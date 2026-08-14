package com.soundscape.audio.playback

import android.content.Context
import android.net.Uri
import com.soundscape.audio.dsd.DffParser
import com.soundscape.audio.dsd.DopPacker
import com.soundscape.audio.dsd.DsfParser
import com.soundscape.library.model.AudioFormat
import kotlinx.coroutines.channels.ProducerScope
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * DSD playback via DoP (DSD-over-PCM) — no decompression involved (DSD
 * is already raw), so this is container parsing ([DsfParser]/[DffParser])
 * plus reframing ([DopPacker]), all pure Kotlin, no native code needed.
 * Genuinely different shape of problem from the FLAC/ALAC/WavPack/APE
 * decoders: there's no codec to vendor here.
 *
 * Emits [PcmDecoder.DecodedChunk]s like every other decoder in this app,
 * but the bytes are DoP-framed Int32 PCM, not real audio samples in the
 * normal sense — [AAudioExclusiveEngine] opens the native stream via
 * `AAudioBridge.openDopStream` (I32 format, no float/Int16 branching)
 * rather than the generic `openStream` path other formats use, since
 * DoP's marker bytes must survive bit-exact — see [DopPacker]'s kdoc
 * for the real-hardware-verification caveat that still applies here.
 *
 * [startPositionMs] gives real seek here too, and it's simpler than
 * FLAC/WavPack/APE's decoder-API seeks: since DSD is raw (no frames to
 * decode), seeking is just skipping the right number of bytes from the
 * data chunk's start, block/round-aligned to DSF/DFF's respective
 * interleaving so a seek never lands mid-block. See [decodeDsf]/[decodeDff].
 */
class DsdDopDecoder(private val context: Context) {

    private val blockSizeBytes = 4096 // matches DSF's typical native block size; used for DFF chunking too

    suspend fun decode(
        uri: Uri,
        format: AudioFormat,
        scope: ProducerScope<PcmDecoder.DecodedChunk>,
        startPositionMs: Long = 0,
        onFormatKnown: (outputSampleRateHz: Int, channelCount: Int) -> Unit
    ) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Could not open fd for $uri")

        pfd.use { descriptor ->
            val input = BufferedInputStream(java.io.FileInputStream(descriptor.fileDescriptor), 1 shl 16)

            when (format) {
                AudioFormat.DSF -> decodeDsf(input, scope, startPositionMs, onFormatKnown)
                AudioFormat.DFF -> decodeDff(input, scope, startPositionMs, onFormatKnown)
                else -> throw IllegalArgumentException("DsdDopDecoder called with non-DSD format $format")
            }
        }
    }

    private suspend fun decodeDsf(
        input: InputStream,
        scope: ProducerScope<PcmDecoder.DecodedChunk>,
        startPositionMs: Long,
        onFormatKnown: (Int, Int) -> Unit
    ) {
        val info = DsfParser.parseHeader(input)
        onFormatKnown(info.sampleRateHz / 16, info.channelCount)

        val packer = DopPacker(info.channelCount)
        val blockSize = info.blockSizePerChannel
        var bytesRemaining = info.dataChunkSize

        if (startPositionMs > 0) {
            // Target DSD bit position -> bytes-per-channel -> block-aligned
            // (DSF's block-interleaved layout means we can only seek to a
            // whole block-group boundary, not an arbitrary byte).
            val targetBitsPerChannel = (startPositionMs / 1000.0) * info.sampleRateHz
            val targetBytePerChannel = (targetBitsPerChannel / 8).toLong()
            val blockIndex = targetBytePerChannel / blockSize
            val skipBytes = blockIndex * blockSize * info.channelCount
            skipFully(input, skipBytes)
            bytesRemaining -= skipBytes
        }

        while (bytesRemaining > 0 && scope.isActive) {
            val thisBlockSize = minOf(blockSize.toLong(), bytesRemaining).toInt()
            val channelBlocks = Array(info.channelCount) { ByteArray(thisBlockSize) }

            // DSF is block-interleaved: each channel's full block comes in turn, not byte-by-byte.
            for (c in 0 until info.channelCount) {
                readFullyOrThrow(input, channelBlocks[c])
            }
            bytesRemaining -= thisBlockSize.toLong() * info.channelCount

            val packed = packer.packToInt32(channelBlocks)
            val frameCount = packed.size / info.channelCount
            scope.send(PcmDecoder.DecodedChunk(packer.toBytes(packed), frameCount))
        }
    }

    private suspend fun decodeDff(
        input: InputStream,
        scope: ProducerScope<PcmDecoder.DecodedChunk>,
        startPositionMs: Long,
        onFormatKnown: (Int, Int) -> Unit
    ) {
        val info = DffParser.parseHeader(input)
        onFormatKnown(info.sampleRateHz / 16, info.channelCount)

        val packer = DopPacker(info.channelCount)
        // DFF is sample-interleaved (one byte per channel, alternating) — read a
        // round of channelCount bytes at a time and de-interleave into per-channel arrays.
        val roundBytes = blockSizeBytes - (blockSizeBytes % info.channelCount)
        var bytesRemaining = info.dataChunkSize

        if (startPositionMs > 0) {
            val targetBitsPerChannel = (startPositionMs / 1000.0) * info.sampleRateHz
            var targetBytePerChannel = (targetBitsPerChannel / 8).toLong()
            targetBytePerChannel -= targetBytePerChannel % 2 // even-align: DoP pairs 2 bytes per word
            val skipBytes = targetBytePerChannel * info.channelCount
            skipFully(input, skipBytes)
            bytesRemaining -= skipBytes
        }

        while (bytesRemaining > 0 && scope.isActive) {
            val thisRound = minOf(roundBytes.toLong(), bytesRemaining).toInt()
            val interleaved = ByteArray(thisRound)
            readFullyOrThrow(input, interleaved)
            bytesRemaining -= thisRound.toLong()

            val perChannel = thisRound / info.channelCount
            val channelBlocks = Array(info.channelCount) { ByteArray(perChannel) }
            for (i in 0 until perChannel) {
                for (c in 0 until info.channelCount) {
                    channelBlocks[c][i] = interleaved[i * info.channelCount + c]
                }
            }

            val packed = packer.packToInt32(channelBlocks)
            val frameCount = packed.size / info.channelCount
            scope.send(PcmDecoder.DecodedChunk(packer.toBytes(packed), frameCount))
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) break // EOF reached while skipping — leaves stream at end, next read will just produce nothing
            remaining -= skipped
        }
    }

    private fun readFullyOrThrow(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) {
                // Short final block — pad with silence-equivalent (0x69, DSD's
                // "flat line" idle pattern) rather than throwing away partial audio.
                buffer.fill(0x69.toByte(), offset, buffer.size)
                return
            }
            offset += read
        }
    }
}
