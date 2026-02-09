package com.oralvis.oralviscamera.feature.usb

// NOTE: Phase 2 USB extraction. Behavior unchanged from original usbserial version.

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

class UsbDeviceDetector(
    private val context: Context,
    private val usbManager: UsbManager,
    private val onDeviceAttached: (UsbDevice) -> Unit,
    private val onDeviceDetached: (UsbDevice) -> Unit
) {

    companion object {
        private const val TAG = "UsbDeviceDetector"

        private const val VENDOR_ID = 0x1209
        private const val PRODUCT_ID = 0xC550
    }

    private var isReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)

            if (device != null && isOralVisDevice(device)) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        logDetailedDeviceInfo(device, "ATTACHED")
                        onDeviceAttached(device)
                    }

                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        Log.i(TAG, "OralVis device detached: ${device.deviceName} (VID=${device.vendorId}, PID=${device.productId})")
                        onDeviceDetached(device)
                    }
                }
            }
        }
    }

    private fun isOralVisDevice(device: UsbDevice): Boolean {
        return device.vendorId == VENDOR_ID && device.productId == PRODUCT_ID
    }

    private fun logDetailedDeviceInfo(device: UsbDevice, event: String) {
        Log.i(TAG, "=== ORALVIS DEVICE $event ===")
        Log.i(TAG, "Device Name: ${device.deviceName}")
        Log.i(TAG, "Vendor ID: ${device.vendorId} (0x${device.vendorId.toString(16)})")
        Log.i(TAG, "Product ID: ${device.productId} (0x${device.productId.toString(16)})")
        Log.i(TAG, "Device Class: ${device.deviceClass}")
        Log.i(TAG, "Device Subclass: ${device.deviceSubclass}")
        Log.i(TAG, "Device Protocol: ${device.deviceProtocol}")
        Log.i(TAG, "Serial Number: ${device.serialNumber ?: "N/A"}")
        Log.i(TAG, "Manufacturer: ${device.manufacturerName ?: "N/A"}")
        Log.i(TAG, "Product Name: ${device.productName ?: "N/A"}")

        Log.i(TAG, "Number of Interfaces: ${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            Log.i(TAG, "  Interface $i: Class=${intf.interfaceClass}, Subclass=${intf.interfaceSubclass}, Protocol=${intf.interfaceProtocol}")

            for (j in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(j)
                val direction = if (endpoint.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) "IN" else "OUT"
                val type = when (endpoint.type) {
                    android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                    android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                    android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                    else -> "UNKNOWN"
                }
                Log.i(TAG, "    Endpoint $j: $direction $type, Address=${endpoint.address}, MaxPacketSize=${endpoint.maxPacketSize}")
            }
        }

        Log.i(TAG, "================================")
    }

    fun register() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, filter)
            }

            isReceiverRegistered = true
            Log.d(TAG, "USB device detector registered (API ${android.os.Build.VERSION.SDK_INT})")

            checkForExistingDevice()
        }
    }

    fun unregister() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
                isReceiverRegistered = false
                Log.d(TAG, "USB device detector unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering USB receiver: ${e.message}")
            }
        }
    }

    private fun checkForExistingDevice() {
        val deviceList = usbManager.deviceList
        for ((_, device) in deviceList) {
            if (isOralVisDevice(device)) {
                logDetailedDeviceInfo(device, "ALREADY CONNECTED")
                onDeviceAttached(device)
                break
            }
        }
    }
}

