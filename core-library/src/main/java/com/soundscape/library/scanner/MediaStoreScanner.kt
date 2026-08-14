package com.soundscape.library.scanner

import android.content.Context
import android.provider.MediaStore
import com.soundscape.library.model.AudioFormat
import com.soundscape.library.model.SourceType
import com.soundscape.library.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Phase 0 scanner: indexes locally-stored audio via MediaStore.
 *
 * This is intentionally the *simple* path — it gets a usable library fast.
 * It does NOT attempt bit-perfect format detection (that needs a real
 * container parse, done later by the format-specific decoders in Phase 2).
 * sampleRateHz/bitDepth are left null here and backfilled once a track is
 * actually opened by the decoding layer.
 */
class MediaStoreScanner @Inject constructor(
    private val context: Context
) {
    suspend fun scanLocal(): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DISC_NUMBER,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.ARTIST} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumArtistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val discCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISC_NUMBER)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val contentUri = "${MediaStore.Audio.Media.EXTERNAL_CONTENT_URI}/$id"

                tracks += Track(
                    title = cursor.getString(titleCol) ?: "Unknown",
                    artist = cursor.getString(artistCol) ?: "Unknown Artist",
                    album = cursor.getString(albumCol) ?: "Unknown Album",
                    albumArtist = cursor.getString(albumArtistCol),
                    trackNumber = cursor.getIntOrNull(trackCol),
                    discNumber = cursor.getIntOrNull(discCol),
                    durationMs = cursor.getLong(durationCol),
                    year = cursor.getIntOrNull(yearCol),
                    sourceUri = contentUri,
                    sourceType = SourceType.LOCAL,
                    sourceId = null,
                    format = formatFromMime(cursor.getString(mimeCol)),
                    sampleRateHz = null,
                    bitDepth = null,
                    channelCount = null,
                    dateAdded = cursor.getLong(dateAddedCol) * 1000L
                )
            }
        }

        tracks
    }

    private fun formatFromMime(mime: String?): AudioFormat = when (mime) {
        "audio/flac", "audio/x-flac" -> AudioFormat.FLAC
        "audio/alac" -> AudioFormat.ALAC
        "audio/x-wavpack", "audio/wavpack" -> AudioFormat.WAVPACK
        "audio/x-wav", "audio/wav" -> AudioFormat.WAV
        "audio/mpeg" -> AudioFormat.MP3
        "audio/mp4", "audio/aac" -> AudioFormat.AAC
        "audio/ogg" -> AudioFormat.OGG
        "audio/opus" -> AudioFormat.OPUS
        else -> AudioFormat.UNKNOWN
    }
    // Caveat (Phase 2 ALAC note): MediaStore's own MIME sniffing commonly
    // reports .m4a files as "audio/mp4" regardless of whether the codec
    // inside is AAC or ALAC — it doesn't parse the stsd atom the way
    // MediaExtractor's per-track format does. Until this scanner is
    // taught to peek at the container the way AlacNativeDecoder already
    // does at play-time, some ALAC files will show up as AudioFormat.AAC
    // here and get routed to the MediaCodec fallback path instead of the
    // native ALAC decoder. Real fix: open each "audio/mp4" candidate with
    // MediaExtractor during scan and check its actual track MIME — costs
    // scan time, worth doing once Phase 2 needs to be reliable end to end.

    private fun android.database.Cursor.getIntOrNull(col: Int): Int? =
        if (isNull(col)) null else getInt(col)
}
