package com.oralvis.oralviscamera.feature.usb

// NOTE: Phase 2 USB extraction.
// This file was moved from com.oralvis.oralviscamera.usbserial with behavior unchanged.

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

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

    fun start() {
        try {
            if (isStarted) {
                Log.d(TAG, "Already started")
                return
            }

            if (!isCameraReady()) {
                Log.i(TAG, "⚠️ Camera not ready - USB serial start deferred until camera opens")
                return
            }

            if (cameraDeviceConnection == null || cameraDevice == null) {
                Log.w(TAG, "⚠️ Camera device connection or device not available - serial cannot start")
                return
            }

            Log.i("CDC_TRACE", "CDC start (UsbSerialManager.start) | cameraReady=$isCameraReady | hasConnection=${cameraDeviceConnection != null} | hasDevice=${cameraDevice != null}")
            Log.i(TAG, "Starting USB serial manager (camera is ready)")
            isStarted = true

            val deviceForCdc = getOralVisCdcDevice()
            if (deviceForCdc == null) {
                Log.e(TAG, "OralVis CDC device (VID=$ORALVIS_CDC_VID, PID=$ORALVIS_CDC_PID) not found — is controller connected?")
                isStarted = false
                return
            }
            openConnection(deviceForCdc)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting USB serial manager: ${e.message}", e)
            isStarted = false
        }
    }

    fun stop() {
        if (!isStarted) {
            return
        }

        Log.i("CDC_TRACE", "CDC STOP | reason=UsbSerialManager.stop")
        Log.i(TAG, "Stopping USB serial manager")
        isStarted = false

        closeConnection()
    }

    fun destroy() {
        Log.i(TAG, "Destroying USB serial manager")
        stop()
        cameraDevice = null
        cameraDeviceConnection = null
        isCameraReady = false
    }

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
                isStarted = false  // Allow future start() retries after permission is granted
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
            isStarted = false  // Allow future start() retries
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
                isStarted = false  // Allow future start() retries
                onConnectionStateChanged(false)
            }
        } catch (e: Exception) {
            if (closeConnectionOnStop) connectionToUse.close()
            Log.e(TAG, "❌ Error establishing serial connection: ${e.message}", e)
            isStarted = false  // Allow future start() retries
            onConnectionStateChanged(false)
            serialConnection = null
        }
    }

    private fun closeConnection() {
        Log.i("CDC_TRACE", "CDC STOP | closeConnection | stopping serialConnection")
        serialConnection?.stop()
        serialConnection = null
    }

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

    fun isConnected(): Boolean {
        return serialConnection?.isConnected() == true
    }

    fun onCameraOpened(cameraConnection: android.hardware.usb.UsbDeviceConnection, device: UsbDevice) {
        try {
            cameraDeviceConnection = cameraConnection
            cameraDevice = device
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
            isCameraReady = false
            cameraDeviceConnection = null
            cameraDevice = null
        }
    }

    fun onCameraClosed() {
        try {
            isCameraReady = false
            cameraDeviceConnection = null
            cameraDevice = null
            Log.i("CDC_TRACE", "CDC STOP | reason=cameraClosed")
            Log.i(TAG, "📷 Camera closed - stopping USB serial and clearing device connection")

            stop()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onCameraClosed: ${e.message}", e)
            isCameraReady = false
            cameraDeviceConnection = null
            cameraDevice = null
            isStarted = false
            serialConnection = null
        }
    }

    private fun isCameraReady(): Boolean {
        return isCameraReady
    }

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

