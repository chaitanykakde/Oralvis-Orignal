package com.oralvis.oralviscamera.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.Date

/**
 * DAO for production-grade media management with canonical IDs and state machine.
 */
@Dao
interface MediaDaoV2 {

    /**
     * Insert new media record.
     */
    @Insert
    suspend fun insertMediaV2(mediaRecord: MediaRecordV2): Long

    /**
     * Get media by canonical ID.
     */
    @Query("SELECT * FROM media_v2 WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getMediaById(mediaId: String): MediaRecordV2?

    /**
     * Update media state.
     */
    @Query("UPDATE media_v2 SET state = :state, updatedAt = :updatedAt WHERE mediaId = :mediaId")
    suspend fun updateMediaState(mediaId: String, state: MediaState, updatedAt: Date)

    /**
     * Update media with cloud metadata after successful upload.
     */
    @Query("""
        UPDATE media_v2
        SET cloudFileName = :cloudFileName, s3Url = :s3Url, uploadedAt = :uploadedAt,
            state = 'SYNCED', updatedAt = :updatedAt
        WHERE mediaId = :mediaId AND state = 'UPLOADING'
    """)
    suspend fun updateMediaWithCloudMetadata(
        mediaId: String,
        cloudFileName: String,
        s3Url: String,
        uploadedAt: Date,
        updatedAt: Date
    )

    /**
     * Get all visible media for a patient (for gallery display).
     * Only shows media in states that should be visible to user.
     */
    @Query("""
        SELECT * FROM media_v2
        WHERE patientId = :patientId
        AND state IN ('DB_COMMITTED', 'SYNCED', 'DOWNLOADED', 'FILE_MISSING')
        ORDER BY captureTime DESC
    """)
    fun getVisibleMediaForPatient(patientId: Long): Flow<List<MediaRecordV2>>

    /**
     * Get media ready for upload to cloud.
     * Only LOCAL media that is committed but not yet synced.
     */
    @Query("""
        SELECT * FROM media_v2
        WHERE patientId = :patientId
        AND state = 'DB_COMMITTED'
        ORDER BY captureTime ASC
    """)
    suspend fun getUploadableMedia(patientId: Long): List<MediaRecordV2>

    /**
     * Get all media for a patient (including invisible states).
     * Used for sync reconciliation and debugging.
     */
    @Query("SELECT * FROM media_v2 WHERE patientId = :patientId ORDER BY captureTime DESC")
    fun getAllMediaForPatient(patientId: Long): Flow<List<MediaRecordV2>>

    /**
     * Check if media exists by canonical ID (for deduplication).
     */
    @Query("SELECT COUNT(*) > 0 FROM media_v2 WHERE mediaId = :mediaId")
    suspend fun mediaExists(mediaId: String): Boolean

    /**
     * Get media by cloud filename (for cloud sync deduplication).
     */
    @Query("SELECT * FROM media_v2 WHERE cloudFileName = :cloudFileName LIMIT 1")
    suspend fun getMediaByCloudFileName(cloudFileName: String): MediaRecordV2?

    /**
     * Delete media by canonical ID.
     */
    @Query("DELETE FROM media_v2 WHERE mediaId = :mediaId")
    suspend fun deleteMediaById(mediaId: String)

    /**
     * Update file path for media (when file is moved or recovered).
     */
    @Query("""
        UPDATE media_v2
        SET filePath = :filePath, fileSizeBytes = :fileSizeBytes, updatedAt = :updatedAt
        WHERE mediaId = :mediaId
    """)
    suspend fun updateFilePath(mediaId: String, filePath: String, fileSizeBytes: Long?, updatedAt: Date)

    /**
     * Get media that needs file recovery (FILE_MISSING state).
     */
    @Query("SELECT * FROM media_v2 WHERE state = 'FILE_MISSING' ORDER BY updatedAt ASC")
    suspend fun getMediaNeedingRecovery(): List<MediaRecordV2>

    /**
     * Get upload statistics for a patient.
     */
    @Query("""
        SELECT
            COUNT(CASE WHEN state = 'DB_COMMITTED' THEN 1 END) as readyToUpload,
            COUNT(CASE WHEN state = 'UPLOADING' THEN 1 END) as uploading,
            COUNT(CASE WHEN state = 'SYNCED' THEN 1 END) as synced
        FROM media_v2
        WHERE patientId = :patientId
    """)
    suspend fun getUploadStats(patientId: Long): UploadStats

    /**
     * Clean up orphaned temp files (implementation detail).
     * This would be called during app startup.
     */
    @Query("SELECT COUNT(*) FROM media_v2")
    suspend fun getTotalMediaCount(): Int

    /**
     * Get media created within a time range (for cleanup/debugging).
     */
    @Query("""
        SELECT * FROM media_v2
        WHERE createdAt >= :startTime AND createdAt <= :endTime
        ORDER BY createdAt DESC
    """)
    suspend fun getMediaInTimeRange(startTime: Date, endTime: Date): List<MediaRecordV2>
}

/**
 * Upload statistics data class.
 */
data class UploadStats(
    val readyToUpload: Int,
    val uploading: Int,
    val synced: Int
) {
    val total: Int get() = readyToUpload + uploading + synced
}
