package com.oralvis.oralviscamera.gallery

import com.oralvis.oralviscamera.database.MediaRecord

/**
 * Represents a sequence card showing RGB and Fluorescence image pair.
 * Groups media by guidedSessionId, dentalArch, and sequenceNumber.
 */
data class SequenceCard(
    val sequenceNumber: Int,
    val dentalArch: String, // "LOWER", "UPPER", or "OTHER"
    val guidedSessionId: String?,
    val rgbImage: MediaRecord?, // Normal mode image
    val fluorescenceImage: MediaRecord? // Fluorescence mode image
) {
    /**
     * Check if this sequence has both images captured.
     */
    fun isComplete(): Boolean {
        return rgbImage != null && fluorescenceImage != null
    }
    
    /**
     * Get display title for the sequence.
     */
    fun getTitle(): String {
        return "Sequence #$sequenceNumber"
    }
}

