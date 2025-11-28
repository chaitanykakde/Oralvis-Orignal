package com.oralvis.oralviscamera.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun getAllSessions(): Flow<List<Session>>
    
    @Query("SELECT * FROM sessions WHERE patientId = :patientId ORDER BY createdAt DESC")
    fun getSessionsByPatient(patientId: Long): Flow<List<Session>>
    
    @Query("SELECT * FROM sessions WHERE patientId = :patientId ORDER BY createdAt DESC")
    suspend fun getSessionsByPatientOnce(patientId: Long): List<Session>

    @Insert
    suspend fun insert(session: Session): Long
    
    @Update
    suspend fun update(session: Session)

    @Delete
    suspend fun delete(session: Session)

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getBySessionId(sessionId: String): Session?
    
    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Session?
}
