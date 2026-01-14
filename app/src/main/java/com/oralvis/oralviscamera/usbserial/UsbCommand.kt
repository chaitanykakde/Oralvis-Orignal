package com.oralvis.oralviscamera.usbserial

/**
 * Command types recognized by the USB serial interface.
 */
enum class CommandType {
    CAPTURE,    // "CAPTURE" - trigger photo capture
    UV,         // "UV" - switch to Fluorescence mode
    RGB,        // "RGB" - switch to Normal mode
    UNKNOWN     // Unrecognized command
}

/**
 * Represents a parsed USB command.
 * @param type The command type
 * @param rawText The original raw text received
 * @param timestamp When the command was received
 */
data class UsbCommand(
    val type: CommandType,
    val rawText: String,
    val timestamp: Long = System.currentTimeMillis()
)
