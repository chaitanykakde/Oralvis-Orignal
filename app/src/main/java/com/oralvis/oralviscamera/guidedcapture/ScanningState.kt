package com.oralvis.oralviscamera.guidedcapture

/**
 * Mirrors the Python ScanningState enum from AutoCapture/main.py.
 *
 * READY_TO_SCAN_LOWER  -> waiting to start guided lower-arch scan
 * SCANNING_LOWER       -> actively scanning lower arch with auto-capture
 * READY_TO_SCAN_UPPER  -> waiting to start guided upper-arch scan
 * SCANNING_UPPER       -> actively scanning upper arch with auto-capture
 * COMPLETE             -> both arches captured; session ready to finish or recapture upper
 */
enum class ScanningState {
    READY_TO_SCAN_LOWER,
    SCANNING_LOWER,
    READY_TO_SCAN_UPPER,
    SCANNING_UPPER,
    COMPLETE
}


