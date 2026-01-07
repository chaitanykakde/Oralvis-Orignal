package com.oralvis.oralviscamera.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * API surface mapped to concrete AWS API Gateway endpoints.
 *
 * NOTE: Patient endpoints use the legacy BASE_URL (NO /prod prefix):
 *  - GET /patients - fetch all patients for clinic
 *  - POST /patients - upsert patient
 *  - Clinic registration: ApiClient.API_CLINIC_REGISTRATION_ENDPOINT
 *  - Media metadata sync: ApiClient.API_MEDIA_SYNC_ENDPOINT (when used)
 *
 * Media endpoints are handled separately by CloudMediaSyncManager using /prod API Gateway.
 */
interface ApiService {

    @POST
    suspend fun registerClinic(
        @Url url: String,
        @Body request: ClinicRegistrationRequest
    ): Response<ClinicRegistrationResponse>

    @GET("patients")
    suspend fun getPatients(
        @Header("ClinicId") clinicId: String
    ): Response<List<PatientDto>>

    @POST("patients")
    suspend fun upsertPatient(
        @Header("ClinicId") clinicId: String,
        @Body request: PatientDto
    ): Response<PatientDto>

    /**
     * Sync media metadata to DynamoDB via Lambda.
     * Note: File must be uploaded to S3 first, then this endpoint saves metadata.
     */
    @POST
    suspend fun syncMediaMetadata(
        @Url url: String,
        @Header("ClinicId") clinicId: String,
        @Body request: MediaMetadataDto
    ): Response<MediaMetadataSyncResponse>

    /**
     * Get cloud media list for a specific patient.
     * Returns metadata for all media files stored in cloud for this patient.
     * Response format: { patientId, count, media: [] }
     */
    @GET("patients/{patientId}/media")
    suspend fun getPatientMedia(
        @Path("patientId") patientId: String,
        @Header("ClinicId") clinicId: String
    ): Response<CloudMediaListResponse>

    /**
     * Get presigned download URL for a specific media file.
     * Returns a temporary download URL for accessing the file from S3.
     */
    @POST("media/download-url")
    suspend fun getMediaDownloadUrl(
        @Header("ClinicId") clinicId: String,
        @Body request: MediaDownloadRequest
    ): Response<MediaDownloadResponse>
}


