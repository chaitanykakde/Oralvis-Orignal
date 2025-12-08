package com.oralvis.oralviscamera.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    /**
     * Cloud configuration – taken directly from system architecture / AWS setup.
     *
     * S3 bucket structure for media:
     * s3://oralvis-media/{GlobalPatientId}/{ClinicId}/{FileName}
     */
    const val AWS_REGION = "ap-south-1"
    const val S3_BUCKET_NAME = "oralvis-media"
    
    // AWS Credentials - Configure these in your local.properties or BuildConfig
    // For security, these should not be committed to version control
    // Example: Add to local.properties: aws.access.key=YOUR_KEY and aws.secret.key=YOUR_SECRET
    // Then read using: BuildConfig.AWS_ACCESS_KEY and BuildConfig.AWS_SECRET_KEY
    // For now, these need to be configured separately
    const val AWS_ACCESS_KEY = "" // TODO: Configure AWS credentials securely
    const val AWS_SECRET_KEY = "" // TODO: Configure AWS credentials securely

    // API Gateway endpoints
    const val API_MEDIA_SYNC_ENDPOINT =
        "https://ocki7ui6wa.execute-api.ap-south-1.amazonaws.com/default/SyncMediaMetadata"
    const val API_PATIENT_SYNC_ENDPOINT =
        "https://te2fzjde7j.execute-api.ap-south-1.amazonaws.com/patients"
    const val API_CLINIC_REGISTRATION_ENDPOINT =
        "https://d3x0w8vpui.execute-api.ap-south-1.amazonaws.com/default/OralVis_ClinicRegistration"

    /**
     * Retrofit still requires a base URL even when using full @Url values.
     * We point it at the patient API host; method-level @Url overrides this.
     */
    private const val BASE_URL = "https://te2fzjde7j.execute-api.ap-south-1.amazonaws.com/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiService: ApiService = retrofit.create(ApiService::class.java)
}


