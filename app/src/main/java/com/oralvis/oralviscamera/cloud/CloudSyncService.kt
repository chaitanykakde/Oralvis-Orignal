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
import com.oralvis.oralviscamera.ClinicManager
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
        val database = MediaDatabase.getDatabase(context)
        val mediaDao = database.mediaDao()
        val clinicManager = ClinicManager(context)
        val clinicId = clinicManager.getClinicId() ?: return@withContext SyncResult(
            successCount = 0,
            errorCount = 0,
            error = "Clinic ID not found"
        )

        // Get all unsynced media for this patient
        val unsyncedMedia = mediaDao.getUnsyncedMediaByPatient(patient.id)
        if (unsyncedMedia.isEmpty()) {
            return@withContext SyncResult(0, 0, null)
        }

        var successCount = 0
        var errorCount = 0
        var lastError: String? = null

        unsyncedMedia.forEachIndexed { index, mediaRecord ->
            try {
                val result = syncSingleMedia(context, patient, mediaRecord, clinicId)
                if (result.success) {
                    // Update local database
                    mediaDao.updateSyncStatus(mediaRecord.id, true, result.s3Url)
                    successCount++
                } else {
                    errorCount++
                    lastError = result.error
                    Log.e(TAG, "Failed to sync media ${mediaRecord.id}: ${result.error}")
                }
                onProgress?.invoke(index + 1, unsyncedMedia.size)
            } catch (e: Exception) {
                errorCount++
                lastError = e.message
                Log.e(TAG, "Exception syncing media ${mediaRecord.id}", e)
            }
        }

        SyncResult(successCount, errorCount, lastError)
    }

    /**
     * Sync a single media file to cloud.
     * Process: 1) Upload file to S3, 2) Call Lambda with JSON metadata
     */
    private suspend fun syncSingleMedia(
        context: Context,
        patient: Patient,
        mediaRecord: MediaRecord,
        clinicId: String
    ): SingleMediaResult = withContext(Dispatchers.IO) {
        try {
            val file = File(mediaRecord.filePath)
            if (!file.exists()) {
                return@withContext SingleMediaResult(false, null, "File not found: ${mediaRecord.filePath}")
            }

            // Generate unique filename (GUID + extension)
            val originalExtension = file.extension
            val newFileName = "${UUID.randomUUID()}.$originalExtension"

            // Step 1: Upload file to S3
            // S3 path: s3://oralvis-media/{GlobalPatientId}/{ClinicId}/{FileName}
            val s3Key = "${patient.code}/$clinicId/$newFileName"
            Log.d(TAG, "Uploading file to S3: bucket=$S3_BUCKET_NAME, key=$s3Key")
            
            val s3Url = uploadFileToS3(context, file, s3Key)
            if (s3Url == null) {
                return@withContext SingleMediaResult(false, null, "Failed to upload file to S3")
            }
            
            Log.d(TAG, "File uploaded to S3: $s3Url")

            // Step 2: Prepare metadata for Lambda (JSON format)
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
            
            // Create metadata DTO matching Lambda function expectations
            val metadata = MediaMetadataDto(
                patientId = patient.code,
                clinicId = clinicId,
                fileName = newFileName,
                s3Url = s3Url,
                mediaType = mediaTypeForApi,
                cameraMode = cameraMode,
                dentalArch = null, // Optional - Lambda handles null
                sequenceNumber = null, // Optional - Lambda handles null
                captureTime = captureTimeStr
            )

            Log.d(TAG, "Syncing metadata to Lambda: patientId=${patient.code}, fileName=$newFileName")
            
            // Step 3: Call Lambda with JSON body
            val response = ApiClient.apiService.syncMediaMetadata(
                url = ApiClient.API_MEDIA_SYNC_ENDPOINT,
                clinicId = clinicId,
                request = metadata
            )

            if (response.isSuccessful) {
                Log.d(TAG, "Successfully synced media metadata")
                SingleMediaResult(true, s3Url, null)
            } else {
                val errorBody = try {
                    response.errorBody()?.string() ?: "No error body"
                } catch (e: Exception) {
                    "Error reading error body: ${e.message}"
                }
                val errorMessage = "API error (${response.code()}): $errorBody"
                Log.e(TAG, "Failed to sync media metadata: $errorMessage")
                SingleMediaResult(false, null, errorMessage)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception syncing media", e)
            SingleMediaResult(false, null, "Exception: ${e.message}")
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
        try {
            // Initialize AWS credentials
            val credentials = BasicAWSCredentials(
                ApiClient.AWS_ACCESS_KEY,
                ApiClient.AWS_SECRET_KEY
            )
            
            val s3Client = AmazonS3Client(credentials, Region.getRegion(Regions.AP_SOUTH_1))
            val transferUtility = TransferUtility.builder()
                .context(context)
                .s3Client(s3Client)
                .build()

            val uploadObserver = transferUtility.upload(S3_BUCKET_NAME, s3Key, file)
            
            uploadObserver.setTransferListener(object : TransferListener {
                override fun onStateChanged(id: Int, state: TransferState) {
                    if (state == TransferState.COMPLETED) {
                        val s3Url = "https://$S3_BUCKET_NAME.s3.$AWS_REGION.amazonaws.com/$s3Key"
                        continuation.resume(s3Url)
                    } else if (state == TransferState.FAILED) {
                        continuation.resume(null)
                    }
                }

                override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                    val percentDone = (bytesCurrent * 100 / bytesTotal).toInt()
                    Log.d(TAG, "S3 upload progress: $percentDone%")
                }

                override fun onError(id: Int, ex: Exception) {
                    Log.e(TAG, "S3 upload error", ex)
                    continuation.resume(null)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing S3 upload", e)
            continuation.resume(null)
        }
    }

    data class SyncResult(
        val successCount: Int,
        val errorCount: Int,
        val error: String?
    )

    private data class SingleMediaResult(
        val success: Boolean,
        val s3Url: String?,
        val error: String?
    )
}

