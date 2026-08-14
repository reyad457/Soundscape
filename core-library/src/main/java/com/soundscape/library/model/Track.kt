package com.soundscape.library.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single playable track, whether it lives on local storage, a NAS share,
 * or a Subsonic/WebDAV source. [sourceUri] is the actual playable content URI;
 * [sourceType] tells the playback layer how to resolve/stream it.
 *
 * [fingerprint] is a content-based hash (not path/filename) so metadata,
 * ratings, and manually-attached lyrics survive files being moved or renamed
 * on a NAS share. Computed lazily on first successful scan.
 */
@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    val year: Int?,

    // --- Source & format ---
    val sourceUri: String,
    val sourceType: SourceType,
    val sourceId: Long?,          // FK into NetworkSource table, null for local files
    val format: AudioFormat,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val channelCount: Int?,
    val isDsd: Boolean = false,

    // --- Fidelity / trust signals (Phase 4/7) ---
    val fingerprint: String? = null,
    val suspectedFakeLossless: Boolean = false,
    val measuredFrequencyCutoffHz: Int? = null,

    // --- User data ---
    val rating: Int = 0,          // 0-5 stars
    val playCount: Int = 0,
    val dateAdded: Long,
    val lastPlayed: Long? = null,

    // --- Manual lyric attachment (see LyricAttachment) ---
    val lyricAttachmentId: Long? = null
)

enum class SourceType { LOCAL, SMB, NFS, WEBDAV, FTP, SUBSONIC, PLEX, JELLYFIN }

enum class AudioFormat {
    FLAC, ALAC, WAV, AIFF, APE, WAVPACK, DSF, DFF, MP3, AAC, OGG, OPUS, UNKNOWN
}
