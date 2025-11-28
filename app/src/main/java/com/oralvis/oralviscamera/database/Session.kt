package com.oralvis.oralviscamera.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val patientId: Long,
    val createdAt: Date,
    val completedAt: Date? = null,
    val displayName: String? = null,
    val mediaCount: Int = 0
)
