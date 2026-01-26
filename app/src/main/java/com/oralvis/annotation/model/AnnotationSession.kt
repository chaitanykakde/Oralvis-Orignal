package com.oralvis.annotation.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a complete annotation session containing annotations for multiple images.
 * 
 * Used for batch export of annotations for an entire session.
 * 
 * @property sessionId The session identifier (from the capture session)
 * @property imageAnnotations Map of filename to ImageAnnotation
 * @property createdAt Timestamp when this session was started
 * @property lastModified Timestamp of last modification
 */
@Parcelize
data class AnnotationSession(
    val sessionId: String,
    val imageAnnotations: MutableMap<String, ImageAnnotation> = mutableMapOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var lastModified: Long = System.currentTimeMillis()
) : Parcelable {

    /**
     * Get or create ImageAnnotation for a file.
     */
    fun getOrCreateAnnotation(filename: String, imageWidth: Int = 0, imageHeight: Int = 0): ImageAnnotation {
        return imageAnnotations.getOrPut(filename) {
            ImageAnnotation.empty(filename, imageWidth, imageHeight)
        }
    }

    /**
     * Update ImageAnnotation for a file.
     */
    fun setAnnotation(annotation: ImageAnnotation) {
        imageAnnotations[annotation.filename] = annotation
        lastModified = System.currentTimeMillis()
    }

    /**
     * Check if any images have annotations.
     */
    fun hasAnyAnnotations(): Boolean {
        return imageAnnotations.values.any { it.hasAnnotations() }
    }

    /**
     * Get total annotation count across all images.
     */
    fun totalAnnotationCount(): Int {
        return imageAnnotations.values.sumOf { it.annotationCount() }
    }

    /**
     * Get list of images that have annotations.
     */
    fun getAnnotatedImages(): List<ImageAnnotation> {
        return imageAnnotations.values.filter { it.hasAnnotations() }
    }

    /**
     * Clear all annotations for all images.
     */
    fun clearAll() {
        imageAnnotations.clear()
        lastModified = System.currentTimeMillis()
    }

    companion object {
        /**
         * Create a new empty annotation session.
         */
        fun create(sessionId: String): AnnotationSession {
            return AnnotationSession(sessionId = sessionId)
        }
    }
}
