package com.oralvis.oralviscamera.usbserial

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import java.nio.charset.StandardCharsets

/**
 * Manages USB serial connection for reading commands from hardware.
 * Runs a dedicated background thread for blocking USB bulk transfers.
 */
class UsbSerialConnection(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val onCommandReceived: (String) -> Unit
) {
    
    companion object {
        private const val TAG = "UsbSerialConnection"
        private const val BAUD_RATE = 115200
        private const val DATA_BITS = 8
        private const val STOP_BITS = 1
        private const val PARITY = 0
        private const val TIMEOUT_MS = 1000
        private const val BUFFER_SIZE = 1024
    }
    
    private var connection: UsbDeviceConnection? = null
    private var readThread: Thread? = null
    @Volatile private var isRunning = false
    
    /**
     * Open the USB connection and claim the interface.
     * @return true if connection opened successfully
     */
    fun open(): Boolean {
        try {
            val conn = usbManager.openDevice(device)
            if (conn == null) {
                Log.e(TAG, "Failed to open USB device")
                return false
            }
            
            // Claim the first interface
            if (device.interfaceCount == 0) {
                Log.e(TAG, "No interfaces available on device")
                conn.close()
                return false
            }
            
            val intf = device.getInterface(0)
            if (!conn.claimInterface(intf, true)) {
                Log.e(TAG, "Failed to claim interface")
                conn.close()
                return false
            }
            
            connection = conn
            Log.i(TAG, "USB connection opened successfully")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error opening USB connection: ${e.message}", e)
            return false
        }
    }
    
    /**
     * Start the background reading thread.
     * Reads data in a loop and calls onCommandReceived for each newline-terminated command.
     */
    fun startReading() {
        if (connection == null) {
            Log.e(TAG, "Cannot start reading: connection not open")
            return
        }
        
        isRunning = true
        readThread = Thread({
            Log.i(TAG, "USB read thread started")
            val buffer = ByteArray(BUFFER_SIZE)
            val commandBuffer = StringBuilder()
            
            try {
                // Find the IN endpoint (bulk transfer for reading)
                val intf = device.getInterface(0)
                var inEndpoint: android.hardware.usb.UsbEndpoint? = null
                
                for (i in 0 until intf.endpointCount) {
                    val endpoint = intf.getEndpoint(i)
                    if (endpoint.direction == android.hardware.usb.UsbConstants.USB_DIR_IN &&
                        endpoint.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        inEndpoint = endpoint
                        break
                    }
                }
                
                if (inEndpoint == null) {
                    Log.e(TAG, "No bulk IN endpoint found")
                    return@Thread
                }
                
                Log.d(TAG, "Using IN endpoint: address=${inEndpoint.address}, maxPacketSize=${inEndpoint.maxPacketSize}")
                
                while (isRunning) {
                    try {
                        // Blocking bulk transfer
                        val length = connection?.bulkTransfer(inEndpoint, buffer, buffer.size, TIMEOUT_MS) ?: -1

                        if (length > 0) {
                            // Convert bytes to string
                            val data = String(buffer, 0, length, StandardCharsets.UTF_8)
                            commandBuffer.append(data)

                            // Process complete commands (newline-terminated)
                            // Handle multiple commands in one read and partial commands across reads
                            while (true) {
                                val newlineIndex = commandBuffer.indexOf('\n')
                                if (newlineIndex == -1) break

                                val command = commandBuffer.substring(0, newlineIndex).trim()
                                commandBuffer.delete(0, newlineIndex + 1)

                                // Skip empty commands (multiple newlines)
                                if (command.isNotEmpty()) {
                                    Log.d(TAG, "Command received: '$command'")
                                    try {
                                        onCommandReceived(command)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error processing command '$command': ${e.message}")
                                    }
                                }
                            }

                            // Prevent buffer from growing indefinitely if no newlines received
                            if (commandBuffer.length > BUFFER_SIZE * 2) {
                                Log.w(TAG, "Command buffer too large (${commandBuffer.length} chars), clearing")
                                commandBuffer.setLength(0)
                            }

                        } else if (length < 0) {
                            // Timeout or error - continue loop
                            if (length != -1) {
                                Log.w(TAG, "Bulk transfer error: $length")
                            }
                        }

                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(TAG, "Error during bulk transfer: ${e.message}")
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in read thread: ${e.message}", e)
            } finally {
                Log.i(TAG, "USB read thread stopped")
            }
        }, "UsbSerialReadThread")
        
        readThread?.start()
    }
    
    /**
     * Stop the reading thread and close the connection.
     */
    fun stop() {
        Log.i(TAG, "Stopping USB serial connection")
        isRunning = false

        // Close connection first to unblock bulkTransfer
        try {
            connection?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connection: ${e.message}")
        }
        connection = null

        // Wait for thread to finish (with timeout)
        readThread?.let { thread ->
            try {
                thread.join(1000) // Wait up to 1 second
                if (thread.isAlive) {
                    Log.w(TAG, "USB read thread did not stop gracefully")
                    thread.interrupt()
                }
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while waiting for USB read thread to stop")
                Thread.currentThread().interrupt()
            }
        }

        readThread = null
    }
    
    /**
     * Check if connection is currently active.
     */
    fun isConnected(): Boolean {
        return connection != null && isRunning && readThread?.isAlive == true
    }

    /**
     * Get connection status for debugging.
     */
    fun getStatus(): String {
        return "UsbSerialConnection(connection=${connection != null}, running=$isRunning, threadAlive=${readThread?.isAlive})"
    }
}
