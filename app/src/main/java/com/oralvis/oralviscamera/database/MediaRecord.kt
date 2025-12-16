package com.oralvis.oralviscamera.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "media")
data class MediaRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val fileName: String,
    val mode: String, // "Normal" or "Fluorescence"
    val mediaType: String, // "Image" or "Video"
    val captureTime: Date,
    val filePath: String,
    val isSynced: Boolean = false, // Track if media has been synced to cloud
    val s3Url: String? = null, // S3 URL after successful upload

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
