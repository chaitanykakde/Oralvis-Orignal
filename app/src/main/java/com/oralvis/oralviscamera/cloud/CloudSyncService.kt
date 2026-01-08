package com.oralvis.oralviscamera.cloud

import android.content.Context
import android.util.Log
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.oralvis.oralviscamera.LoginManager
import com.oralvis.oralviscamera.api.ApiClient
import com.oralvis.oralviscamera.api.ApiClient.AWS_REGION
import com.oralvis.oralviscamera.api.ApiClient.S3_BUCKET_NAME
import com.oralvis.oralviscamera.api.MediaMetadataDto
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.MediaRecord
import com.oralvis.oralviscamera.database.Patient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume

/**
 * Service to handle cloud synchronization of media files.
 * Uploads media to S3 and creates metadata records via API.
 */
object CloudSyncService {
    private const val TAG = "CloudSyncService"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Sync all unsynced media for a patient to the cloud.
     * @param context Application context
     * @param patient Patient to sync media for
     * @param onProgress Callback for progress updates (current, total)
     * @return Result with success count and error count
     */
    suspend fun syncPatientMedia(
        context: Context,
        patient: Patient,
        onProgress: ((Int, Int) -> Unit)? = null
    ): SyncResult = withContext(Dispatchers.IO) {
        val mediaRepository = com.oralvis.oralviscamera.database.MediaRepository(context)
        val loginManager = LoginManager(context)
        val clientId = loginManager.getClientId() ?: return@withContext SyncResult(
            successCount = 0,
            errorCount = 0,
            error = "Client ID not found. Please login."
        )

        // Get all uploadable media for this patient using new repository
        val unsyncedMedia = mediaRepository.getUploadableMedia(patient.id)
        if (unsyncedMedia.isEmpty()) {
            return@withContext SyncResult(0, 0, null)
        }

        var successCount = 0
        var errorCount = 0
        var lastError: String? = null

        unsyncedMedia.forEachIndexed { index, mediaRecord ->
            try {
                // INVARIANT: Only upload media in DB_COMMITTED state
                if (mediaRecord.state != com.oralvis.oralviscamera.database.MediaState.DB_COMMITTED) {
                    Log.w(TAG, "Skipping media ${mediaRecord.mediaId} in state ${mediaRecord.state} (expected DB_COMMITTED)")
                    errorCount++
                    return@forEachIndexed
                }

                // Update state to UPLOADING
                val stateUpdated = mediaRepository.updateMediaState(mediaRecord.mediaId, com.oralvis.oralviscamera.database.MediaState.UPLOADING)
                if (!stateUpdated) {
                    Log.w(TAG, "Failed to update state to UPLOADING for ${mediaRecord.mediaId}")
                    errorCount++
                    return@forEachIndexed
                }

                val result = syncSingleMediaV2(context, patient, mediaRecord, clientId)
                if (result.success && result.s3Url != null) {
                    // Update repository with cloud metadata and SYNCED state
                    val metadataUpdated = mediaRepository.updateCloudMetadata(mediaRecord.mediaId, result.cloudFileName, result.s3Url)
                    if (metadataUpdated) {
                        successCount++
                    } else {
                        Log.e(TAG, "Failed to update cloud metadata for ${mediaRecord.mediaId}")
                        // Rollback to DB_COMMITTED state
                        mediaRepository.updateMediaState(mediaRecord.mediaId, com.oralvis.oralviscamera.database.MediaState.DB_COMMITTED)
                        errorCount++
                    }
                } else {
                    // Upload failed - rollback to DB_COMMITTED state
                    mediaRepository.updateMediaState(mediaRecord.mediaId, com.oralvis.oralviscamera.database.MediaState.DB_COMMITTED)
                    errorCount++
                    lastError = result.error
                    Log.e(TAG, "Failed to sync media ${mediaRecord.mediaId}: ${result.error}")
                }
                onProgress?.invoke(index + 1, unsyncedMedia.size)
            } catch (e: Exception) {
                errorCount++
                lastError = e.message
                // Ensure state is rolled back on exception
                mediaRepository.updateMediaState(mediaRecord.mediaId, com.oralvis.oralviscamera.database.MediaState.DB_COMMITTED)
                Log.e(TAG, "Exception syncing media ${mediaRecord.mediaId}", e)
            }
        }

        SyncResult(successCount, errorCount, lastError)
    }

    /**
     * Sync a single media file to cloud (V2 - uses MediaRecordV2 and canonical mediaId).
     * Process: 1) Upload file to S3, 2) Call Lambda with JSON metadata including mediaId
     */
    private suspend fun syncSingleMediaV2(
        context: Context,
        patient: Patient,
        mediaRecord: com.oralvis.oralviscamera.database.MediaRecordV2,
        clientId: String
    ): SingleMediaResult = withContext(Dispatchers.IO) {
        try {
            val file = File(mediaRecord.filePath)
            if (!file.exists()) {
                return@withContext SingleMediaResult(false, "", null, "File not found: ${mediaRecord.filePath}")
            }

            // Use canonical mediaId to generate cloud filename for deduplication
            val originalExtension = file.extension
            val cloudFileName = "${mediaRecord.mediaId}.$originalExtension"

            // Step 1: Upload file to S3
            // S3 path: s3://oralvis-media/{GlobalPatientId}/{ClientId}/{CloudFileName}
            val s3Key = "${patient.code}/$clientId/$cloudFileName"
            Log.d(TAG, "Uploading file to S3: bucket=$S3_BUCKET_NAME, key=$s3Key, mediaId=${mediaRecord.mediaId}")

            val s3Url = uploadFileToS3(context, file, s3Key)
            if (s3Url == null) {
                return@withContext SingleMediaResult(false, cloudFileName, null, "Failed to upload file to S3")
            }

            Log.d(TAG, "File uploaded to S3: $s3Url")

            // Step 2: Prepare metadata for Lambda (JSON format) - now includes mediaId
            val captureTimeStr = dateFormat.format(mediaRecord.captureTime)

            // Map camera mode: "Normal" -> "RGB", "Fluorescence" -> "Fluorescence"
            val cameraMode = when (mediaRecord.mode) {
                "Normal" -> "RGB"
                "Fluorescence" -> "Fluorescence"
                else -> mediaRecord.mode
            }

            // Ensure mediaType is capitalized correctly (Image/Video)
            val mediaTypeForApi = mediaRecord.mediaType.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }

            // Create metadata DTO with canonical mediaId for cloud deduplication
            val metadata = MediaMetadataDto(
                patientId = patient.code,
                clinicId = clientId, // Use Client ID from login
                fileName = cloudFileName,
                s3Url = s3Url,
                mediaType = mediaTypeForApi,
                cameraMode = cameraMode,
                // Include canonical mediaId for deduplication
                mediaId = mediaRecord.mediaId, // NEW: Canonical ID for cloud deduplication
                // For legacy/manual captures these will be null.
                // For guided auto-capture, they are populated from MediaRecord.
                dentalArch = mediaRecord.dentalArch,
                sequenceNumber = mediaRecord.sequenceNumber,
                captureTime = captureTimeStr
            )

            Log.d(TAG, "Syncing metadata to Lambda: patientId=${patient.code}, fileName=$cloudFileName")

            // Step 3: Call Lambda with JSON body
            val response = ApiClient.apiService.syncMediaMetadata(
                url = ApiClient.API_MEDIA_SYNC_ENDPOINT,
                clinicId = clientId, // Use Client ID from login
                request = metadata
            )

            if (response.isSuccessful) {
                Log.d(TAG, "Successfully synced media metadata")
                SingleMediaResult(true, cloudFileName, s3Url, null)
            } else {
                val errorBody = try {
                    response.errorBody()?.string() ?: "No error body"
                } catch (e: Exception) {
                    "Error reading error body: ${e.message}"
                }
                val errorMessage = "API error (${response.code()}): $errorBody"
                Log.e(TAG, "Failed to sync media metadata: $errorMessage")
                SingleMediaResult(false, cloudFileName, null, errorMessage)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception syncing media", e)
            SingleMediaResult(false, "", null, "Exception: ${e.message}")
        }
    }

    /**
     * Upload file to S3 and return the S3 URL
     */
    private suspend fun uploadFileToS3(
        context: Context,
        file: File,
        s3Key: String
    ): String? = suspendCancellableCoroutine { continuation ->
        // Validate AWS credentials before proceeding
        val accessKey = ApiClient.AWS_ACCESS_KEY
        val secretKey = ApiClient.AWS_SECRET_KEY
        
        if (accessKey.isBlank() || secretKey.isBlank()) {
            Log.e(TAG, "AWS credentials are not configured. Please set aws.access.key and aws.secret.key in local.properties")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        
        // Flag to ensure continuation is only resumed once
        var isResumed = false
        fun safeResume(value: String?) {
            if (!isResumed && continuation.isActive) {
                isResumed = true
                continuation.resume(value)
            }
        }
        
        try {
            // Initialize AWS credentials
            val credentials = BasicAWSCredentials(accessKey, secretKey)
            
            val s3Client = AmazonS3Client(credentials, Region.getRegion(Regions.AP_SOUTH_1))
            val transferUtility = TransferUtility.builder()
                .context(context)
                .s3Client(s3Client)
                .build()

            val uploadObserver = transferUtility.upload(S3_BUCKET_NAME, s3Key, file)
            
            uploadObserver.setTransferListener(object : TransferListener {
                override fun onStateChanged(id: Int, state: TransferState) {
                    when (state) {
                        TransferState.COMPLETED -> {
                            val s3Url = "https://$S3_BUCKET_NAME.s3.$AWS_REGION.amazonaws.com/$s3Key"
                            safeResume(s3Url)
                        }
                        TransferState.FAILED -> {
                            Log.e(TAG, "S3 upload failed (state: FAILED)")
                            safeResume(null)
                        }
                        else -> {
                            // Other states (IN_PROGRESS, WAITING, etc.) - do nothing
                        }
                    }
                }

                override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                    val percentDone = (bytesCurrent * 100 / bytesTotal).toInt()
                    Log.d(TAG, "S3 upload progress: $percentDone%")
                }

                override fun onError(id: Int, ex: Exception) {
                    Log.e(TAG, "S3 upload error", ex)
                    safeResume(null)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing S3 upload", e)
            safeResume(null)
        }
    }

    data class SyncResult(
        val successCount: Int,
        val errorCount: Int,
        val error: String?
    )

    private data class SingleMediaResult(
        val success: Boolean,
        val cloudFileName: String,
        val s3Url: String?,  // Can be null on failure
        val error: String?
    )
}

