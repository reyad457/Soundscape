package com.soundscape.app.di

import android.content.Context
import androidx.room.Room
import com.soundscape.audio.playback.PlaybackEngine
import com.soundscape.audio.playback.PlaybackEngineRouter
import com.soundscape.library.data.AppDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "soundscape.db")
            .fallbackToDestructiveMigration() // fine pre-1.0; replace with real migrations before release
            .build()

    @Provides
    fun provideTrackDao(db: AppDatabase) = db.trackDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    @Binds
    @Singleton
    abstract fun bindPlaybackEngine(impl: PlaybackEngineRouter): PlaybackEngine
}
