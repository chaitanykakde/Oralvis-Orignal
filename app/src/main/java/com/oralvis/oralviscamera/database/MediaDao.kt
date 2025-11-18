package com.oralvis.oralviscamera.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media WHERE sessionId = :sessionId ORDER BY captureTime DESC")
    fun getMediaBySession(sessionId: String): Flow<List<MediaRecord>>

    @Query("SELECT * FROM media WHERE sessionId = :sessionId AND mode = :mode ORDER BY captureTime DESC")
    fun getMediaBySessionAndMode(sessionId: String, mode: String): Flow<List<MediaRecord>>

    @Query("SELECT * FROM media WHERE sessionId = :sessionId AND mediaType = :mediaType ORDER BY captureTime DESC")
    fun getMediaBySessionAndType(sessionId: String, mediaType: String): Flow<List<MediaRecord>>

    @Insert
    suspend fun insertMedia(mediaRecord: MediaRecord): Long

    @Delete
    suspend fun deleteMedia(mediaRecord: MediaRecord)

    @Query("DELETE FROM media WHERE id = :mediaId")
    suspend fun deleteMediaById(mediaId: Long)

    @Query("DELETE FROM media WHERE sessionId = :sessionId")
    suspend fun deleteMediaBySession(sessionId: String)

    @Query("SELECT COUNT(*) FROM media WHERE sessionId = :sessionId")
    suspend fun getMediaCountBySession(sessionId: String): Int

    @Query("SELECT * FROM media ORDER BY captureTime DESC")
    fun getAllMedia(): Flow<List<MediaRecord>>
}
