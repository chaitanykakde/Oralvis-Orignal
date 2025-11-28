package com.oralvis.oralviscamera.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PatientDao {
    @Query("SELECT * FROM patients ORDER BY createdAt DESC")
    fun observePatients(): Flow<List<Patient>>

    @Query("SELECT * FROM patients ORDER BY createdAt DESC")
    suspend fun getPatientsOnce(): List<Patient>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(patient: Patient)
}

