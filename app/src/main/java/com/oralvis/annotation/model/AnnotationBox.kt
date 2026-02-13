package com.oralvis.annotation.model

import android.graphics.RectF
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a single bounding box annotation on an image.
 * 
 * Coordinates are stored in IMAGE PIXEL COORDINATES (not screen coordinates).
 * This ensures annotations are independent of screen size, zoom, pan, and device resolution.
 *
 * @property id Unique identifier for this annotation box
 * @property label The label/class assigned to this annotation (e.g., "Active Caries")
 * @property bbox The bounding box in image pixel coordinates [left, top, right, bottom]
 * @property createdAt Timestamp when this annotation was created
 */
@Parcelize
data class AnnotationBox(
    val id: String,
    val label: String,
    val bbox: RectF,
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable {

    /**
     * Get the bounding box as an integer array [left, top, right, bottom].
     * Used for JSON export in the required format.
     */
    fun getBboxArray(): IntArray {
        return intArrayOf(
            bbox.left.toInt(),
            bbox.top.toInt(),
            bbox.right.toInt(),
            bbox.bottom.toInt()
        )
    }

    /**
     * Check if a point (in image coordinates) is within this bounding box.
     */
    fun containsPoint(x: Float, y: Float): Boolean {
        return bbox.contains(x, y)
    }

    /**
     * Get the center point of this bounding box.
     */
    fun getCenter(): Pair<Float, Float> {
        return Pair(bbox.centerX(), bbox.centerY())
    }

    /**
     * Check if this annotation box has valid dimensions.
     */
    fun isValid(): Boolean {
        return bbox.width() > 0 && bbox.height() > 0 && label.isNotBlank()
    }

    companion object {
        /**
         * Create a new AnnotationBox from integer coordinates.
         */
        fun fromIntCoordinates(
            id: String,
            label: String,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int
        ): AnnotationBox {
            return AnnotationBox(
                id = id,
                label = label,
                bbox = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            )
        }
    }
}
