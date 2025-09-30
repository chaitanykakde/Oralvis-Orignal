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
    val filePath: String
)
