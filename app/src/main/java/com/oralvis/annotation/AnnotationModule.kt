package com.oralvis.annotation

import android.content.Context
import android.content.Intent
import com.oralvis.annotation.export.AnnotationExporter
import com.oralvis.annotation.export.AnnotationSyncHelper
import com.oralvis.annotation.ui.AnnotationActivity
import com.oralvis.annotation.util.AnnotationLogger

/**
 * Entry point for the annotation module.
 * 
 * This class provides a simple API for the main app to integrate with
 * the annotation functionality without coupling to internal implementation.
 * 
 * USAGE EXAMPLE:
 * 
 * From MediaViewerActivity or any image preview screen:
 * 
 * ```kotlin
 * // Add an "Annotate" button and handle its click:
 * btnAnnotate.setOnClickListener {
 *     AnnotationModule.launchAnnotation(
 *         context = this,
 *         imagePath = mediaRecord.filePath,
 *         imageFilename = mediaRecord.fileName,
 *         sessionId = session?.sessionId
 *     )
 * }
 * ```
 * 
 * For syncing annotations, see [AnnotationSyncHelper].
 */
object AnnotationModule {
    
    /**
     * Launch the annotation activity for an image.
     * 
     * @param context Activity context
     * @param imagePath Absolute path to the image file
     * @param imageFilename Filename of the image (used for export)
     * @param sessionId Optional session identifier for batch export
     */
    fun launchAnnotation(
        context: Context,
        imagePath: String,
        imageFilename: String,
        sessionId: String? = null
    ) {
        AnnotationLogger.i("Launching annotation for: $imageFilename (session: $sessionId)")
        
        val intent = AnnotationActivity.createIntent(
            context = context,
            imagePath = imagePath,
            imageFilename = imageFilename,
            sessionId = sessionId
        )
        context.startActivity(intent)
    }
    
    /**
     * Create an intent for the annotation activity (use with startActivityForResult).
     * 
     * Result extras include:
     * - ANNOTATION_FILE_PATH: Path to the saved JSON file
     * - SESSION_ID: The session identifier
     * 
     * @param context Activity context
     * @param imagePath Absolute path to the image file
     * @param imageFilename Filename of the image (used for export)
     * @param sessionId Optional session identifier for batch export
     * @return Intent ready to be used with startActivityForResult
     */
    fun createAnnotationIntent(
        context: Context,
        imagePath: String,
        imageFilename: String,
        sessionId: String? = null
    ): Intent {
        return AnnotationActivity.createIntent(
            context = context,
            imagePath = imagePath,
            imageFilename = imageFilename,
            sessionId = sessionId
        )
    }
    
    /**
     * Check if annotations exist for a specific session.
     * 
     * @param context Application context
     * @param sessionId Session identifier
     * @return true if annotation JSON file exists for this session
     */
    fun hasAnnotationsForSession(context: Context, sessionId: String): Boolean {
        return AnnotationSyncHelper.hasAnnotationsForSession(context, sessionId)
    }
    
    /**
     * Get the path to the annotation JSON file for a session.
     * 
     * @param context Application context
     * @param sessionId Session identifier
     * @return File path if exists, null otherwise
     */
    fun getAnnotationFilePath(context: Context, sessionId: String): String? {
        return AnnotationExporter.getAnnotationFilePath(context, sessionId)
    }
    
    /**
     * Get all annotation files ready for cloud sync.
     * 
     * TODO: Integration point - call this from your sync code to get
     * annotation files that should be uploaded along with media.
     * 
     * @param context Application context
     * @return List of annotation sync items
     */
    fun getAnnotationsForSync(context: Context): List<AnnotationSyncHelper.AnnotationSyncItem> {
        return AnnotationSyncHelper.getAnnotationsForSync(context)
    }
    
    /**
     * Get annotation for a specific session, ready for sync.
     * 
     * TODO: Integration point - call this when syncing a specific session
     * to include its annotation file.
     * 
     * @param context Application context
     * @param sessionId Session identifier
     * @return AnnotationSyncItem if exists, null otherwise
     */
    fun getAnnotationForSession(
        context: Context, 
        sessionId: String
    ): AnnotationSyncHelper.AnnotationSyncItem? {
        return AnnotationSyncHelper.getAnnotationForSession(context, sessionId)
    }
    
    /**
     * Mark an annotation as synced after successful upload.
     * 
     * TODO: Integration point - call this from your sync code after
     * successfully uploading the annotation file.
     * 
     * @param context Application context
     * @param sessionId Session identifier
     * @param success Whether the sync was successful
     */
    fun markAnnotationSynced(context: Context, sessionId: String, success: Boolean) {
        AnnotationSyncHelper.markAnnotationSynced(context, sessionId, success)
    }
    
    /**
     * Delete annotation file for a session.
     * 
     * @param context Application context
     * @param sessionId Session identifier
     * @return true if deleted successfully
     */
    fun deleteAnnotation(context: Context, sessionId: String): Boolean {
        return AnnotationExporter.deleteAnnotationFile(context, sessionId)
    }
}
