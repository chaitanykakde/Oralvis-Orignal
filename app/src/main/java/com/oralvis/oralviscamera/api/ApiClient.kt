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

    // AWS Credentials from BuildConfig (loaded from local.properties)
    // Add to local.properties:
    // aws.access.key=YOUR_ACCESS_KEY
    // aws.secret.key=YOUR_SECRET_KEY
    val AWS_ACCESS_KEY: String = com.oralvis.oralviscamera.BuildConfig.AWS_ACCESS_KEY
    val AWS_SECRET_KEY: String = com.oralvis.oralviscamera.BuildConfig.AWS_SECRET_KEY

    // API Gateway endpoints - ALL USING /prod STAGE
    const val API_MEDIA_SYNC_ENDPOINT =
        "https://lciq6du3h6.execute-api.ap-south-1.amazonaws.com/prod/media/sync"
    const val API_CLINIC_REGISTRATION_ENDPOINT =
        "https://d3x0w8vpui.execute-api.ap-south-1.amazonaws.com/default/OralVis_ClinicRegistration"

    // Login API uses separate HTTP API Gateway (ejriu7iz5a) with /prod stage
    // Endpoint: https://ejriu7iz5a.execute-api.ap-south-1.amazonaws.com/prod/client-login

    /**
     * Base URL for ALL APIs using /prod stage.
     * ALL operations now use production APIs.
     */
    private const val BASE_URL = "https://19p5ev9nic.execute-api.ap-south-1.amazonaws.com/prod/"

    /**
     * Base URL for login API (separate HTTP API Gateway with /prod stage).
     */
    private const val LOGIN_BASE_URL = "https://ejriu7iz5a.execute-api.ap-south-1.amazonaws.com/prod/"

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
    
    /**
     * Separate Retrofit instance for login API.
     * Uses dedicated HTTP API Gateway (ejriu7iz5a) with $default stage.
     * Isolated from all other APIs.
     */
    private val loginRetrofit = Retrofit.Builder()
        .baseUrl(LOGIN_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    val loginApiService: LoginApiService = loginRetrofit.create(LoginApiService::class.java)
}


