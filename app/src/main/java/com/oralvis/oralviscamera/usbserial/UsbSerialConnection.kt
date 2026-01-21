package com.oralvis.oralviscamera.usbserial

import android.hardware.usb.UsbConstants
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
 * Open the USB connection using the camera's already-open device connection.
 * @param existingConnection The camera's UsbDeviceConnection (must already be open)
 * @return true if connection established successfully
 */
fun openWithExistingConnection(existingConnection: UsbDeviceConnection): Boolean {
    try {
        if (existingConnection == null) {
            Log.e(TAG, "Failed to get camera's USB device connection")
            return false
        }

            Log.i("CDC_TRACE", "CDC ENUM START | deviceId=${device.deviceId} | interfaceCount=${device.interfaceCount}")

            // Find CDC DATA interface (USB_CLASS_CDC_DATA = 10)
            var cdcDataInterface: android.hardware.usb.UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                Log.i("CDC_TRACE", """
Interface[$i]:
class=${intf.interfaceClass}
subclass=${intf.interfaceSubclass}
protocol=${intf.interfaceProtocol}
endpointCount=${intf.endpointCount}
""".trimIndent())
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    Log.i("CDC_TRACE", "  Endpoint[$e]: type=${ep.type}, dir=${ep.direction}, addr=${ep.address}")
                }
                if (intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                    Log.i("CDC_TRACE", "CDC DATA interface FOUND at index=$i")
                    cdcDataInterface = intf
                    break
                }
            }

            if (cdcDataInterface == null) {
                Log.e("CDC_TRACE", "CDC DATA interface NOT FOUND on deviceId=${device.deviceId}")
                Log.e(TAG, "No CDC DATA interface found on device")
                return false
            }

            // Claim the CDC DATA interface only
            Log.i("CDC_TRACE", "Attempting claimInterface() | force=true | interfaceId=${cdcDataInterface.id}")
            if (!existingConnection.claimInterface(cdcDataInterface, true)) {
                Log.e("CDC_TRACE", "claimInterface FAILED | interfaceId=${cdcDataInterface.id}")
                Log.e(TAG, "Failed to claim CDC DATA interface (index ${cdcDataInterface.id})")
                return false
            }

            Log.i("CDC_TRACE", "CDC connection OPEN | baud=$BAUD_RATE | thread=${Thread.currentThread().name}")
            connection = existingConnection
            Log.i(TAG, "✅ USB connection opened successfully - using CDC DATA interface index ${cdcDataInterface.id}")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error opening USB connection: ${e.message}", e)
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
            Log.i("CDC_TRACE", "CDC READ THREAD STARTED")
            Log.i(TAG, "USB read thread started")
            val buffer = ByteArray(BUFFER_SIZE)
            val commandBuffer = StringBuilder()

            try {
                // Find the CDC DATA interface that was claimed
                var cdcDataInterface: android.hardware.usb.UsbInterface? = null
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    if (intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                        cdcDataInterface = intf
                        break
                    }
                }

                if (cdcDataInterface == null) {
                    Log.e(TAG, "❌ No CDC DATA interface found")
                    return@Thread
                }

                // Find BULK IN and BULK OUT endpoints on the CDC DATA interface
                var inEndpoint: android.hardware.usb.UsbEndpoint? = null
                var outEndpoint: android.hardware.usb.UsbEndpoint? = null

                for (i in 0 until cdcDataInterface.endpointCount) {
                    val endpoint = cdcDataInterface.getEndpoint(i)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                            inEndpoint = endpoint
                        } else if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                            outEndpoint = endpoint
                        }
                    }
                }

                Log.i("CDC_TRACE", "CDC endpoint selected | IN=${inEndpoint?.address} | OUT=${outEndpoint?.address}")
                if (inEndpoint == null) {
                    Log.e("CDC_TRACE", "CDC endpoints missing or invalid")
                    Log.e(TAG, "❌ No bulk IN endpoint found on CDC DATA interface")
                    return@Thread
                }

                // Log endpoint information for diagnostics
                Log.i(TAG, "📡 Using CDC DATA interface index ${cdcDataInterface.id}")
                Log.i(TAG, "📡 CDC DATA endpoints: IN=${inEndpoint.address}, OUT=${outEndpoint?.address ?: "N/A"}")

                while (isRunning) {
                    try {
                        Log.d("CDC_TRACE", "CDC READ attempt...")
                        // Blocking bulk transfer
                        val length = connection?.bulkTransfer(inEndpoint, buffer, buffer.size, TIMEOUT_MS) ?: -1

                        if (length > 0) {
                            Log.i("CDC_TRACE", "CDC READ bytes=$length data=${buffer.take(length).joinToString("") { "%02x".format(it.toInt().and(0xFF)) }.let { if (it.length > 128) it.take(128) + "..." else it }}")
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
                            Log.e("CDC_TRACE", "CDC READ ERROR", e)
                            Log.e(TAG, "Error during bulk transfer: ${e.message}")
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e("CDC_TRACE", "CDC READ ERROR", e)
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
        Log.i("CDC_TRACE", "CDC STOP | UsbSerialConnection.stop | reason=stop")
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
