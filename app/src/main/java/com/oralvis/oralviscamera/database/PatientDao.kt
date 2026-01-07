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

    @Query("SELECT * FROM patients WHERE id = :patientId LIMIT 1")
    suspend fun getPatientById(patientId: Long): Patient?

    @Query("SELECT * FROM patients WHERE code = :code LIMIT 1")
    suspend fun getPatientByCode(code: String): Patient?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(patient: Patient)

    @androidx.room.Delete
    suspend fun delete(patient: Patient)

    // Cloud-synced patient operations (patients table for sync)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(patients: List<Patient>)

    @Query("DELETE FROM patients")
    suspend fun clearAll()
}

