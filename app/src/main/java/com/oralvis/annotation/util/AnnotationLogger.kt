package com.oralvis.annotation.util

import android.util.Log

/**
 * Centralized logging utility for the annotation module.
 * 
 * Provides structured logging with consistent tags and levels
 * for easier debugging and monitoring of annotation lifecycle.
 */
object AnnotationLogger {
    
    private const val TAG = "AnnotationModule"
    private const val TAG_UI = "Annotation.UI"
    private const val TAG_OVERLAY = "Annotation.Overlay"
    private const val TAG_EXPORT = "Annotation.Export"
    private const val TAG_COORDINATE = "Annotation.Coord"
    
    private var isDebugEnabled = true
    
    /**
     * Enable or disable debug logging.
     */
    fun setDebugEnabled(enabled: Boolean) {
        isDebugEnabled = enabled
    }
    
    // ============= General Module Logging =============
    
    fun d(message: String) {
        if (isDebugEnabled) Log.d(TAG, message)
    }
    
    fun i(message: String) {
        Log.i(TAG, message)
    }
    
    fun w(message: String) {
        Log.w(TAG, message)
    }
    
    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
    
    // ============= UI Lifecycle Logging =============
    
    fun logActivityCreated(activityName: String, imagePath: String?) {
        i("[$TAG_UI] Activity created: $activityName, imagePath: $imagePath")
    }
    
    fun logActivityDestroyed(activityName: String) {
        i("[$TAG_UI] Activity destroyed: $activityName")
    }
    
    fun logModeChanged(newMode: String) {
        d("[$TAG_UI] Mode changed to: $newMode")
    }
    
    fun logLabelSelected(label: String?) {
        d("[$TAG_UI] Label selected: $label")
    }
    
    // ============= Overlay/Drawing Logging =============
    
    fun logDrawStart(screenX: Float, screenY: Float) {
        if (isDebugEnabled) {
            Log.d(TAG_OVERLAY, "Draw started at screen: ($screenX, $screenY)")
        }
    }
    
    fun logDrawEnd(screenRect: String, imageRect: String) {
        if (isDebugEnabled) {
            Log.d(TAG_OVERLAY, "Draw ended - Screen: $screenRect, Image: $imageRect")
        }
    }
    
    fun logAnnotationAdded(id: String, label: String, bbox: String) {
        i("[$TAG_OVERLAY] Annotation added: id=$id, label=$label, bbox=$bbox")
    }
    
    fun logAnnotationDeleted(id: String) {
        i("[$TAG_OVERLAY] Annotation deleted: id=$id")
    }
    
    fun logAnnotationDiscarded(reason: String) {
        d("[$TAG_OVERLAY] Annotation discarded: $reason")
    }
    
    // ============= Coordinate Mapping Logging =============
    
    fun logCoordinateMapping(
        screenCoords: String,
        imageCoords: String,
        scaleFactor: Float,
        translation: String
    ) {
        if (isDebugEnabled) {
            Log.d(TAG_COORDINATE, "Mapping: screen=$screenCoords -> image=$imageCoords (scale=$scaleFactor, trans=$translation)")
        }
    }
    
    fun logImageMatrix(matrixValues: String) {
        if (isDebugEnabled) {
            Log.d(TAG_COORDINATE, "Image matrix: $matrixValues")
        }
    }
    
    // ============= Export Logging =============
    
    fun logExportStarted(sessionId: String, imageCount: Int) {
        i("[$TAG_EXPORT] Export started for session: $sessionId with $imageCount images")
    }
    
    fun logExportCompleted(filePath: String) {
        i("[$TAG_EXPORT] Export completed: $filePath")
    }
    
    fun logExportFailed(error: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG_EXPORT, "Export failed: $error", throwable)
        } else {
            Log.e(TAG_EXPORT, "Export failed: $error")
        }
    }
    
    // ============= Session Logging =============
    
    fun logSessionLoaded(sessionId: String, annotationCount: Int) {
        i("Session loaded: $sessionId with $annotationCount total annotations")
    }
    
    fun logSessionSaved(sessionId: String) {
        i("Session saved: $sessionId")
    }
}
