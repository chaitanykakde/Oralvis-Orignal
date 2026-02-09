package com.oralvis.oralviscamera.feature.usb

/**
 * UsbController
 *
 * Phase 1 (structure-only) pass-through controller that wraps UsbSerialManager.
 * It exposes a very small surface for starting/stopping/destroying the USB
 * serial manager without introducing any new logic.
 */
class UsbController(
    private val usbSerialManager: UsbSerialManager
) {

    fun start() = usbSerialManager.start()

    fun stop() = usbSerialManager.stop()

    fun destroy() = usbSerialManager.destroy()

    fun isConnected(): Boolean = usbSerialManager.isConnected()
}

