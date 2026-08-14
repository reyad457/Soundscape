package com.soundscape.library.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class LyricFormat { LRC, SRT, TTML, ASS }

/**
 * A manually-attached lyric/subtitle file, keyed to a track's [Track.fingerprint]
 * rather than its file path — so the attachment survives the track being
 * renamed or moved (e.g. reorganized on a NAS share).
 */
@Entity(tableName = "lyric_attachments")
data class LyricAttachment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val trackFingerprint: String,
    val fileUri: String,
    val format: LyricFormat,

    /** User-adjustable sync offset in milliseconds, positive = lyrics shown later. */
    val timingOffsetMs: Int = 0,

    val attachedAt: Long
)
