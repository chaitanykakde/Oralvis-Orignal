package com.oralvis.oralviscamera.database

/**
 * Media state machine for production-grade media management.
 * Defines explicit states and valid transitions for media lifecycle.
 */
enum class MediaState {
    /**
     * Initial capture triggered, file/DB not yet committed.
     * Temporary state during capture process.
     */
    CAPTURED,

    /**
     * File written to temp location, ready for DB commit.
     * File exists but media not yet visible in gallery.
     */
    FILE_READY,

    /**
     * DB record exists, media logically exists.
     * Media is visible in gallery, can be uploaded.
     */
    DB_COMMITTED,

    /**
     * Currently being uploaded to cloud.
     * Prevents concurrent upload attempts.
     */
    UPLOADING,

    /**
     * Successfully uploaded to cloud.
     * Media exists locally and in cloud.
     */
    SYNCED,

    /**
     * Downloaded from cloud.
     * Media sourced from cloud, not local capture.
     */
    DOWNLOADED,

    /**
     * DB record exists but file missing/unreadable.
     * Media logically exists but file needs recovery.
     */
    FILE_MISSING,

    /**
     * File exists but corrupted/unreadable.
     * Media cannot be displayed but record preserved.
     */
    CORRUPT;

    /**
     * Check if media is visible in gallery.
     */
    fun isVisibleInGallery(): Boolean = when (this) {
        DB_COMMITTED, SYNCED, DOWNLOADED, FILE_MISSING -> true
        CAPTURED, FILE_READY, UPLOADING, CORRUPT -> false
    }

    /**
     * Check if media can be uploaded to cloud.
     */
    fun canBeUploaded(): Boolean = when (this) {
        DB_COMMITTED -> true
        CAPTURED, FILE_READY, UPLOADING, SYNCED, DOWNLOADED, FILE_MISSING, CORRUPT -> false
    }

    /**
     * Check if media needs file recovery.
     */
    fun needsFileRecovery(): Boolean = when (this) {
        FILE_MISSING -> true
        CAPTURED, FILE_READY, DB_COMMITTED, UPLOADING, SYNCED, DOWNLOADED, CORRUPT -> false
    }

    /**
     * Get valid next states for this state.
     */
    fun getValidTransitions(): Set<MediaState> = when (this) {
        CAPTURED -> setOf(FILE_READY)
        FILE_READY -> setOf(DB_COMMITTED)
        DB_COMMITTED -> setOf(UPLOADING, FILE_MISSING, CORRUPT)
        UPLOADING -> setOf(SYNCED, DB_COMMITTED) // Success or rollback
        SYNCED -> setOf(FILE_MISSING, CORRUPT)
        DOWNLOADED -> setOf(FILE_MISSING, CORRUPT)
        FILE_MISSING -> setOf(DB_COMMITTED, CORRUPT) // File recovered or became corrupt
        CORRUPT -> setOf(FILE_MISSING) // File became unreadable but still missing
    }

    /**
     * Validate state transition.
     */
    fun canTransitionTo(newState: MediaState): Boolean = newState in getValidTransitions()
}
