package com.oralvis.oralviscamera.usbserial

import android.util.Log

/**
 * Dispatcher that executes USB commands via the CameraCommandReceiver interface.
 * Provides context-aware command handling (e.g., different behavior during guided sessions).
 */
class UsbCommandDispatcher(
    private val commandReceiver: CameraCommandReceiver
) {

    companion object {
        private const val TAG = "UsbCommandDispatcher"
        private const val MIN_COMMAND_INTERVAL_MS = 200L // Minimum 200ms between commands
    }

    private var lastCommandTime = 0L
    
    /**
     * Dispatch a parsed USB command to the appropriate app action.
     * @param command The parsed command to execute
     * @return true if command was successfully dispatched, false otherwise
     */
    fun dispatch(command: UsbCommand): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastCommand = currentTime - lastCommandTime

        // Rate limit commands to prevent rapid-fire execution
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
