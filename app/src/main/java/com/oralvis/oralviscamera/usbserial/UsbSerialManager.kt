package com.oralvis.oralviscamera.usbserial

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Main orchestrator for USB serial communication with OralVis hardware.
 * Manages device detection, permission handling, connection lifecycle, and command dispatch.
 *
 * Behavior aligned with Python oralvis_verify.py:
 * - Same device (VID 0x1209, PID 0xC550) via camera connection.
 * - CDC starts as soon as camera is connected (no delay).
 * - DTR and line coding set in UsbSerialConnection before read loop.
 * - Commands: newline-terminated ASCII (CAPTURE, UV, RGB); logged as [RECEIVED] / CDC_CMD.
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
        /** OralVis CDC controller (hardware buttons) — must match Python/device_filter. */
        private const val ORALVIS_CDC_VID = 0x1209
        private const val ORALVIS_CDC_PID = 0xC550
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

            // Use OralVis CDC device by VID/PID (0x1209/0xC550). If same as camera, reuse camera connection; else open separately.
            val deviceForCdc = getOralVisCdcDevice()
            if (deviceForCdc == null) {
                Log.e(TAG, "OralVis CDC device (VID=$ORALVIS_CDC_VID, PID=$ORALVIS_CDC_PID) not found — is controller connected?")
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
     * Uses OralVis CDC device (VID 0x1209, PID 0xC550). If it's the same device as the camera, reuses camera connection; otherwise opens the CDC device separately.
     */
    private fun openConnection(device: UsbDevice) {
        closeConnection()

        val isSameAsCamera = cameraDevice?.deviceId == device.deviceId
        val connectionToUse: android.hardware.usb.UsbDeviceConnection?
        val closeConnectionOnStop: Boolean

        if (isSameAsCamera) {
            connectionToUse = getCameraDeviceConnection()
            closeConnectionOnStop = false
            Log.i("CDC_TRACE", "CDC device is same as camera — reusing camera connection (deviceId=${device.deviceId})")
            Log.i(TAG, "🔌 Opening serial using camera's device connection (composite device)")
        } else {
            if (!usbManager.hasPermission(device)) {
                Log.e(TAG, "❌ No permission for OralVis CDC device (VID=${device.vendorId}, PID=${device.productId}) — request permission for controller")
                onConnectionStateChanged(false)
                return
            }
            connectionToUse = usbManager.openDevice(device)
            closeConnectionOnStop = true
            Log.i("CDC_TRACE", "CDC device is separate from camera — opened dedicated connection (deviceId=${device.deviceId})")
            Log.i(TAG, "🔌 Opening serial on OralVis CDC controller (separate device)")
        }

        if (connectionToUse == null) {
            Log.e(TAG, "❌ Cannot open connection for CDC device")
            onConnectionStateChanged(false)
            return
        }

        try {
            val serialConn = UsbSerialConnection(
                usbManager = usbManager,
                device = device,
                onCommandReceived = { rawCommand -> handleCommandReceived(rawCommand) }
            )

            if (serialConn.openWithExistingConnection(connectionToUse, closeConnectionOnStop)) {
                serialConn.startReading()
                serialConnection = serialConn
                onConnectionStateChanged(true)
                Log.i(TAG, "✅ USB serial connection established (deviceId=${device.deviceId}, VID=${device.vendorId}, PID=${device.productId})")
                Log.i(TAG, "📡 Ready to receive CAPTURE, UV, RGB commands from OralVis hardware")
            } else {
                if (closeConnectionOnStop) connectionToUse.close()
                Log.w(TAG, "CDC device has no CDC DATA interface — serial disabled")
                onConnectionStateChanged(false)
            }
        } catch (e: Exception) {
            if (closeConnectionOnStop) connectionToUse.close()
            Log.e(TAG, "❌ Error establishing serial connection: ${e.message}", e)
            onConnectionStateChanged(false)
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
     * Detailed logging for every command: received, parsed, dispatched, result.
     * Filter logcat by "CDC_CMD" to see full command path.
     */
    private fun handleCommandReceived(rawCommand: String) {
        try {
            if (rawCommand.isBlank()) {
                Log.d("CDC_CMD", "[SKIP] blank or whitespace-only command")
                return
            }

            Log.i("CDC_CMD", "[RECEIVED] raw='$rawCommand'")
            Log.i("CDC_TRACE", "HARDWARE BUTTON EVENT received | raw=$rawCommand")

            val command = commandParser.parse(rawCommand)

            if (command.type == CommandType.UNKNOWN) {
                Log.w("CDC_CMD", "[SKIP] unknown command: '$rawCommand'")
                Log.d(TAG, "⚠️ Skipping unknown command: '$rawCommand'")
                return
            }

            Log.i("CDC_CMD", "[PARSE] type=${command.type} raw='$rawCommand'")
            Log.i(TAG, "🎮 Processing command: ${command.type} (from: '$rawCommand')")

            val success = commandDispatcher.dispatch(command)

            if (success) {
                Log.i("CDC_CMD", "[OK] executed: ${command.type}")
                Log.i(TAG, "✅ Command executed successfully: ${command.type}")
            } else {
                Log.w("CDC_CMD", "[REJECTED] ${command.type} (rate limit or guard)")
                Log.w(TAG, "❌ Command execution failed or rejected: ${command.type}")
            }
        } catch (e: Exception) {
            Log.e("CDC_CMD", "[ERROR] raw='$rawCommand' ${e.message}", e)
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
     * CDC is started immediately by MainActivity when camera opens (onCameraOpened → start),
     * so hardware buttons (CAPTURE/UV/RGB) work as soon as the camera is connected.
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
            Log.i(TAG, "CDC serial will be started by MainActivity when camera opens (immediate)")
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
     * Find the OralVis CDC controller by VID/PID (0x1209, 0xC550).
     * When camera and controller are the same composite device, this may return the camera device;
     * when they are separate (e.g. camera VID=9568, controller VID=0x1209), returns the controller.
     */
    private fun getOralVisCdcDevice(): UsbDevice? {
        usbManager.deviceList?.values?.forEach {
            Log.i("CDC_TRACE", "USB_SCAN: id=${it.deviceId}, VID=${it.vendorId}, PID=${it.productId}, ifaces=${it.interfaceCount}")
        } ?: Log.i("CDC_TRACE", "USB_SCAN: deviceList.values is null")
        val cdc = usbManager.deviceList?.values?.find { it.vendorId == ORALVIS_CDC_VID && it.productId == ORALVIS_CDC_PID }
        if (cdc != null) {
            Log.i("CDC_TRACE", "getOralVisCdcDevice: FOUND id=${cdc.deviceId} VID=${cdc.vendorId} PID=${cdc.productId}")
            Log.i(TAG, "Using OralVis CDC device (deviceId=${cdc.deviceId}) for hardware buttons")
            return cdc
        }
        Log.w("CDC_TRACE", "getOralVisCdcDevice: NOT FOUND (VID=$ORALVIS_CDC_VID PID=$ORALVIS_CDC_PID)")
        return null
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
