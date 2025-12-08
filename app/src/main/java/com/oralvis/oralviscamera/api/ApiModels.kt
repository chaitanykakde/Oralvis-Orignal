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

data class MediaMetadataDto(
    @SerializedName("patientId")
    val patientId: String,
    
    @SerializedName("clinicId")
    val clinicId: String,
    
    @SerializedName("fileName")
    val fileName: String,
    
    @SerializedName("s3Url")
    val s3Url: String,
    
    @SerializedName("mediaType")
    val mediaType: String,
    
    @SerializedName("cameraMode")
    val cameraMode: String,
    
    @SerializedName("dentalArch")
    val dentalArch: String?,
    
    @SerializedName("sequenceNumber")
    val sequenceNumber: Int?,
    
    @SerializedName("captureTime")
    val captureTime: String
)

data class MediaMetadataSyncResponse(
    @SerializedName("message")
    val message: String,
    
    @SerializedName("fileName")
    val fileName: String
)

