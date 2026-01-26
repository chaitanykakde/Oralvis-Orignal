package com.oralvis.annotation.export

import android.content.Context
import com.oralvis.annotation.util.AnnotationLogger
import java.io.File

/**
 * Helper class for integrating annotation files with the existing cloud sync pipeline.
 * 
 * IMPORTANT: This class does NOT reimplement upload logic.
 * It only prepares annotation files for the existing sync pipeline and provides
 * hooks/interfaces that the main app sync code can use.
 * 
 * Annotation JSON files are treated as:
 *   mediaType = "annotation"
 * 
 * They should be uploaded to the same S3 bucket as images.
 */
object AnnotationSyncHelper {
    
    /**
     * Metadata for an annotation file ready for sync.
     */
    data class AnnotationSyncItem(
        val sessionId: String,
        val filePath: String,
        val filename: String,
        val mediaType: String = "annotation", // Fixed media type for annotations
        val fileSize: Long,
        val lastModified: Long
    )
    
    /**
     * Get all annotation files that are ready for sync.
     * 
     * TODO: Integration point - the main app's sync code should call this
     * to get annotation files that need to be uploaded along with media.
     * 
     * @param context Application context
     * @return List of annotation items ready for sync
     */
    fun getAnnotationsForSync(context: Context): List<AnnotationSyncItem> {
        val files = AnnotationExporter.listAnnotationFiles(context)
        
        return files.mapNotNull { file ->
            try {
                // Extract session ID from filename: annotations_<sessionId>.json
                val sessionId = file.name
                    .removePrefix("annotations_")
                    .removeSuffix(".json")
                
                AnnotationSyncItem(
                    sessionId = sessionId,
                    filePath = file.absolutePath,
                    filename = file.name,
                    fileSize = file.length(),
                    lastModified = file.lastModified()
                )
            } catch (e: Exception) {
                AnnotationLogger.e("Failed to process annotation file: ${file.name}", e)
                null
            }
        }
    }
    
    /**
     * Get annotation file for a specific session.
     * 
     * TODO: Integration point - sync code can call this to get the annotation
     * file for a specific session when uploading session media.
     * 
     * @param context Application context
     * @param sessionId Session identifier
     * @return AnnotationSyncItem if file exists, null otherwise
     */
    fun getAnnotationForSession(context: Context, sessionId: String): AnnotationSyncItem? {
        val filePath = AnnotationExporter.getAnnotationFilePath(context, sessionId) ?: return null
        val file = File(filePath)
        
        if (!file.exists()) return null
        
        return AnnotationSyncItem(
            sessionId = sessionId,
            filePath = file.absolutePath,
            filename = file.name,
            fileSize = file.length(),
            lastModified = file.lastModified()
        )
    }
    
    /**
     * Mark an annotation as synced.
     * 
     * TODO: Integration point - sync code should call this after successful upload.
     * For now, this just logs the event. In production, you might want to:
     * - Move the file to a "synced" folder
     * - Update a database record
     * - Delete the local file (if cloud is source of truth)
     * 
     * @param context Application context
     * @param sessionId Session identifier
     * @param success Whether the sync was successful
     */
    fun markAnnotationSynced(context: Context, sessionId: String, success: Boolean) {
        if (success) {
            AnnotationLogger.i("Annotation for session $sessionId marked as synced")
            // TODO: Implement actual sync status tracking if needed
            // Options:
            // 1. Delete local file (cloud is source of truth)
            // 2. Rename/move to synced folder
            // 3. Update a local database with sync status
        } else {
            AnnotationLogger.w("Annotation sync failed for session $sessionId")
        }
    }
    
    /**
     * Check if annotations exist for a session.
     * 
     * @param context Application context
     * @param sessionId Session identifier
     * @return true if annotation file exists
     */
    fun hasAnnotationsForSession(context: Context, sessionId: String): Boolean {
        return AnnotationExporter.annotationFileExists(context, sessionId)
    }
    
    /**
     * Get S3 key path for an annotation file.
     * 
     * TODO: Integration point - sync code should use this to determine
     * the S3 path for uploading annotation files.
     * 
     * This follows a pattern similar to media files:
     * <clinicId>/<patientId>/annotations/<filename>
     * 
     * @param clinicId Clinic identifier
     * @param patientId Patient identifier
     * @param filename Annotation filename
     * @return S3 key path
     */
    fun getS3KeyPath(clinicId: String, patientId: String, filename: String): String {
        return "$clinicId/$patientId/annotations/$filename"
    }
}
