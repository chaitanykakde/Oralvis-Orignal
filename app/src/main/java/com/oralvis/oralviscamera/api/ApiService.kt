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
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * API surface mapped to concrete AWS API Gateway endpoints.
 *
 * NOTE: We use @Url so each call can hit its own full HTTPS endpoint:
 *  - Clinic registration: ApiClient.API_CLINIC_REGISTRATION_ENDPOINT
 *  - Patient sync/search: ApiClient.API_PATIENT_SYNC_ENDPOINT
 *  - Media metadata sync: ApiClient.API_MEDIA_SYNC_ENDPOINT (when used)
 */
interface ApiService {

    @POST
    suspend fun registerClinic(
        @Url url: String,
        @Body request: ClinicRegistrationRequest
    ): Response<ClinicRegistrationResponse>

    @POST
    suspend fun upsertPatient(
        @Url url: String,
        @Header("ClinicId") clinicId: String,
        @Body request: PatientDto
    ): Response<PatientDto>

    @GET
    suspend fun searchPatients(
        @Url url: String,
        @Header("ClinicId") clinicId: String,
        @Query("name") name: String?
    ): Response<List<PatientDto>>

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
}


