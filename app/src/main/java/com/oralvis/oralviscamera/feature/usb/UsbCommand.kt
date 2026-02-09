package com.oralvis.oralviscamera.feature.usb

// NOTE: Phase 2 USB extraction. Behavior unchanged from original usbserial version.

enum class CommandType {
    CAPTURE,
    UV,
    RGB,
    UNKNOWN
}

data class UsbCommand(
    val type: CommandType,
    val rawText: String,
    val timestamp: Long = System.currentTimeMillis()
)

