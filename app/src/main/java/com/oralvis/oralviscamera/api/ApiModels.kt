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
    val captureTime: String,

    @SerializedName("mediaId")
    val mediaId: String? = null     // Canonical media ID for deduplication
)

data class MediaMetadataSyncResponse(
    @SerializedName("message")
    val message: String,

    @SerializedName("fileName")
    val fileName: String
)

/**
 * Cloud media metadata returned from GET /patients/{patientId}/media
 */
data class CloudMediaDto(
    @SerializedName("FileName")
    val fileName: String,        // UUID.ext - unique cloud identifier

    @SerializedName("MediaType")
    val mediaType: String,       // "Image" or "Video"

    @SerializedName("CameraMode")
    val cameraMode: String,      // "RGB" or "Fluorescence"

    @SerializedName("S3Url")
    val s3Url: String,           // Full S3 URL

    @SerializedName("CaptureTime")
    val captureTime: String,     // ISO 8601 timestamp

    @SerializedName("DentalArch")
    val dentalArch: String?,     // "Upper" or "Lower"

    @SerializedName("SequenceNumber")
    val sequenceNumber: Int?,    // Sequence number

    @SerializedName("MediaId")
    val mediaId: String? = null  // Canonical media ID (if available from cloud)
)

/**
 * Wrapper response for GET /patients/{patientId}/media
 * API returns: { patientId, count, media: [] }
 */
data class CloudMediaListResponse(
    @SerializedName("patientId")
    val patientId: String,       // Patient ID

    @SerializedName("count")
    val count: Int,              // Number of media items

    @SerializedName("media")
    val media: List<CloudMediaDto> // List of media items
)

/**
 * Request payload for POST /media/download-url
 */
data class MediaDownloadRequest(
    @SerializedName("patientId")
    val patientId: String,        // Patient ID

    @SerializedName("fileName")
    val fileName: String          // UUID.ext filename
)

/**
 * Response from POST /media/download-url
 */
data class MediaDownloadResponse(
    @SerializedName("downloadUrl")
    val downloadUrl: String       // Presigned S3 download URL
)

