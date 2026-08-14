package com.soundscape.library.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.soundscape.library.model.Track
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY artist, album, discNumber, trackNumber")
    fun observeAll(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: Long): Track?

    @Query("SELECT * FROM tracks WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): Track?

    @Query("SELECT * FROM tracks WHERE sourceId = :sourceId")
    suspend fun getBySource(sourceId: Long): List<Track>

    @Query(
        """
        SELECT * FROM tracks
        WHERE title LIKE '%' || :query || '%'
           OR artist LIKE '%' || :query || '%'
           OR album LIKE '%' || :query || '%'
        ORDER BY artist, album
        """
    )
    fun search(query: String): Flow<List<Track>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(track: Track): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<Track>)

    @Update
    suspend fun update(track: Track)

    @Query("DELETE FROM tracks WHERE sourceUri = :sourceUri")
    suspend fun deleteByUri(sourceUri: String)

    @Query("SELECT sourceUri FROM tracks WHERE sourceId IS :sourceId OR (sourceId IS NULL AND :sourceId IS NULL)")
    suspend fun allUrisForSource(sourceId: Long?): List<String>
}
