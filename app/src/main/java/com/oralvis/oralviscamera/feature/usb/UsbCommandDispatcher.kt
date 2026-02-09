package com.oralvis.oralviscamera.feature.usb

// NOTE: Phase 2 USB extraction. Behavior unchanged from original usbserial version.

import android.util.Log

class UsbCommandDispatcher(
    private val commandReceiver: CameraCommandReceiver
) {

    companion object {
        private const val TAG = "UsbCommandDispatcher"
        private const val MIN_COMMAND_INTERVAL_MS = 200L
    }

    private var lastCommandTime = 0L

    fun dispatch(command: UsbCommand): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastCommand = currentTime - lastCommandTime

        if (timeSinceLastCommand < MIN_COMMAND_INTERVAL_MS) {
            Log.d(TAG, "Command rate limited: ${command.type} (${timeSinceLastCommand}ms since last)")
            return false
        }

        lastCommandTime = currentTime
        Log.i(TAG, "Dispatching command: ${command.type} (raw: '${command.rawText}')")

        return when (command.type) {
            CommandType.CAPTURE -> {
                Log.d(TAG, "Executing CAPTURE command")
                val result = commandReceiver.triggerCapture()
                if (result) {
                    Log.i(TAG, "CAPTURE command executed successfully")
                } else {
                    Log.w(TAG, "CAPTURE command rejected (camera not ready or already capturing)")
                }
                result
            }

            CommandType.UV -> {
                Log.d(TAG, "Executing UV (Fluorescence mode) command")
                val result = commandReceiver.switchToFluorescenceMode()
                if (result) {
                    Log.i(TAG, "UV command executed successfully")
                } else {
                    Log.w(TAG, "UV command rejected (camera not ready or guided session active)")
                }
                result
            }

            CommandType.RGB -> {
                Log.d(TAG, "Executing RGB (Normal mode) command")
                val result = commandReceiver.switchToNormalMode()
                if (result) {
                    Log.i(TAG, "RGB command executed successfully")
                } else {
                    Log.w(TAG, "RGB command rejected (camera not ready or guided session active)")
                }
                result
            }

            CommandType.UNKNOWN -> {
                Log.w(TAG, "Unknown command ignored: '${command.rawText}'")
                false
            }
        }
    }
}

