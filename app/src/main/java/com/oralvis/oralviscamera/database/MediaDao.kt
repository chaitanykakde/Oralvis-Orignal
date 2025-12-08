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
    
    /**
     * Get all media records for a specific patient by joining with sessions table.
     * This returns all media from all sessions belonging to the patient.
     */
    @Query("""
        SELECT m.* FROM media m 
        INNER JOIN sessions s ON m.sessionId = s.sessionId 
        WHERE s.patientId = :patientId 
        ORDER BY m.captureTime DESC
    """)
    fun getMediaByPatient(patientId: Long): Flow<List<MediaRecord>>

    /**
     * Get unsynced media count for a specific patient.
     */
    @Query("""
        SELECT COUNT(*) FROM media m 
        INNER JOIN sessions s ON m.sessionId = s.sessionId 
        WHERE s.patientId = :patientId AND m.isSynced = 0
    """)
    suspend fun getUnsyncedMediaCount(patientId: Long): Int

    /**
     * Get all unsynced media records for a specific patient.
     */
    @Query("""
        SELECT m.* FROM media m 
        INNER JOIN sessions s ON m.sessionId = s.sessionId 
        WHERE s.patientId = :patientId AND m.isSynced = 0
        ORDER BY m.captureTime ASC
    """)
    suspend fun getUnsyncedMediaByPatient(patientId: Long): List<MediaRecord>

    /**
     * Update media sync status.
     */
    @Query("""
        UPDATE media 
        SET isSynced = :isSynced, s3Url = :s3Url 
        WHERE id = :mediaId
    """)
    suspend fun updateSyncStatus(mediaId: Long, isSynced: Boolean, s3Url: String?)
}
