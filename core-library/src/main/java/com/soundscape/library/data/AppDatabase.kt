package com.soundscape.library.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.soundscape.library.model.LyricAttachment
import com.soundscape.library.model.NetworkSource
import com.soundscape.library.model.Track

@Database(
    entities = [Track::class, NetworkSource::class, LyricAttachment::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
}
