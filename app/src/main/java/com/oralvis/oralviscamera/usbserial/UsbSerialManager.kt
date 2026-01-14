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
    
    companion object {
        private const val TAG = "UsbSerialManager"
    }
    
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    
    private val commandParser = UsbCommandParser()
    private val commandDispatcher = UsbCommandDispatcher(commandReceiver)
    
    private var deviceDetector: UsbDeviceDetector? = null
    private var permissionManager: UsbPermissionManager? = null
    private var serialConnection: UsbSerialConnection? = null
    
    private var currentDevice: UsbDevice? = null
    private var isStarted = false
    
    /**
     * Start the USB serial manager.
     * Registers listeners for device attach/detach and begins monitoring.
     * Call from Activity.onResume().
     */
    fun start() {
        if (isStarted) {
            Log.d(TAG, "Already started")
            return
        }
        
        Log.i(TAG, "Starting USB serial manager")
        isStarted = true
        
        // Initialize permission manager
        permissionManager = UsbPermissionManager(
            context = context,
            usbManager = usbManager,
            onPermissionGranted = { device ->
                handlePermissionGranted(device)
            },
            onPermissionDenied = { device ->
                handlePermissionDenied(device)
            }
        )
        permissionManager?.register()
        
        // Initialize device detector
        deviceDetector = UsbDeviceDetector(
            context = context,
            usbManager = usbManager,
            onDeviceAttached = { device ->
                handleDeviceAttached(device)
            },
            onDeviceDetached = { device ->
                handleDeviceDetached(device)
            }
        )
        deviceDetector?.register()
    }
    
    /**
     * Stop the USB serial manager.
     * Closes connection and unregisters all listeners.
     * Call from Activity.onPause().
     */
    fun stop() {
        if (!isStarted) {
            return
        }
        
        Log.i(TAG, "Stopping USB serial manager")
        isStarted = false
        
        // Close connection
        closeConnection()
        
        // Unregister listeners
        deviceDetector?.unregister()
        permissionManager?.unregister()
        
        deviceDetector = null
        permissionManager = null
    }
    
    /**
     * Destroy the USB serial manager.
     * Call from Activity.onDestroy().
     */
    fun destroy() {
        Log.i(TAG, "Destroying USB serial manager")
        stop()
        currentDevice = null
    }
    
    /**
     * Handle device attached event.
     */
    private fun handleDeviceAttached(device: UsbDevice) {
        Log.i(TAG, "Device attached, requesting permission")
        currentDevice = device
        permissionManager?.requestPermission(device)
    }
    
    /**
     * Handle device detached event.
     */
    private fun handleDeviceDetached(device: UsbDevice) {
        Log.i(TAG, "Device detached")
        if (currentDevice == device) {
            closeConnection()
            currentDevice = null
            onConnectionStateChanged(false)
        }
    }
    
    /**
     * Handle permission granted event.
     */
    private fun handlePermissionGranted(device: UsbDevice) {
        Log.i(TAG, "Permission granted, opening connection")
        currentDevice = device
        openConnection(device)
    }
    
    /**
     * Handle permission denied event.
     */
    private fun handlePermissionDenied(device: UsbDevice) {
        Log.w(TAG, "Permission denied for device: ${device.deviceName}")
        onConnectionStateChanged(false)
    }
    
    /**
     * Open the USB serial connection and start reading commands.
     */
    private fun openConnection(device: UsbDevice) {
        // Close any existing connection
        closeConnection()

        // Log detailed device information for the connection
        Log.i(TAG, "Attempting to connect to OralVis device:")
        Log.i(TAG, "  Device Name: ${device.deviceName}")
        Log.i(TAG, "  Vendor ID: ${device.vendorId} (0x${device.vendorId.toString(16)})")
        Log.i(TAG, "  Product ID: ${device.productId} (0x${device.productId.toString(16)})")
        Log.i(TAG, "  Serial Number: ${device.serialNumber ?: "N/A"}")
        Log.i(TAG, "  Manufacturer: ${device.manufacturerName ?: "N/A"}")
        Log.i(TAG, "  Product Name: ${device.productName ?: "N/A"}")
        Log.i(TAG, "  Device Class: ${device.deviceClass}")
        Log.i(TAG, "  Interfaces: ${device.interfaceCount}")

        try {
            val connection = UsbSerialConnection(
                usbManager = usbManager,
                device = device,
                onCommandReceived = { rawCommand ->
                    handleCommandReceived(rawCommand)
                }
            )

            if (connection.open()) {
                connection.startReading()
                serialConnection = connection
                onConnectionStateChanged(true)
                Log.i(TAG, "✅ USB serial connection established successfully")
                Log.i(TAG, "📡 Ready to receive CAPTURE, UV, RGB commands from OralVis hardware")
            } else {
                Log.e(TAG, "❌ Failed to open USB serial connection")
                onConnectionStateChanged(false)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error opening connection: ${e.message}", e)
            onConnectionStateChanged(false)
        }
    }
    
    /**
     * Close the active USB serial connection.
     */
    private fun closeConnection() {
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
}
