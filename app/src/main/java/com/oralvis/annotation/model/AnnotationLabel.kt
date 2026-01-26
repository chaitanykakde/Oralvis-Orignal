package com.oralvis.annotation.model

import android.graphics.Color

/**
 * Predefined annotation labels for dental conditions.
 * 
 * These labels are hardcoded as per requirements.
 * Future versions may load labels from a configuration file or remote source.
 */
enum class AnnotationLabel(
    val displayName: String,
    val color: Int
) {
    ACTIVE_CARIES("Active Caries", Color.parseColor("#FF5252")),      // Red
    INACTIVE_CARIES("Inactive Caries", Color.parseColor("#FF9800")), // Orange
    PLAQUE("Plaque", Color.parseColor("#FFEB3B")),                    // Yellow
    GINGIVITIS("Gingivitis", Color.parseColor("#E91E63")),            // Pink
    CALCULUS("Calculus", Color.parseColor("#9C27B0"));                // Purple

    companion object {
        /**
         * Get all available labels for the picker.
         */
        fun getAllLabels(): List<AnnotationLabel> {
            return values().toList()
        }

        /**
         * Get label by display name.
         */
        fun fromDisplayName(name: String): AnnotationLabel? {
            return values().find { it.displayName == name }
        }

        /**
         * Get color for a given label name.
         * Returns a default color if label not found.
         */
        fun getColorForLabel(labelName: String): Int {
            val label = fromDisplayName(labelName)
            return label?.color ?: Color.parseColor("#2196F3") // Default blue
        }

        /**
         * Get all label display names.
         */
        fun getAllLabelNames(): List<String> {
            return values().map { it.displayName }
        }
    }
}
