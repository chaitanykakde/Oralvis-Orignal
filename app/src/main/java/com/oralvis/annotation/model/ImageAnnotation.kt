package com.oralvis.annotation.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents all annotations for a single image.
 * 
 * @property filename The filename of the annotated image (e.g., "image1.jpg")
 * @property annotations List of bounding box annotations on this image
 * @property imageWidth Original image width in pixels (for validation)
 * @property imageHeight Original image height in pixels (for validation)
 * @property lastModified Timestamp of last modification
 */
@Parcelize
data class ImageAnnotation(
    val filename: String,
    val annotations: MutableList<AnnotationBox> = mutableListOf(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
) : Parcelable {

    /**
     * Add a new annotation box.
     */
    fun addAnnotation(box: AnnotationBox) {
        annotations.add(box)
    }

    /**
     * Remove an annotation by its ID.
     * @return true if annotation was found and removed
     */
    fun removeAnnotation(id: String): Boolean {
        return annotations.removeAll { it.id == id }
    }

    /**
     * Update the label of an existing annotation.
     * @return true if annotation was found and updated
     */
    fun updateAnnotationLabel(id: String, newLabel: String): Boolean {
        val index = annotations.indexOfFirst { it.id == id }
        if (index >= 0) {
            val existing = annotations[index]
            annotations[index] = existing.copy(label = newLabel)
            return true
        }
        return false
    }

    /**
     * Get an annotation by its ID.
     */
    fun getAnnotation(id: String): AnnotationBox? {
        return annotations.find { it.id == id }
    }

    /**
     * Find annotation at the given image coordinates.
     * Returns the first annotation whose bounding box contains the point.
     */
    fun findAnnotationAt(x: Float, y: Float): AnnotationBox? {
        // Search in reverse order so newer annotations (drawn on top) are found first
        return annotations.reversed().find { it.containsPoint(x, y) }
    }

    /**
     * Check if this image has any annotations.
     */
    fun hasAnnotations(): Boolean {
        return annotations.isNotEmpty()
    }

    /**
     * Get count of annotations.
     */
    fun annotationCount(): Int {
        return annotations.size
    }

    /**
     * Clear all annotations.
     */
    fun clearAnnotations() {
        annotations.clear()
    }

    /**
     * Validate all annotations are within image bounds.
     * Removes any that are outside the image.
     */
    fun validateBounds() {
        if (imageWidth <= 0 || imageHeight <= 0) return
        
        annotations.removeAll { box ->
            box.bbox.left < 0 || box.bbox.top < 0 ||
            box.bbox.right > imageWidth || box.bbox.bottom > imageHeight
        }
    }

    companion object {
        /**
         * Create an empty ImageAnnotation for a file.
         */
        fun empty(filename: String, width: Int = 0, height: Int = 0): ImageAnnotation {
            return ImageAnnotation(
                filename = filename,
                imageWidth = width,
                imageHeight = height
            )
        }
    }
}
