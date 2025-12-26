package com.oralvis.oralviscamera.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Separate API service for login functionality.
 * 
 * Rules:
 * - Do NOT modify existing ApiService
 * - Do NOT add login logic to CloudSyncService
 * - Do NOT add interceptors that affect existing APIs
 */
interface LoginApiService {

    /**
     * Client login endpoint.
     * 
     * Endpoint: POST /client-login
     * Body: { clientId: String, password: String }
     * 
     * Uses HTTP API Gateway: ejriu7iz5a.execute-api.ap-south-1.amazonaws.com
     */
    @POST("client-login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<LoginResponse>
}

/**
 * Login request model.
 */
data class LoginRequest(
    val clientId: String,
    val password: String
)

/**
 * Login response model.
 */
data class LoginResponse(
    val success: Boolean,
    val message: String? = null,
    val clientId: String? = null
)

