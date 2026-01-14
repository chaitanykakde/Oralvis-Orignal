package com.oralvis.oralviscamera.usbserial

import android.util.Log

/**
 * Parser for USB serial commands.
 * Converts raw text strings into typed UsbCommand objects.
 */
class UsbCommandParser {
    
    companion object {
        private const val TAG = "UsbCommandParser"
    }
    
    /**
     * Parse a raw command string into a UsbCommand.
     * @param rawCommand The raw text received from serial connection
     * @return Parsed UsbCommand with appropriate CommandType
     */
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
