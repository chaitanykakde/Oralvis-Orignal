package com.oralvis.oralviscamera.feature.usb

// NOTE: Phase 2 USB extraction. Behavior unchanged from original usbserial version.

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

class UsbPermissionManager(
    private val context: Context,
    private val usbManager: UsbManager,
    private val onPermissionGranted: (UsbDevice) -> Unit,
    private val onPermissionDenied: (UsbDevice) -> Unit
) {

    companion object {
        private const val TAG = "UsbPermissionManager"
        private const val ACTION_USB_PERMISSION = "com.oralvis.USB_PERMISSION"
    }

    private var isReceiverRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                synchronized(this) {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                    if (device != null) {
                        if (granted) {
                            Log.i(TAG, "USB permission granted for device: ${device.deviceName}")
                            onPermissionGranted(device)
                        } else {
                            Log.w(TAG, "USB permission denied for device: ${device.deviceName}")
                            onPermissionDenied(device)
                        }
                    }
                }
            }
        }
    }

    fun register() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter(ACTION_USB_PERMISSION)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(permissionReceiver, filter)
            }

            isReceiverRegistered = true
            Log.d(TAG, "Permission receiver registered (API ${android.os.Build.VERSION.SDK_INT})")
        }
    }

    fun unregister() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(permissionReceiver)
                isReceiverRegistered = false
                Log.d(TAG, "Permission receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering permission receiver: ${e.message}")
            }
        }
    }

    fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "Permission already granted for device: ${device.deviceName}")
            onPermissionGranted(device)
        } else {
            Log.i(TAG, "Requesting USB permission for device: ${device.deviceName}")
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }
}

