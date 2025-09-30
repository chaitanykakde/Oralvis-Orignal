package com.oralvis.oralviscamera.camera

data class CameraModePreset(
    val autoExposure: Boolean,
    val autoWhiteBalance: Boolean,
    val contrast: Int,
    val saturation: Int,
    val brightness: Int = 50,
    val gamma: Int = 50,
    val hue: Int = 50,
    val sharpness: Int = 50,
    val gain: Int = 50,
    val exposure: Int = 50
)

object CameraModePresets {
    val NORMAL = CameraModePreset(
        autoExposure = true,
        autoWhiteBalance = true,
        contrast = 50,
        saturation = 60
    )
    
    val FLUORESCENCE = CameraModePreset(
        autoExposure = false,
        autoWhiteBalance = false,
        contrast = 85,
        saturation = 40
    )
}
