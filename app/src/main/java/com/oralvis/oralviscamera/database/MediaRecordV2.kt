package com.oralvis.oralviscamera.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/**
 * Production-grade media record with canonical ID and state machine.
 * Implements single source of truth design.
 */
@Entity(
    tableName = "media_v2",
    indices = [
        Index(value = ["patientId"], name = "idx_media_v2_patient"),
        Index(value = ["sessionId"], name = "idx_media_v2_session"),
        Index(value = ["state"], name = "idx_media_v2_state"),
        Index(value = ["cloudFileName"], name = "idx_media_v2_cloud_file")
    ]
)
data class MediaRecordV2(
    @PrimaryKey
    val mediaId: String,                    // Canonical UUID - never changes

    val patientId: Long,                    // Patient owns media (required)
    val sessionId: String?,                 // Session provides context (optional)

    val state: MediaState,                  // Explicit state machine

    // File metadata
    val fileName: String,                   // Original filename
    val filePath: String?,                  // Current absolute path (can change)
    val fileSizeBytes: Long?,               // File size for validation
    val checksum: String?,                  // File integrity check (future use)

    // Media properties
    val mediaType: String,                  // "Image" or "Video"
    val mode: String,                       // "Normal" or "Fluorescence"
    val captureTime: Date,                  // Capture timestamp

    // Cloud sync metadata
    val cloudFileName: String?,             // Cloud UUID.ext (set after upload)
    val s3Url: String?,                     // S3 URL (set after upload)
    val uploadedAt: Date?,                  // Upload timestamp

    // Guided capture metadata
    val dentalArch: String?,                // "LOWER"/"UPPER"
    val sequenceNumber: Int?,               // 1,2,3,... per arch
    val guidedSessionId: String?,           // Guided session identifier

    // Timestamps
    val createdAt: Date,                    // Creation timestamp
    val updatedAt: Date                     // Last update timestamp
) {

    /**
     * Check constraints that should be enforced by DB but validated in code.
     */
    fun validate(): Boolean {
        // Required fields
        if (mediaId.isBlank()) return false
        if (mediaType !in listOf("Image", "Video")) return false
        if (mode !in listOf("Normal", "Fluorescence")) return false

        // State-specific validations
        when (state) {
            MediaState.DB_COMMITTED,
            MediaState.UPLOADING,
            MediaState.SYNCED,
            MediaState.DOWNLOADED,
            MediaState.FILE_MISSING -> {
                // These states require filePath for committed media
                if (filePath.isNullOrBlank()) return false
            }
            MediaState.CAPTURED,
            MediaState.FILE_READY,
            MediaState.CORRUPT -> {
                // These states may or may not have filePath
            }
        }

        // Cloud metadata consistency
        if (cloudFileName != null && s3Url == null) return false
        if (s3Url != null && cloudFileName == null) return false
        if (uploadedAt != null && (cloudFileName == null || s3Url == null)) return false

        // Split validation by state - cloud media has different constraints than local media
        return when (state) {
            MediaState.DOWNLOADED -> validateCloudMediaRecord()
            else -> validateLocalMediaRecord()
        }
    }

    /**
     * Validation for cloud-downloaded media (DOWNLOADED state).
     * Cloud media can have dentalArch/sequenceNumber without guidedSessionId.
     */
    private fun validateCloudMediaRecord(): Boolean {
        // Basic field validation
        if (patientId <= 0) return false

        // Cloud metadata is required for downloaded media
        if (cloudFileName.isNullOrBlank()) return false
        if (s3Url.isNullOrBlank()) return false
        if (uploadedAt == null) return false

        // Dental arch and sequence validation (relaxed for cloud media)
        if (dentalArch != null && dentalArch !in listOf("LOWER", "UPPER")) return false
        if (sequenceNumber != null && sequenceNumber < 1) return false

        // Cloud media allows dentalArch + sequenceNumber WITHOUT requiring guidedSessionId
        // This is different from local capture which requires guidedSessionId

        return true
    }

    /**
     * Validation for local-captured media (all states except DOWNLOADED).
     * Local media follows stricter guided capture constraints.
     */
    private fun validateLocalMediaRecord(): Boolean {
        // Dental arch and sequence validation
        if (dentalArch != null && dentalArch !in listOf("LOWER", "UPPER")) return false
        if (sequenceNumber != null && sequenceNumber < 1) return false

        // Local media requires guidedSessionId when dentalArch or sequenceNumber is present
        if ((dentalArch != null || sequenceNumber != null) && guidedSessionId == null) return false

        return true
    }

    /**
     * Create a copy with updated state (validates transition).
     */
    fun withState(newState: MediaState): MediaRecordV2 {
        require(state.canTransitionTo(newState)) {
            "Invalid state transition: $state -> $newState"
        }
        return copy(state = newState, updatedAt = Date())
    }

    /**
     * Create a copy with cloud metadata.
     */
    fun withCloudMetadata(cloudFileName: String, s3Url: String): MediaRecordV2 {
        require(state == MediaState.UPLOADING) {
            "Can only set cloud metadata when UPLOADING, current state: $state"
        }
        return copy(
            cloudFileName = cloudFileName,
            s3Url = s3Url,
            uploadedAt = Date(),
            updatedAt = Date()
        )
    }

    /**
     * Create a copy with file path update.
     */
    fun withFilePath(filePath: String, fileSizeBytes: Long? = null): MediaRecordV2 {
        return copy(
            filePath = filePath,
            fileSizeBytes = fileSizeBytes,
            updatedAt = Date()
        )
    }

    /**
     * Get display name for UI.
     */
    val displayName: String
        get() = fileName

    /**
     * Check if media has been uploaded to cloud.
     */
    val isUploaded: Boolean
        get() = cloudFileName != null && s3Url != null

    /**
     * Check if media is from cloud download.
     */
    val isFromCloud: Boolean
        get() = state == MediaState.DOWNLOADED

    /**
     * Check if media file exists and is readable.
     */
    fun isFileAccessible(): Boolean {
        if (filePath.isNullOrBlank()) return false
        return try {
            val file = java.io.File(filePath)
            file.exists() && file.canRead() && file.length() > 0
        } catch (e: Exception) {
            false
        }
    }
}
