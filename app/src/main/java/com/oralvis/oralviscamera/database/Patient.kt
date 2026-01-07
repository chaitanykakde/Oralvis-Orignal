package com.oralvis.oralviscamera.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "patients",
    indices = [Index(value = ["code"], unique = true, name = "idx_patients_code")]
)
data class Patient(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String = "",
    val firstName: String,
    val lastName: String,
    val title: String?,
    val gender: String?,
    val age: Int?,
    val isPregnant: Boolean = false,
    val diagnosis: String?,
    val appointmentTime: String?,
    val checkInStatus: String? = null,
    val phone: String?,
    val email: String?,
    val otp: String?,
    val mobile: String?,
    val dob: String?,
    val addressLine1: String?,
    val addressLine2: String?,
    val area: String?,
    val city: String?,
    val pincode: String?,
    val createdAt: Date = Date()
) {
    val displayName: String
        get() = listOfNotNull(title, firstName, lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
}

