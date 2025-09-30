package com.oralvis.oralviscamera.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun getAllSessions(): Flow<List<Session>>

    @Insert
    suspend fun insert(session: Session): Long

    @Delete
    suspend fun delete(session: Session)

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getBySessionId(sessionId: String): Session?
}
