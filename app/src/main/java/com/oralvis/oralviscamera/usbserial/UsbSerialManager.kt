package com.oralvis.oralviscamera.usbserial

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Main orchestrator for USB serial communication with OralVis hardware.
 * Manages device detection, permission handling, connection lifecycle, and command dispatch.
 */
class UsbSerialManager(
    private val context: Context,
    private val commandReceiver: CameraCommandReceiver,
    private val onConnectionStateChanged: (Boolean) -> Unit
) {

    // Camera's device connection (shared with serial)
    private var cameraDeviceConnection: android.hardware.usb.UsbDeviceConnection? = null
    // Camera's UsbDevice (obtained from connection for interface enumeration)
    private var cameraDevice: UsbDevice? = null

    companion object {
        private const val TAG = "UsbSerialManager"
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val commandParser = UsbCommandParser()

    private val commandDispatcher = UsbCommandDispatcher(commandReceiver)

    private var serialConnection: UsbSerialConnection? = null

    private var isStarted = false
    private var isCameraReady = false

    init {
        Log.i("CDC_TRACE", "UsbSerialManager created | thread=${Thread.currentThread().name} | usbManager=${usbManager != null} | isStarted=$isStarted | isCameraReady=$isCameraReady")
    }

    /**
     * Start the USB serial manager.
     * No longer handles device detection or permissions - camera owns that.
     * Call from Activity.onResume().
     *
     * CRITICAL: Only starts if camera is ready to ensure camera-exclusive device ownership.
     */
    fun start() {
        try {
            if (isStarted) {
                Log.d(TAG, "Already started")
                return
            }

            // ENFORCE CAMERA-FIRST RULE: Serial only starts after camera is ready
            if (!isCameraReady()) {
                Log.i(TAG, "⚠️ Camera not ready - USB serial start deferred until camera opens")
                return
            }

            // Camera must provide both connection and device for serial to work
            if (cameraDeviceConnection == null || cameraDevice == null) {
                Log.w(TAG, "⚠️ Camera device connection or device not available - serial cannot start")
                return
            }

            Log.i("CDC_TRACE", "CDC start (UsbSerialManager.start) | cameraReady=$isCameraReady | hasConnection=${cameraDeviceConnection != null} | hasDevice=${cameraDevice != null}")
            Log.i(TAG, "Starting USB serial manager (camera is ready)")
            isStarted = true

            // PROBLEM 2 FIX: Use fresh UsbDevice from UsbManager at CDC start time.
            // Cached device from camera open can be a stale snapshot; interfaces may
            // be enumerated later. Fresh lookup ensures CDC DATA is found when present.
            val deviceForCdc = getFreshUsbDevice()
            if (deviceForCdc == null) {
                Log.e(TAG, "Cannot get UsbDevice for CDC — cameraDevice is null")
                isStarted = false
                return
            }
            openConnection(deviceForCdc)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting USB serial manager: ${e.message}", e)
            // Reset state on error
            isStarted = false
        }
    }
    
    /**
     * Stop the USB serial manager.
     * Closes connection.
     * Call from Activity.onPause() or when camera closes.
     */
    fun stop() {
        if (!isStarted) {
            return
        }

        Log.i("CDC_TRACE", "CDC STOP | reason=UsbSerialManager.stop")
        Log.i(TAG, "Stopping USB serial manager")
        isStarted = false

        // Close connection
        closeConnection()
    }
    
    /**
     * Destroy the USB serial manager.
     * Call from Activity.onDestroy().
     */
    fun destroy() {
        Log.i(TAG, "Destroying USB serial manager")
        stop()
        cameraDevice = null
        cameraDeviceConnection = null
        isCameraReady = false
    }
    
    
    /**
     * Open the USB serial connection and start reading commands.
     * This is called AFTER camera is ready to ensure camera owns the device.
     */
    private fun openConnection(device: UsbDevice) {
        // Close any existing connection
        closeConnection()

        // CRITICAL: Wait for camera to be ready before opening serial
        // This ensures camera has exclusive device ownership
        if (!isCameraReady()) {
            Log.w(TAG, "⚠️ Camera not ready - deferring serial connection")
            return
        }

        // Get the camera's device connection
        val cameraConnection = getCameraDeviceConnection()
        if (cameraConnection == null) {
            Log.e(TAG, "❌ Cannot access camera's device connection")
            onConnectionStateChanged(false)
            return
        }

        Log.i(TAG, "🔌 Opening serial using camera's device connection")

        try {
            val connection = UsbSerialConnection(
                usbManager = usbManager,
                device = device,
                onCommandReceived = { rawCommand ->
                    handleCommandReceived(rawCommand)
                }
            )

            // Use camera's existing device connection instead of opening our own
            if (connection.openWithExistingConnection(cameraConnection)) {
                connection.startReading()
                serialConnection = connection
                onConnectionStateChanged(true)
                Log.i(TAG, "✅ USB serial connection established using camera's device")
                Log.i(TAG, "📡 Ready to receive CAPTURE, UV, RGB commands from OralVis hardware")
            } else {
                Log.w(TAG, "Camera device has no CDC DATA interface — serial disabled")
                onConnectionStateChanged(false)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error establishing serial connection: ${e.message}", e)
            onConnectionStateChanged(false)
            // Reset state on error
            serialConnection = null
        }
    }
    
    /**
     * Close the active USB serial connection.
     */
    private fun closeConnection() {
        Log.i("CDC_TRACE", "CDC STOP | closeConnection | stopping serialConnection")
        serialConnection?.stop()
        serialConnection = null
    }
    
    /**
     * Handle a received command from the serial connection.
     * Parses and dispatches the command on the main thread.
     */
    private fun handleCommandReceived(rawCommand: String) {
        try {
            // Skip empty or whitespace-only commands
            if (rawCommand.isBlank()) {
                return
            }

            Log.i("CDC_TRACE", "HARDWARE BUTTON EVENT received | raw=$rawCommand")
            Log.d(TAG, "📥 Received raw command: '$rawCommand'")

            val command = commandParser.parse(rawCommand)

            // Skip unknown commands to avoid spam
            if (command.type == CommandType.UNKNOWN) {
                Log.d(TAG, "⚠️ Skipping unknown command: '$rawCommand'")
                return
            }

            Log.i(TAG, "🎮 Processing command: ${command.type} (from: '$rawCommand')")

            // Dispatch command (dispatcher will call commandReceiver which uses runOnUiThread)
            val success = commandDispatcher.dispatch(command)

            if (success) {
                Log.i(TAG, "✅ Command executed successfully: ${command.type}")
            } else {
                Log.w(TAG, "❌ Command execution failed or rejected: ${command.type}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling command '$rawCommand': ${e.message}", e)
        }
    }
    
    /**
     * Check if currently connected to the USB device.
     */
    fun isConnected(): Boolean {
        return serialConnection?.isConnected() == true
    }

    /**
     * Notify that camera has opened successfully.
     * Stores camera connection and device reference for later CDC startup.
     * 
     * CRITICAL: CDC will NOT start here. It will start only after first frame received.
     * This ensures UVC enumeration is complete before CDC interface claiming.
     */
    fun onCameraOpened(cameraConnection: android.hardware.usb.UsbDeviceConnection, device: UsbDevice) {
        try {
            cameraDeviceConnection = cameraConnection
            cameraDevice = device  // Store the device reference
            isCameraReady = true
            Log.i("CDC_TRACE", """
onCameraOpened:
deviceId=${device.deviceId}
VID=${device.vendorId}, PID=${device.productId}
interfaces=${device.interfaceCount}
deviceName=${device.deviceName}
source=cameraCallback
""".trimIndent())
            Log.i(TAG, "📷 Camera opened - stored device connection (VID=${cameraDevice?.vendorId}, PID=${cameraDevice?.productId})")
            Log.i(TAG, "⏳ CDC serial will start after first video frame received (UVC streaming must be stable)")

            // DO NOT start serial here - wait for first frame confirmation
            // Serial will be started from MainActivity.onFirstFrameReceived()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onCameraOpened: ${e.message}", e)
            // Reset state on error to prevent inconsistent state
            isCameraReady = false
            cameraDeviceConnection = null
            cameraDevice = null
        }
    }

    /**
     * Notify that camera has closed.
     * Serial should stop when camera stops.
     */
    fun onCameraClosed() {
        try {
            isCameraReady = false
            cameraDeviceConnection = null
            cameraDevice = null
            Log.i("CDC_TRACE", "CDC STOP | reason=cameraClosed")
            Log.i(TAG, "📷 Camera closed - stopping USB serial and clearing device connection")

            // Stop serial when camera closes
            stop()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onCameraClosed: ${e.message}", e)
            // Force reset state even on error
            isCameraReady = false
            cameraDeviceConnection = null
            cameraDevice = null
            isStarted = false
            serialConnection = null
        }
    }

    /**
     * Check if camera is ready (for internal use).
     */
    private fun isCameraReady(): Boolean {
        return isCameraReady
    }

    /**
     * Obtain a fresh UsbDevice from UsbManager at CDC start time.
     * Cached device from camera open can be a stale snapshot; a fresh lookup
     * ensures CDC DATA interface is found when the kernel has finished enumeration.
     */
    private fun getFreshUsbDevice(): UsbDevice? {
        val cached = cameraDevice ?: return null
        usbManager.deviceList?.values?.forEach {
            Log.i("CDC_TRACE", "USB_SCAN: id=${it.deviceId}, VID=${it.vendorId}, PID=${it.productId}, ifaces=${it.interfaceCount}")
        } ?: Log.i("CDC_TRACE", "USB_SCAN: deviceList.values is null")
        val fresh = usbManager.deviceList?.values?.find { it.deviceId == cached.deviceId }
        if (fresh != null) {
            Log.i("CDC_TRACE", "getFreshUsbDevice: match FOUND, using fresh UsbDevice from UsbManager")
            Log.i(TAG, "Using fresh UsbDevice from UsbManager for CDC (deviceId=${cached.deviceId})")
            return fresh
        }
        Log.i("CDC_TRACE", "getFreshUsbDevice: match NOT FOUND, using cached fallback (deviceId=${cached.deviceId})")
        Log.w(TAG, "Fresh UsbDevice not in deviceList, using cached — CDC may fail if interfaces were not enumerated (deviceId=${cached.deviceId})")
        return cached
    }

    /**
     * Get the camera's device connection.
     * This allows serial to reuse the camera's already-open device connection.
     */
    private fun getCameraDeviceConnection(): android.hardware.usb.UsbDeviceConnection? {
        if (!isCameraReady) {
            Log.w(TAG, "Camera not ready - cannot get device connection")
            return null
        }

        if (cameraDeviceConnection == null) {
            Log.e(TAG, "Camera device connection is null despite camera being ready")
            return null
        }

        Log.d(TAG, "Using camera's device connection for serial")
        return cameraDeviceConnection
    }
}
