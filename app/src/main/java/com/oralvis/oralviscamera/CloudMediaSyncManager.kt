package com.oralvis.oralviscamera

import android.content.Context
import android.util.Log
import com.oralvis.oralviscamera.api.ApiService
import com.oralvis.oralviscamera.api.CloudMediaDto
import com.oralvis.oralviscamera.api.CloudMediaListResponse
import com.oralvis.oralviscamera.api.MediaDownloadRequest
import com.oralvis.oralviscamera.api.MediaDownloadResponse
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.MediaRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import java.util.*

/**
 * Manages Cloud → Local media synchronization.
 * Downloads missing media from cloud and inserts safe Room records.
 *
 * Responsibilities:
 * 1. Fetch cloud media list for a patient
 * 2. Deduplicate using cloudFileName
 * 3. Download missing media files
 * 4. Insert Room records safely
 */
object CloudMediaSyncManager {
    private const val TAG = "CloudMediaSyncManager"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Extract UUID from cloud filename (format: uuid.ext)
     * Cloud filenames are typically UUID.ext where UUID is the media identifier.
     */
    private fun extractUuidFromFileName(fileName: String): String {
        // Remove file extension and use as UUID
        val uuidPart = fileName.substringBeforeLast(".")
        return try {
            // Validate it's a proper UUID format
            UUID.fromString(uuidPart)
            uuidPart
        } catch (e: IllegalArgumentException) {
            // If not a valid UUID, generate one but log warning
            Log.w(TAG, "Filename $fileName does not contain valid UUID, generating new one")
            UUID.randomUUID().toString()
        }
    }

    /**
     * Separate Retrofit instance for Cloud → Local sync APIs.
     * Uses dedicated HTTP API Gateway for read operations.
     */
    private const val CLOUD_TO_LOCAL_BASE_URL = "https://lciq6du3h6.execute-api.ap-south-1.amazonaws.com/prod/"

    init {
        android.util.Log.d("MediaSync", "Using PROD API for cloud media operations")
    }

    private val cloudToLocalRetrofit: Retrofit by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(CLOUD_TO_LOCAL_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private val cloudToLocalApiService: ApiService by lazy {
        cloudToLocalRetrofit.create(ApiService::class.java)
    }

    /**
     * Sync cloud media to local for a specific patient.
     * Downloads missing media and creates Room records.
     *
     * @param context Application context
     * @param patientId Local patient ID (Long)
     * @param patientCode Global patient ID (code)
     * @param onProgress Progress callback (current, total)
     * @return Sync result with success/failure counts
     */
    suspend fun syncCloudMediaToLocal(
        context: Context,
        patientId: Long,
        patientCode: String,
        onProgress: ((Int, Int) -> Unit)? = null
    ): CloudSyncResult = withContext(Dispatchers.IO) {
        try {
            val loginManager = LoginManager(context)
            val clinicId = loginManager.getClientId()

            if (clinicId == null) {
                Log.w(TAG, "Cannot sync cloud media: no client ID available")
                return@withContext CloudSyncResult(false, 0, 0, "No client ID available")
            }

            // Fetch cloud media list
            val cloudMediaList = fetchCloudMediaList(patientCode, clinicId)
            if (cloudMediaList.isEmpty()) {
                Log.d(TAG, "No cloud media found for patient $patientCode")
                return@withContext CloudSyncResult(true, 0, 0, null)
            }

            Log.d(TAG, "Found ${cloudMediaList.size} cloud media items for patient $patientCode")

            val mediaRepository = com.oralvis.oralviscamera.database.MediaRepository(context)

            var successCount = 0
            var errorCount = 0

            // Process each cloud media item
            cloudMediaList.forEachIndexed { index, cloudMedia ->
                try {
                    // Check if we already have this cloud media locally using canonical mediaId
                    val existingMedia = if (cloudMedia.mediaId != null) {
                        // Use canonical mediaId for deduplication if available
                        mediaRepository.getMediaById(cloudMedia.mediaId)
                    } else {
                        // Fallback to cloud filename for legacy compatibility
                        val database = MediaDatabase.getDatabase(context)
                        database.mediaDaoV2().getMediaByCloudFileName(cloudMedia.fileName)
                    }

                    if (existingMedia != null) {
                        Log.d(TAG, "Cloud media ${cloudMedia.fileName} (mediaId=${cloudMedia.mediaId ?: "null"}) already exists locally, skipping")
                        successCount++ // Count as success since it's already synced
                    } else {
                        // Download and insert new cloud media using repository
                        val result = downloadAndInsertCloudMediaV2(context, cloudMedia, patientId, patientCode, clinicId, mediaRepository)
                        if (result) {
                            successCount++
                        } else {
                            errorCount++
                        }
                    }

                    onProgress?.invoke(index + 1, cloudMediaList.size)

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process cloud media ${cloudMedia.fileName}", e)
                    errorCount++
                }
            }

            Log.d(TAG, "Cloud sync completed: $successCount success, $errorCount errors")
            return@withContext CloudSyncResult(true, successCount, errorCount, null)

        } catch (e: Exception) {
            val errorMsg = "Exception during cloud media sync: ${e.message}"
            Log.e(TAG, errorMsg, e)
            return@withContext CloudSyncResult(false, 0, 0, errorMsg)
        }
    }

    /**
     * Fetch list of cloud media for a patient.
     */
    private suspend fun fetchCloudMediaList(patientCode: String, clinicId: String): List<CloudMediaDto> {
        return try {
            // Debug log the full request URL
            val fullUrl = "$CLOUD_TO_LOCAL_BASE_URL" + "patients/$patientCode/media"
            Log.d(TAG, "Fetching cloud media list from: $fullUrl")

            val response = cloudToLocalApiService.getPatientMedia(
                patientId = patientCode,
                clinicId = clinicId
            )

            if (response.isSuccessful && response.body() != null) {
                val responseBody = response.body()!!
                val mediaList = responseBody.media

                Log.d(TAG, "Cloud media count = ${responseBody.count}")
                Log.d(TAG, "Successfully parsed ${mediaList.size} cloud media items")

                // Log each media item for debugging
                mediaList.forEach { media ->
                    Log.d(TAG, "Parsed cloud media fileName=${media.fileName}")
                    Log.d(TAG, "Processing cloud media fileName=${media.fileName}")
                }

                mediaList
            } else {
                Log.e(TAG, "Failed to fetch cloud media list: ${response.code()} ${response.message()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching cloud media list", e)
            emptyList()
        }
    }

    /**
     * Download cloud media file and insert Room record (V2 - uses MediaRepository and canonical mediaId).
     * Returns true if successful, false otherwise.
     */
    private suspend fun downloadAndInsertCloudMediaV2(
        context: Context,
        cloudMedia: CloudMediaDto,
        patientId: Long,
        patientCode: String,
        clinicId: String,
        mediaRepository: com.oralvis.oralviscamera.database.MediaRepository
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Get download URL
            val downloadUrl = getMediaDownloadUrl(patientCode, cloudMedia.fileName, clinicId)
            if (downloadUrl == null) {
                Log.e(TAG, "Failed to get download URL for ${cloudMedia.fileName}")
                return@withContext false
            }

            // Create local directory
            val cloudMediaDir = File(context.getExternalFilesDir(null), "CloudMedia/$patientCode")
            if (!cloudMediaDir.exists()) {
                cloudMediaDir.mkdirs()
            }

            // Local file path
            val localFile = File(cloudMediaDir, cloudMedia.fileName)
            val localPath = localFile.absolutePath

            // Download file
            val downloadSuccess = downloadFile(downloadUrl, localFile)
            if (!downloadSuccess) {
                Log.e(TAG, "Failed to download file ${cloudMedia.fileName}")
                // Clean up partial file
                if (localFile.exists()) {
                    localFile.delete()
                }
                return@withContext false
            }

            // Parse capture time
            val captureTime = try {
                dateFormat.parse(cloudMedia.captureTime) ?: Date()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse capture time ${cloudMedia.captureTime}, using current time")
                Date()
            }

            // Use canonical mediaId from cloud, or extract from filename (deterministic)
            val mediaId = cloudMedia.mediaId ?: extractUuidFromFileName(cloudMedia.fileName)

            // Map cloud cameraMode to local mode values
            // Cloud uses "RGB" but local app uses "Normal"
            val localMode = when (cloudMedia.cameraMode) {
                "RGB" -> "Normal"
                else -> cloudMedia.cameraMode
            }

            // Use MediaRepository to create cloud media record atomically
            val mediaRecord = mediaRepository.createCloudMediaRecord(
                mediaId = mediaId,
                patientId = patientId,
                cloudFileName = cloudMedia.fileName,
                s3Url = cloudMedia.s3Url,
                fileContent = localFile.readBytes(), // Read downloaded file content
                mediaType = cloudMedia.mediaType,
                mode = localMode,
                captureTime = captureTime,
                dentalArch = cloudMedia.dentalArch,
                sequenceNumber = cloudMedia.sequenceNumber
            )

            if (mediaRecord != null) {
                Log.d(TAG, "Successfully created cloud media record: mediaId=$mediaId, cloudFileName=${cloudMedia.fileName}")
                return@withContext true
            } else {
                Log.e(TAG, "Failed to create cloud media record for ${cloudMedia.fileName}")
                // Clean up downloaded file on failure
                if (localFile.exists()) {
                    localFile.delete()
                }
                return@withContext false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception downloading/inserting cloud media ${cloudMedia.fileName}", e)
            return@withContext false
        }
    }

    /**
     * Get presigned download URL for a media file.
     */
    private suspend fun getMediaDownloadUrl(patientId: String, fileName: String, clinicId: String): String? {
        return try {
            // Debug log the full request URL
            val fullUrl = "$CLOUD_TO_LOCAL_BASE_URL" + "media/download-url"
            Log.d(TAG, "Getting download URL from: $fullUrl for file: $fileName")

            val request = MediaDownloadRequest(
                patientId = patientId,
                fileName = fileName
            )

            // Log the request body for debugging
            Log.d(TAG, "Download URL request body: patientId=$patientId, fileName=$fileName")

            val response = cloudToLocalApiService.getMediaDownloadUrl(
                clinicId = clinicId,
                request = request
            )

            if (response.isSuccessful && response.body() != null) {
                Log.d(TAG, "Successfully got download URL for $fileName")
                response.body()!!.downloadUrl
            } else {
                Log.e(TAG, "Failed to get download URL: ${response.code()} ${response.message()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting download URL", e)
            null
        }
    }

    /**
     * Download file from URL to local file.
     */
    private suspend fun downloadFile(url: String, destinationFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(url)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: ${response.code} ${response.message}")
                    return@withContext false
                }

                response.body?.byteStream()?.use { input ->
                    destinationFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                Log.d(TAG, "Successfully downloaded file to ${destinationFile.absolutePath}")
                return@withContext true

            } catch (e: Exception) {
                Log.e(TAG, "Exception downloading file", e)
                return@withContext false
            }
        }
    }

    data class CloudSyncResult(
        val success: Boolean,
        val successCount: Int,
        val errorCount: Int,
        val error: String?
    )
}
