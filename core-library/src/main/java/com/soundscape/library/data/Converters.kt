package com.soundscape.library.data

import androidx.room.TypeConverter
import com.soundscape.library.model.AudioFormat
import com.soundscape.library.model.LyricFormat
import com.soundscape.library.model.SourceType

class Converters {
    @TypeConverter
    fun sourceTypeToString(value: SourceType): String = value.name

    @TypeConverter
    fun stringToSourceType(value: String): SourceType = SourceType.valueOf(value)

    @TypeConverter
    fun audioFormatToString(value: AudioFormat): String = value.name

    @TypeConverter
    fun stringToAudioFormat(value: String): AudioFormat = AudioFormat.valueOf(value)

    @TypeConverter
    fun lyricFormatToString(value: LyricFormat?): String? = value?.name

    @TypeConverter
    fun stringToLyricFormat(value: String?): LyricFormat? = value?.let { LyricFormat.valueOf(it) }
}
