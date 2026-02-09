package com.oralvis.oralviscamera.feature.usb

// NOTE: Phase 2 USB extraction. Behavior unchanged from original usbserial version.

import android.util.Log

class UsbCommandParser {

    companion object {
        private const val TAG = "UsbCommandParser"
    }

    fun parse(rawCommand: String): UsbCommand {
        val normalized = rawCommand.trim().uppercase()

        val type = when (normalized) {
            "CAPTURE" -> CommandType.CAPTURE
            "UV" -> CommandType.UV
            "RGB" -> CommandType.RGB
            else -> {
                Log.w(TAG, "Unknown command received: '$rawCommand'")
                CommandType.UNKNOWN
            }
        }

        return UsbCommand(
            type = type,
            rawText = rawCommand,
            timestamp = System.currentTimeMillis()
        )
    }
}

