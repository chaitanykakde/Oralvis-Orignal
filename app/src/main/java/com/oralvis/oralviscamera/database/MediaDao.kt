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
     * Get all media records for a specific patient.
     * Supports both session media (via session join) and non-session media (direct patientId).
     * This returns all media belonging to the patient from all sources.
     */
    @Query("""
        SELECT m.* FROM media m
        LEFT JOIN sessions s ON m.sessionId = s.sessionId
        WHERE
            (s.patientId = :patientId)
            OR
            (m.patientId = :patientId AND m.sessionId IS NULL)
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

    /**
     * Update media with cloud metadata after successful upload.
     */
    @Query("""
        UPDATE media
        SET isSynced = 1, cloudFileName = :cloudFileName, s3Url = :s3Url
        WHERE id = :mediaId
    """)
    suspend fun updateCloudMetadata(mediaId: Long, cloudFileName: String, s3Url: String?)

    /**
     * Reassign all media from one patient to another (used for duplicate repair).
     */
    @Query("UPDATE media SET patientId = :newPatientId WHERE patientId = :oldPatientId")
    fun reassignMediaToPatient(oldPatientId: Long, newPatientId: Long)

    /**
     * Cloud media sync methods - CRITICAL FOR DEDUPLICATION.
     */

    /**
     * Find media by cloud file name for deduplication.
     * This is the GLOBAL UNIQUE IDENTIFIER - prevents duplicates.
     */
    @Query("SELECT * FROM media WHERE cloudFileName = :cloudFileName LIMIT 1")
    suspend fun getMediaByCloudFileName(cloudFileName: String): MediaRecord?

    /**
     * Insert media record only if it doesn't already exist.
     * Used for safe cloud media insertion - ignores duplicates.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMediaIfNotExists(mediaRecord: MediaRecord): Long

    /**
     * Get all local media that can be uploaded to cloud.
     * Only LOCAL source media that hasn't been synced yet.
     * CRITICAL: This prevents re-uploading already synced media.
     */
    @Query("""
        SELECT m.* FROM media m
        INNER JOIN sessions s ON m.sessionId = s.sessionId
        WHERE s.patientId = :patientId
        AND m.source = 'LOCAL'
        AND m.isSynced = 0
        ORDER BY m.captureTime ASC
    """)
    suspend fun getUploadableMediaByPatient(patientId: Long): List<MediaRecord>
}
