package com.oralvis.oralviscamera.api

import com.google.gson.annotations.SerializedName

data class ClinicRegistrationRequest(
    @SerializedName("clinicName")
    val clinicName: String
)

data class ClinicRegistrationResponse(
    @SerializedName("clinicId")
    val clinicId: String
)

data class PatientDto(
    @SerializedName("patientId")
    val patientId: String,
    
    @SerializedName("name")
    val name: String,
    
    @SerializedName("age")
    val age: Int,
    
    @SerializedName("phoneNumber")
    val phoneNumber: String
)

