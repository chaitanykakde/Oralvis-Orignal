package com.oralvis.oralviscamera.feature.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import java.nio.charset.StandardCharsets

/**
 * NOTE: Phase 2 USB extraction.
 * This class was moved from com.oralvis.oralviscamera.usbserial with behavior unchanged.
 *
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

        // CDC ACM control requests (USB CDC 1.1) — must match Windows/Python (DtrEnable = true)
        private const val USB_RECIP_INTERFACE = 0x01
        private const val USB_TYPE_CLASS = 0x20
        private const val USB_DIR_OUT = 0x00
        private const val CDC_REQUEST_TYPE = USB_DIR_OUT or USB_TYPE_CLASS or USB_RECIP_INTERFACE // 0x21
        private const val SET_CONTROL_LINE_STATE = 0x22
        private const val SET_LINE_CODING = 0x20
        private const val DTR_MASK = 0x0001
        private const val CONTROL_TRANSFER_TIMEOUT_MS = 500

        /** Tag for filtering all received commands in logcat (matches Python "[RECEIVED]"). */
        private const val LOG_RECEIVED = "CDC_RECEIVED"
    }

    private var connection: UsbDeviceConnection? = null
    private var readThread: Thread? = null
    @Volatile private var isRunning = false
    /** When false, we do not close the connection in stop() (e.g. camera owns it). */
    private var closeConnectionOnStop = true

    /**
     * Open the USB connection.
     * @param existingConnection Already-open UsbDeviceConnection for this device
     * @param closeConnectionOnStop true to close the connection in stop(); false when caller owns it (e.g. camera)
     * @return true if connection established successfully
     */
    fun openWithExistingConnection(existingConnection: UsbDeviceConnection, closeConnectionOnStop: Boolean = true): Boolean {
        this.closeConnectionOnStop = closeConnectionOnStop
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

            // FIX 1 — Enable DTR (required for hardware to emit data; matches Windows/Python DtrEnable = true)
            val cdcCommInterface = findCdcCommInterface()
            if (cdcCommInterface != null) {
                enableDtr(existingConnection, cdcCommInterface)
                setLineCoding(existingConnection, cdcCommInterface)
            } else {
                Log.w(TAG, "CDC COMM interface not found — DTR not set; hardware may not send data")
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
     * Find CDC ACM COMM interface (class 2, subclass 2). Used for SET_CONTROL_LINE_STATE and SET_LINE_CODING.
     */
    private fun findCdcCommInterface(): android.hardware.usb.UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == 2 && intf.interfaceSubclass == 2) { // USB_CLASS_CDC, ACM
                Log.i("CDC_TRACE", "CDC COMM (ACM) interface FOUND at index=$i id=${intf.id}")
                return intf
            }
        }
        return null
    }

    /**
     * Send SET_CONTROL_LINE_STATE (0x22) with DTR=1 so hardware emits data (matches Windows/Python DtrEnable = true).
     */
    private fun enableDtr(conn: UsbDeviceConnection, commInterface: android.hardware.usb.UsbInterface) {
        val result = conn.controlTransfer(
            CDC_REQUEST_TYPE,
            SET_CONTROL_LINE_STATE,
            DTR_MASK,
            commInterface.id,
            null,
            0,
            CONTROL_TRANSFER_TIMEOUT_MS
        )
        if (result >= 0) {
            Log.i("CDC_TRACE", "DTR enabled (SET_CONTROL_LINE_STATE) | interfaceId=${commInterface.id}")
            Log.i(TAG, "✅ DTR enabled — hardware can now send CAPTURE/UV/RGB")
        } else {
            Log.e("CDC_TRACE", "SET_CONTROL_LINE_STATE failed | result=$result")
            Log.w(TAG, "DTR enable failed (result=$result) — hardware may not send data")
        }
    }

    /**
     * Send SET_LINE_CODING for 115200 8N1 (optional; mirrors Windows line settings).
     */
    private fun setLineCoding(conn: UsbDeviceConnection, commInterface: android.hardware.usb.UsbInterface) {
        val lineCoding = ByteArray(7).apply {
            // dwDTERate: 115200 little-endian
            this[0] = (BAUD_RATE and 0xFF).toByte()
            this[1] = ((BAUD_RATE shr 8) and 0xFF).toByte()
            this[2] = ((BAUD_RATE shr 16) and 0xFF).toByte()
            this[3] = ((BAUD_RATE shr 24) and 0xFF).toByte()
            this[4] = 0   // bCharFormat (1 stop bit)
            this[5] = 0   // bParityType (none)
            this[6] = DATA_BITS.toByte() // bDataBits (8)
        }
        val result = conn.controlTransfer(
            CDC_REQUEST_TYPE,
            SET_LINE_CODING,
            0,
            commInterface.id,
            lineCoding,
            lineCoding.size,
            CONTROL_TRANSFER_TIMEOUT_MS
        )
        if (result >= 0) {
            Log.i("CDC_TRACE", "SET_LINE_CODING sent | 115200 8N1 | interfaceId=${commInterface.id}")
        } else {
            Log.w(TAG, "SET_LINE_CODING failed (result=$result) — using default line coding")
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

                var readAttempts = 0L
                var lastNoDataLogTime = 0L
                var lastPartialLogTime = 0L
                var lastErrorLogTime = 0L
                val noDataLogIntervalMs = 5000L
                val partialLogIntervalMs = 3000L
                val errorLogIntervalMs = 5000L

                while (isRunning) {
                    try {
                        readAttempts++
                        // Blocking bulk transfer (same as Python readline timeout = 1.0)
                        val length = connection?.bulkTransfer(inEndpoint, buffer, buffer.size, TIMEOUT_MS) ?: -1

                        if (length > 0) {
                            val hexPreview = buffer.take(length).joinToString("") { "%02x".format(it.toInt().and(0xFF)) }.let { if (it.length > 64) it.take(64) + "..." else it }
                            Log.i("CDC_TRACE", "CDC READ bytes=$length hex=$hexPreview")
                            val data = String(buffer, 0, length, StandardCharsets.UTF_8)
                            commandBuffer.append(data)

                            // Process complete commands (newline-terminated, same as Python readline())
                            while (true) {
                                val newlineIndex = commandBuffer.indexOf('\n')
                                if (newlineIndex == -1) break

                                val command = commandBuffer.substring(0, newlineIndex).trim()
                                commandBuffer.delete(0, newlineIndex + 1)

                                if (command.isNotEmpty()) {
                                    Log.i(LOG_RECEIVED, "[RECEIVED] $command")
                                    Log.i(TAG, "[RECEIVED] $command")
                                    try {
                                        onCommandReceived(command)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error processing command '$command': ${e.message}")
                                    }
                                } else {
                                    Log.d("CDC_TRACE", "CDC empty line (multiple newlines), skipped")
                                }
                            }

                            if (commandBuffer.length > BUFFER_SIZE * 2) {
                                Log.w(TAG, "Command buffer too large (${commandBuffer.length} chars), clearing")
                                commandBuffer.setLength(0)
                            }
                        } else if (length == 0) {
                            val now = System.currentTimeMillis()
                            if (now - lastNoDataLogTime >= noDataLogIntervalMs) {
                                Log.d("CDC_TRACE", "CDC READ: no data (timeout/zero length) — read loop active, waiting for hardware")
                                lastNoDataLogTime = now
                            }
                        } else {
                            // length < 0: transient USB read error (e.g. disconnect, timeout).
                            // These happen frequently while the controller is idle, so we
                            // throttle logging heavily and downgrade to DEBUG to avoid log spam.
                            val now = System.currentTimeMillis()
                            if (now - lastErrorLogTime >= errorLogIntervalMs) {
                                Log.d("CDC_TRACE", "CDC READ error (throttled): length=$length")
                                Log.d(TAG, "Bulk transfer error (throttled): $length")
                                lastErrorLogTime = now
                            }
                        }

                        // Log when we have partial data (no newline yet) — occasionally
                        if (commandBuffer.isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            if (now - lastPartialLogTime >= partialLogIntervalMs) {
                                Log.d("CDC_TRACE", "CDC partial buffer (no newline yet): length=${commandBuffer.length} preview='${commandBuffer.toString().take(40).replace(Regex("[^\\x20-\\x7E]"), ".")}'")
                                lastPartialLogTime = now
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

        // Close connection only if we own it (CDC-only device); do not close camera's connection
        val conn = connection
        connection = null
        if (closeConnectionOnStop && conn != null) {
            try {
                conn.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing connection: ${e.message}")
            }
        }

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

