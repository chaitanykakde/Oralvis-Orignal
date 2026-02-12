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
        // IMPORTANT:
        // The camera hardware already provides a fluorescence-colored preview.
        // We now only add a *very soft* blue shader effect and DO NOT want
        // aggressive exposure/contrast changes that can turn the image white
        // or laggy after a second.
        //
        // So we keep fluorescence camera parameters almost identical to Normal.
        autoExposure = true,
        autoWhiteBalance = true,
        contrast = 52,   // very small boost compared to Normal
        saturation = 58  // very small tweak, keeps colors natural
    )
}
