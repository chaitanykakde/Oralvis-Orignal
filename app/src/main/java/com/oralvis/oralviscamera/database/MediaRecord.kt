package com.oralvis.oralviscamera.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "media"
)
data class MediaRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String?,
    val fileName: String,
    val mode: String, // "Normal" or "Fluorescence"
    val mediaType: String, // "Image" or "Video"
    val captureTime: Date,
    val filePath: String,
    val isSynced: Boolean = false, // Track if media has been synced to cloud
    val s3Url: String? = null, // S3 URL after successful upload

    /**
     * Patient association for cloud media.
     * For LOCAL media: can be derived from session → patient join
     * For CLOUD media: must be stored directly since no session exists
     */
    val patientId: Long? = null, // Direct patient association for cloud media

    /**
     * Cloud sync metadata for deduplication and source tracking.
     *
     * cloudFileName:
     *  - For LOCAL media: set after upload (UUID.ext from cloud)
     *  - For CLOUD media: DynamoDB FileName (UUID.ext) - used as unique cloud identity
     *
     * source:
     *  - "LOCAL": captured on this device, may be uploaded
     *  - "CLOUD": downloaded from cloud, never uploaded
     *
     * isFromCloud:
     *  - true: downloaded from cloud (source of truth)
     *  - false: captured locally
     */
    val cloudFileName: String? = null, // DynamoDB FileName (UUID.ext) - GLOBAL UNIQUE ID
    val source: String = "LOCAL", // "LOCAL" or "CLOUD"
    val isFromCloud: Boolean = false, // true if downloaded from cloud

    /**
     * Optional metadata used by guided auto-capture.
     *
     * dentalArch:
     *  - "LOWER" for lower-arch guided captures
     *  - "UPPER" for upper-arch guided captures
     *  - null for legacy/manual captures
     *
     * sequenceNumber:
     *  - 1,2,3,... per arch within a guided session
     *  - null for legacy/manual captures
     *
     * guidedSessionId:
     *  - identifier used to group captures belonging to the same guided run
     *  - null for legacy/manual captures
     */
    val dentalArch: String? = null,
    val sequenceNumber: Int? = null,
    val guidedSessionId: String? = null
)
