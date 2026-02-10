package com.oralvis.oralviscamera.feature.camera.controls

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.PreviewSize
import com.oralvis.oralviscamera.databinding.ActivityMainBinding
import com.oralvis.oralviscamera.feature.camera.state.CameraStateStore
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * CameraControlCoordinator (Phase 4 — Camera Controls + Resolution Authority Extraction)
 *
 * Owns ALL camera control and resolution logic.
 * MainActivity MUST NOT call CameraUVC setters directly.
 * Logic moved verbatim from MainActivity; behavior unchanged.
 */
class CameraControlCoordinator(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val cameraStateStore: CameraStateStore,
    private val cameraMapProvider: () -> MutableMap<Int, MultiCameraClient.ICamera>,
    private val isRecording: () -> Boolean,
    private val settingsBottomSheetProvider: () -> BottomSheetDialog?,
    private val writeDebugLog: (String, String, String, Map<String, Any>) -> Unit,
    private val refreshCameraReferenceCallback: () -> Unit
) {

    // Exposure throttling state
    private var lastExposureUpdate = 0L
    private val exposureUpdateInterval = 100L
    private var pendingExposureValue = -1
    private val exposureUpdateHandler = Handler(Looper.getMainLooper())
    private val exposureUpdateRunnable = Runnable {
        if (pendingExposureValue >= 0) {
            cameraStateStore.mCurrentCamera?.let { camera ->
                if (camera is CameraUVC) {
                    camera.setExposure(pendingExposureValue)
                    pendingExposureValue = -1
                }
            }
        }
    }

    // Resolution state
    private val availableResolutions = mutableListOf<PreviewSize>()
    var currentResolution: PreviewSize? = null
    var isResolutionChanging = false
    private var resolutionChangeHandler: Handler? = null
    private var resolutionChangeRunnable: Runnable? = null
    private var resolutionChangeSafetyTimeout: Runnable? = null
    private var topSpinnerInitialSetup = true
    private var isProgrammaticSelection = false

    fun cleanupExposureHandler() {
        exposureUpdateHandler.removeCallbacks(exposureUpdateRunnable)
    }

    fun setupCameraControlSeekBars() {
        // Brightness control
        binding.seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.txtBrightness.text = progress.toString()
                    cameraStateStore.mCurrentCamera?.let { camera ->
                        if (camera is CameraUVC) {
                            camera.setBrightness(progress)
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Contrast control
        binding.seekContrast.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.txtContrast.text = progress.toString()
                    cameraStateStore.mCurrentCamera?.let { camera ->
                        if (camera is CameraUVC) {
                            camera.setContrast(progress)
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Saturation control
        binding.seekSaturation.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.txtSaturation.text = progress.toString()
                    cameraStateStore.mCurrentCamera?.let { camera ->
                        if (camera is CameraUVC) {
                            camera.setSaturation(progress)
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Gamma control
        binding.seekGamma.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.txtGamma.text = progress.toString()
                    cameraStateStore.mCurrentCamera?.let { camera ->
                        if (camera is CameraUVC) {
                            camera.setGamma(progress)
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Hue control
        binding.seekHue.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.txtHue.text = progress.toString()
                    cameraStateStore.mCurrentCamera?.let { camera ->
                        if (camera is CameraUVC) {
                            camera.setHue(progress)
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Sharpness control
        binding.seekSharpness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.txtSharpness.text = progress.toString()
                    cameraStateStore.mCurrentCamera?.let { camera ->
                        if (camera is CameraUVC) {
                            camera.setSharpness(progress)
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Gain control
        binding.seekGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.txtGain.text = progress.toString()
                    cameraStateStore.mCurrentCamera?.let { camera ->
                        if (camera is CameraUVC) {
                            camera.setGain(progress)
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Exposure control (Now working with reflection + throttled updates!)
        binding.seekExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.txtExposure.text = progress.toString()
                    
                    // Throttle exposure updates to prevent frame rate drops
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastExposureUpdate >= exposureUpdateInterval) {
                        // Update immediately
                        cameraStateStore.mCurrentCamera?.let { camera ->
                            if (camera is CameraUVC) {
                                camera.setExposure(progress)
                                lastExposureUpdate = currentTime
                            }
                        }
                    } else {
                        // Schedule delayed update
                        pendingExposureValue = progress
                        exposureUpdateHandler.removeCallbacks(exposureUpdateRunnable)
                        exposureUpdateHandler.postDelayed(exposureUpdateRunnable, exposureUpdateInterval)
                    }
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Cancel any pending updates when user starts dragging
                exposureUpdateHandler.removeCallbacks(exposureUpdateRunnable)
                pendingExposureValue = -1
            }
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Ensure final value is applied immediately when user stops dragging
                val finalProgress = seekBar?.progress ?: 0
                cameraStateStore.mCurrentCamera?.let { camera ->
                    if (camera is CameraUVC) {
                        camera.setExposure(finalProgress)
                        lastExposureUpdate = System.currentTimeMillis()
                    }
                }
                // Cancel any pending updates
                exposureUpdateHandler.removeCallbacks(exposureUpdateRunnable)
                pendingExposureValue = -1
            }
        })
        
        // Focus control (Note: setFocus method doesn't exist in CameraUVC)
        binding.seekFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.txtFocus.text = progress.toString()
                    // Note: Manual focus control not available for UVC cameras
                    // Use Auto Focus button instead
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // White Balance control (Note: setWhiteBalance method doesn't exist in CameraUVC)
        binding.seekWhiteBalance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.txtWhiteBalance.text = progress.toString()
                    // Note: Manual white balance control not available for UVC cameras
                    // Use Auto White Balance button instead
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    fun updateCameraControlValues() {
        cameraStateStore.mCurrentCamera?.let { camera ->
            if (camera is CameraUVC) {
                try {
                    // Update all camera control values from actual camera
                    camera.getBrightness()?.let { 
                        binding.seekBrightness.progress = it
                        binding.txtBrightness.text = it.toString()
                    }
                    camera.getContrast()?.let { 
                        binding.seekContrast.progress = it
                        binding.txtContrast.text = it.toString()
                    }
                    camera.getSaturation()?.let { 
                        binding.seekSaturation.progress = it
                        binding.txtSaturation.text = it.toString()
                    }
                    camera.getGamma()?.let { 
                        binding.seekGamma.progress = it
                        binding.txtGamma.text = it.toString()
                    }
                    camera.getHue()?.let { 
                        binding.seekHue.progress = it
                        binding.txtHue.text = it.toString()
                    }
                    camera.getSharpness()?.let { 
                        binding.seekSharpness.progress = it
                        binding.txtSharpness.text = it.toString()
                    }
                    camera.getGain()?.let { 
                        binding.seekGain.progress = it
                        binding.txtGain.text = it.toString()
                    }
                    
                    // Exposure control (Now working with reflection!)
                    if (camera.isExposureSupported()) {
                        val exposure = camera.getExposure()
                        if (exposure >= 0) {
                            binding.seekExposure.progress = exposure
                            binding.txtExposure.text = exposure.toString()
                        } else {
                            binding.seekExposure.progress = 50
                            binding.txtExposure.text = "50"
                        }
                    } else {
                        binding.seekExposure.progress = 50
                        binding.txtExposure.text = "50"
                    }
                    
                    // Note: getFocus, getWhiteBalance methods don't exist in CameraUVC
                    // Set default values for these controls
                    binding.seekFocus.progress = 50
                    binding.txtFocus.text = "50"
                    binding.seekWhiteBalance.progress = 50
                    binding.txtWhiteBalance.text = "50"
                    
                    // Update auto control button states
                    val autoFocus = camera.getAutoFocus() ?: false
                    binding.btnAutoFocus.setBackgroundColor(
                        ContextCompat.getColor(context, if (autoFocus) android.R.color.holo_green_dark else android.R.color.darker_gray)
                    )
                    
                    // Auto Exposure control (Now working with reflection!)
                    if (camera.isAutoExposureSupported()) {
                        val autoExposure = camera.getAutoExposure()
                        binding.btnAutoExposure.setBackgroundColor(
                            ContextCompat.getColor(context, if (autoExposure) android.R.color.holo_green_dark else android.R.color.darker_gray)
                        )
                    } else {
                        binding.btnAutoExposure.setBackgroundColor(
                            ContextCompat.getColor(context, android.R.color.darker_gray)
                        )
                    }
                    
                    val autoWB = camera.getAutoWhiteBalance() ?: false
                    binding.btnAutoWhiteBalance.setBackgroundColor(
                        ContextCompat.getColor(context, if (autoWB) android.R.color.holo_green_dark else android.R.color.darker_gray)
                    )
                    
                } catch (e: Exception) {
                    // If getting values fails, set defaults
                    setDefaultCameraControlValues()
                }
            } else {
                setDefaultCameraControlValues()
            }
        } ?: run {
            setDefaultCameraControlValues()
        }
    }

    fun setDefaultCameraControlValues() {
        // Set default values when camera is not available
        binding.seekBrightness.progress = 50
        binding.txtBrightness.text = "50"
        
        binding.seekContrast.progress = 50
        binding.txtContrast.text = "50"
        
        binding.seekSaturation.progress = 50
        binding.txtSaturation.text = "50"
        
        binding.seekGamma.progress = 50
        binding.txtGamma.text = "50"
        
        binding.seekHue.progress = 50
        binding.txtHue.text = "50"
        
        binding.seekSharpness.progress = 50
        binding.txtSharpness.text = "50"
        
        binding.seekGain.progress = 50
        binding.txtGain.text = "50"
        
        binding.seekExposure.progress = 50
        binding.txtExposure.text = "50"
        
        binding.seekFocus.progress = 50
        binding.txtFocus.text = "50"
        
        binding.seekWhiteBalance.progress = 50
        binding.txtWhiteBalance.text = "50"
        
        // Reset auto control buttons
        binding.btnAutoFocus.setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_green_light))
        binding.btnAutoExposure.setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_green_light))
        binding.btnAutoWhiteBalance.setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_green_light))
    }

    fun toggleAutoFocus() {
        if (!cameraStateStore.isCameraReady) {
            Toast.makeText(context, "Camera not ready - please wait for camera to connect", Toast.LENGTH_SHORT).show()
            return
        }

        cameraStateStore.mCurrentCamera?.let { camera ->
            if (camera is CameraUVC) {
                try {
                    val currentAutoFocus = camera.getAutoFocus() ?: false
                    camera.setAutoFocus(!currentAutoFocus)
                    
                    val newState = !currentAutoFocus
                    binding.btnAutoFocus.setBackgroundColor(
                        ContextCompat.getColor(context, if (newState) android.R.color.holo_green_dark else android.R.color.darker_gray)
                    )
                    
                    Toast.makeText(context, "Auto Focus: ${if (newState) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Auto Focus control failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Camera type not supported", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(context, "Camera not connected", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleAutoExposure() {
        if (!cameraStateStore.isCameraReady) {
            Toast.makeText(context, "Camera not ready - please wait for camera to connect", Toast.LENGTH_SHORT).show()
            return
        }

        cameraStateStore.mCurrentCamera?.let { camera ->
            if (camera is CameraUVC) {
                try {
                    if (camera.isAutoExposureSupported()) {
                        val currentAutoExposure = camera.getAutoExposure()
                        camera.setAutoExposure(!currentAutoExposure)
                        
                        val newState = !currentAutoExposure
                        binding.btnAutoExposure.setBackgroundColor(
                            ContextCompat.getColor(context, if (newState) android.R.color.holo_green_dark else android.R.color.darker_gray)
                        )
                        
                        Toast.makeText(context, "Auto Exposure: ${if (newState) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Auto Exposure not supported by this camera", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Auto Exposure control failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Camera type not supported", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(context, "Camera not connected", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleAutoWhiteBalance() {
        if (!cameraStateStore.isCameraReady) {
            Toast.makeText(context, "Camera not ready - please wait for camera to connect", Toast.LENGTH_SHORT).show()
            return
        }

        cameraStateStore.mCurrentCamera?.let { camera ->
            if (camera is CameraUVC) {
                try {
                    val currentAutoWB = camera.getAutoWhiteBalance() ?: false
                    camera.setAutoWhiteBalance(!currentAutoWB)
                    
                    val newState = !currentAutoWB
                    binding.btnAutoWhiteBalance.setBackgroundColor(
                        ContextCompat.getColor(context, if (newState) android.R.color.holo_green_dark else android.R.color.darker_gray)
                    )
                    
                    Toast.makeText(context, "Auto White Balance: ${if (newState) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Auto White Balance control failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Camera type not supported", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(context, "Camera not connected", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleCameraControls() {
        // This method is now handled by the settings panel
        // controlsVisible = !controlsVisible
        // binding.cameraControlsPanel.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        // binding.btnToggleControls.text = if (controlsVisible) "Hide Controls" else "Camera Controls"
    }

    fun resetCameraControls() {
        if (!cameraStateStore.isCameraReady) {
            Toast.makeText(context, "Camera not ready - please wait for camera to connect", Toast.LENGTH_SHORT).show()
            return
        }

        cameraStateStore.mCurrentCamera?.let { camera ->
            if (camera is CameraUVC) {
                try {
                    // Reset all camera controls
                    camera.resetBrightness()
                    camera.resetContrast()
                    camera.resetSaturation()
                    camera.resetGamma()
                    camera.resetHue()
                    camera.resetSharpness()
                    camera.resetGain()
                    camera.resetExposure()
                    // Note: resetFocus, resetWhiteBalance methods don't exist in CameraUVC
                    camera.resetAutoFocus()
                    
                    // Update UI to reflect reset values
                    updateCameraControlValues()
                    
                    Toast.makeText(context, "All camera controls reset to default", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Reset failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } ?: run {
            // Reset UI controls to default values when no camera
            setDefaultCameraControlValues()
            Toast.makeText(context, "UI controls reset to default", Toast.LENGTH_SHORT).show()
        }
    }

    fun loadAvailableResolutions() {
        // Try to get current camera, if not available, wait and retry
        val camera = cameraStateStore.mCurrentCamera ?: run {
            android.util.Log.w("ResolutionManager", "Camera not available, will retry when camera is ready")
            return
        }
        
        try {
            val resolutions = camera.getAllPreviewSizes()
            if (resolutions?.isNotEmpty() == true) {
                availableResolutions.clear()
                availableResolutions.addAll(resolutions)
                
                // Sort resolutions by total pixels (descending)
                availableResolutions.sortByDescending { it.width * it.height }
                
                // Get camera's actual resolution
                val cameraRequest = (camera as? CameraUVC)?.getCameraRequest()
                if (cameraRequest != null) {
                    val actualResolution = PreviewSize(cameraRequest.previewWidth, cameraRequest.previewHeight)
                    android.util.Log.d("ResolutionManager", "Camera actual resolution: ${actualResolution.width}x${actualResolution.height}")
                    android.util.Log.d("ResolutionManager", "App stored resolution: ${currentResolution?.width}x${currentResolution?.height}")
                    
                    // Update currentResolution from camera's actual resolution (matching reference app approach)
                    currentResolution = actualResolution
                    android.util.Log.d("ResolutionManager", "Updated current resolution to: ${currentResolution?.width}x${currentResolution?.height}")
                } else {
                    android.util.Log.w("ResolutionManager", "Could not get camera request to determine current resolution")
                }
                
                android.util.Log.d("ResolutionManager", "Available resolutions: ${availableResolutions.size}")
                availableResolutions.forEach { res ->
                    android.util.Log.d("ResolutionManager", "Resolution: ${res.width}x${res.height}")
                }
                
                // Update resolution UI (top toolbar)
                updateResolutionUI()
                
                // Settings panel resolution display removed - no longer needed
            } else {
                android.util.Log.w("ResolutionManager", "No resolutions available from camera")
                // Set some common default resolutions if camera doesn't provide any
                // Prioritize higher resolutions - only include 640x480 as absolute last resort
                if (availableResolutions.isEmpty()) {
                    availableResolutions.addAll(listOf(
                        PreviewSize(3840, 2160),  // 4K if supported
                        PreviewSize(1920, 1080),  // Full HD
                        PreviewSize(1280, 720),   // HD
                        PreviewSize(640, 480)     // VGA - only as last resort
                    ))
                    // Use highest resolution as default
                    currentResolution = PreviewSize(1920, 1080)
                    android.util.Log.d("ResolutionManager", "Using default resolutions with fallback: 1920x1080")
                    updateResolutionUI() // Update UI with default resolution
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ResolutionManager", "Failed to load resolutions: ${e.message}")
            // Update UI even on error to show current resolution if available
            updateResolutionUI()
        }
    }

    fun reloadResolutionsWithRetry(
        maxRetries: Int = 5,
        initialDelayMs: Long = 500,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null
    ) {
        android.util.Log.d("ResolutionManager", "reloadResolutionsWithRetry: maxRetries=$maxRetries, initialDelay=$initialDelayMs")
        
        fun attemptLoad(attempt: Int, delayMs: Long) {
            android.util.Log.d("ResolutionManager", "Reload attempt $attempt/$maxRetries (delay: ${delayMs}ms)")
            
            // Check if camera is available and opened
            val camera = cameraStateStore.mCurrentCamera
            val isCameraReadyForCapture = camera != null && (camera as? CameraUVC)?.isCameraOpened() == true
            
            if (!isCameraReadyForCapture) {
                android.util.Log.w("ResolutionManager", "Camera not ready (attempt $attempt/$maxRetries)")
                if (attempt < maxRetries) {
                    // Retry with exponential backoff
                    val nextDelay = delayMs * 2
                    Handler(Looper.getMainLooper()).postDelayed({
                        attemptLoad(attempt + 1, nextDelay)
                    }, delayMs)
                } else {
                    android.util.Log.e("ResolutionManager", "Failed to reload resolutions after $maxRetries attempts - camera not ready")
                    onFailure?.invoke()
                }
                return
            }
            
            // Camera is ready, store in local non-null variable for smart cast
            val readyCamera = camera ?: run {
                android.util.Log.e("ResolutionManager", "Camera became null after ready check")
                onFailure?.invoke()
                return
            }
            
            // Try to load resolutions
            try {
                val resolutions = readyCamera.getAllPreviewSizes()
                if (resolutions?.isNotEmpty() == true) {
                    android.util.Log.d("ResolutionManager", "Successfully reloaded ${resolutions.size} resolutions")
                    loadAvailableResolutions() // This will update UI and spinner
                    onSuccess?.invoke()
                } else {
                    android.util.Log.w("ResolutionManager", "No resolutions available (attempt $attempt/$maxRetries)")
                    if (attempt < maxRetries) {
                        val nextDelay = delayMs * 2
                        Handler(Looper.getMainLooper()).postDelayed({
                            attemptLoad(attempt + 1, nextDelay)
                        }, delayMs)
                    } else {
                        android.util.Log.e("ResolutionManager", "Failed to reload resolutions after $maxRetries attempts - no resolutions")
                        onFailure?.invoke()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ResolutionManager", "Exception loading resolutions (attempt $attempt/$maxRetries): ${e.message}")
                if (attempt < maxRetries) {
                    val nextDelay = delayMs * 2
                    Handler(Looper.getMainLooper()).postDelayed({
                        attemptLoad(attempt + 1, nextDelay)
                    }, delayMs)
                } else {
                    android.util.Log.e("ResolutionManager", "Failed to reload resolutions after $maxRetries attempts - exception")
                    onFailure?.invoke()
                }
            }
        }
        
        // Start first attempt immediately
        attemptLoad(1, initialDelayMs)
    }

    fun updateResolutionUI() {
        currentResolution?.let { resolution ->
            // Update top toolbar resolution selector text
            binding.resolutionSelectorTop.text = "${resolution.width}x${resolution.height}"
            android.util.Log.d("ResolutionSelector", "Updated top toolbar resolution display: ${resolution.width}x${resolution.height}")
        }
    }

    fun isResolutionChangePending(): Boolean {
        val hasPendingHandler = resolutionChangeRunnable != null || resolutionChangeSafetyTimeout != null
        val result = isResolutionChanging || hasPendingHandler
        android.util.Log.d("ResolutionManager", "isResolutionChangePending: flag=$isResolutionChanging, handler=$hasPendingHandler, result=$result")
        return result
    }

    fun setupTopToolbarResolutionSelector() {
        android.util.Log.d("ResolutionSelector", "========================================")
        android.util.Log.d("ResolutionSelector", "setupTopToolbarResolutionSelector() CALLED")
        android.util.Log.d("ResolutionSelector", "Available resolutions: ${availableResolutions.size}")
        
        if (availableResolutions.isEmpty()) {
            android.util.Log.w("ResolutionSelector", "No resolutions available")
            binding.resolutionSelectorTop.text = "N/A"
            binding.resolutionSelectorTop.isEnabled = false
            return
        }
        
        val selector = binding.resolutionSelectorTop
        selector.isEnabled = true
        
        // Update current resolution display
        currentResolution?.let { resolution ->
            selector.text = "${resolution.width}x${resolution.height}"
        } ?: run {
            selector.text = availableResolutions.firstOrNull()?.let { "${it.width}x${it.height}" } ?: "N/A"
        }
        
        // Setup click listener to show popup menu
        selector.setOnClickListener {
            showResolutionPopupMenu(selector)
        }
        
        android.util.Log.d("ResolutionSelector", "Top toolbar resolution selector setup completed")
        android.util.Log.d("ResolutionSelector", "========================================")
    }

    fun showResolutionDropdown() {
        writeDebugLog("A", "MainActivity.kt:840", "showResolutionDropdown() called", mapOf("timestamp" to System.currentTimeMillis()))
        android.util.Log.d("ResolutionClick", "showResolutionDropdown() called")
        // This will be handled by the spinner in the settings panel
        // (MainActivity must call showSettingsPanel() for the old behavior)
    }

    fun showResolutionPopupMenu(anchorView: View) {
        android.util.Log.d("ResolutionSelector", "========================================")
        android.util.Log.d("ResolutionSelector", "showResolutionPopupMenu() CALLED")
        android.util.Log.d("ResolutionSelector", "cameraStateStore.mCurrentCamera: ${cameraStateStore.mCurrentCamera != null}")
        android.util.Log.d("ResolutionSelector", "mCameraMap.size: ${cameraMapProvider().size}")
        android.util.Log.d("ResolutionSelector", "availableResolutions.size: ${availableResolutions.size}")
        android.util.Log.d("ResolutionSelector", "isRecording: ${isRecording()}")
        android.util.Log.d("ResolutionSelector", "isResolutionChanging: $isResolutionChanging")
        
        // Get camera reference - try cameraStateStore.mCurrentCamera first, then fallback to camera map
        // This matches demo app behavior where camera instance is maintained even when closed
        var camera = cameraStateStore.mCurrentCamera
        if (camera == null && cameraMapProvider().isNotEmpty()) {
            android.util.Log.w("ResolutionSelector", "cameraStateStore.mCurrentCamera is null, trying to get from camera map...")
            camera = cameraMapProvider().values.firstOrNull()
            if (camera != null) {
                android.util.Log.d("ResolutionSelector", "Found camera in map, updating cameraStateStore.mCurrentCamera reference")
                cameraStateStore.mCurrentCamera = camera
            }
        }
        
        // If resolutions list is empty, try to reload it first (fallback for edge cases)
        if (availableResolutions.isEmpty()) {
            android.util.Log.w("ResolutionSelector", "Resolutions list is empty, attempting to reload...")
            camera?.let { cam ->
                try {
                    val resolutions = cam.getAllPreviewSizes()
                    if (resolutions?.isNotEmpty() == true) {
                        availableResolutions.clear()
                        availableResolutions.addAll(resolutions)
                        availableResolutions.sortByDescending { it.width * it.height }
                        android.util.Log.d("ResolutionSelector", "Reloaded ${availableResolutions.size} resolutions")
                    } else {
                        android.util.Log.w("ResolutionSelector", "Camera returned no resolutions")
                        Toast.makeText(context, "No resolutions available - camera may not be ready", Toast.LENGTH_SHORT).show()
                        return
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ResolutionSelector", "Failed to reload resolutions: ${e.message}", e)
                    Toast.makeText(context, "Failed to load resolutions: ${e.message}", Toast.LENGTH_SHORT).show()
                    return
                }
            } ?: run {
                android.util.Log.w("ResolutionSelector", "Camera not available to reload resolutions (camera is null)")
                Toast.makeText(context, "Camera not available", Toast.LENGTH_SHORT).show()
                return
            }
        }
        
        // Check if camera is available before showing menu
        if (camera == null) {
            android.util.Log.e("ResolutionSelector", "Camera not available (camera is null) - cannot change resolution")
            Toast.makeText(context, "Camera not available", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (isRecording()) {
            Toast.makeText(context, "Cannot change resolution while recording", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (isResolutionChanging) {
            Toast.makeText(context, "Resolution change in progress, please wait...", Toast.LENGTH_SHORT).show()
            return
        }
        
        android.util.Log.d("ResolutionSelector", "All checks passed - showing popup menu")
        
        val popupMenu = android.widget.PopupMenu(context, anchorView)
        val menu = popupMenu.menu
        
        // Add all available resolutions to menu
        availableResolutions.forEachIndexed { index, resolution ->
            val resolutionString = "${resolution.width}x${resolution.height}"
            val menuItem = menu.add(0, index, 0, resolutionString)
            
            // Mark current resolution as checked
            if (currentResolution?.width == resolution.width && 
                currentResolution?.height == resolution.height) {
                menuItem.isChecked = true
            }
        }
        
        popupMenu.setOnMenuItemClickListener { item ->
            val selectedIndex = item.itemId
            if (selectedIndex >= 0 && selectedIndex < availableResolutions.size) {
                val selectedResolution = availableResolutions[selectedIndex]
                
                // Check if same resolution selected
                if (currentResolution?.width == selectedResolution.width && 
                    currentResolution?.height == selectedResolution.height) {
                    android.util.Log.d("ResolutionSelector", "Same resolution selected - skipping")
                    return@setOnMenuItemClickListener true
                }
                
                // Change resolution (matching demo app - simple direct call)
                android.util.Log.i("ResolutionSelector", "User selected resolution: ${selectedResolution.width}x${selectedResolution.height}")
                changeResolutionSimple(selectedResolution)
            }
            true
        }
        
        popupMenu.show()
    }

    fun changeResolutionSimple(newResolution: PreviewSize) {
        android.util.Log.d("ResolutionChange", "========================================")
        android.util.Log.d("ResolutionChange", "changeResolutionSimple() CALLED")
        android.util.Log.d("ResolutionChange", "Requested resolution: ${newResolution.width}x${newResolution.height}")
        android.util.Log.d("ResolutionChange", "Current resolution: ${currentResolution?.width}x${currentResolution?.height}")
        
        // Check if same resolution
        if (currentResolution?.width == newResolution.width && 
            currentResolution?.height == newResolution.height) {
            android.util.Log.d("ResolutionChange", "Same resolution - skipping")
            return
        }
        
        // Get camera reference - try cameraStateStore.mCurrentCamera first, then fallback to camera map
        // This matches demo app behavior where camera instance is maintained even when closed
        var camera = cameraStateStore.mCurrentCamera
        if (camera == null && cameraMapProvider().isNotEmpty()) {
            android.util.Log.w("ResolutionChange", "cameraStateStore.mCurrentCamera is null, trying to get from camera map...")
            camera = cameraMapProvider().values.firstOrNull()
            if (camera != null) {
                android.util.Log.d("ResolutionChange", "Found camera in map, updating cameraStateStore.mCurrentCamera reference")
                cameraStateStore.mCurrentCamera = camera
            }
        }
        
        if (camera == null) {
            android.util.Log.e("ResolutionChange", "Camera not available (camera is null)")
            android.util.Log.e("ResolutionChange", "mCameraMap.size: ${cameraMapProvider().size}")
            android.util.Log.e("ResolutionChange", "Available resolutions count: ${availableResolutions.size}")
            android.util.Log.e("ResolutionChange", "This might happen if camera was disconnected")
            Toast.makeText(context, "Camera not available", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Log camera state for debugging
        android.util.Log.d("ResolutionChange", "Camera available: ${camera.javaClass.simpleName}")
        android.util.Log.d("ResolutionChange", "isResolutionChanging: $isResolutionChanging")
        
        try {
            // Set flag to prevent concurrent changes and to prevent clearing resolutions list
            isResolutionChanging = true
            android.util.Log.d("ResolutionChange", "Set isResolutionChanging = true")
            
            // Update UI immediately (matching demo app - trust that updateResolution will work)
            currentResolution = newResolution
            binding.resolutionSelectorTop.text = "${newResolution.width}x${newResolution.height}"
            
            // Call updateResolution - MultiCameraClient handles:
            // 1. Closing camera
            // 2. Waiting for native thread cleanup (200ms)
            // 3. Scheduling camera reopen with new resolution (1200ms delay)
            // 4. Opening camera with updated CameraRequest
            android.util.Log.d("ResolutionChange", "Calling camera.updateResolution(${newResolution.width}, ${newResolution.height})")
            camera.updateResolution(newResolution.width, newResolution.height)
            
            Toast.makeText(context, "Changing resolution to ${newResolution.width}x${newResolution.height}...", Toast.LENGTH_SHORT).show()
            
            android.util.Log.d("ResolutionChange", "Resolution change initiated - camera will stop and restart automatically")
            android.util.Log.d("ResolutionChange", "Camera state callbacks will handle resolution verification and flag reset")
            android.util.Log.d("ResolutionChange", "========================================")
            
        } catch (e: Exception) {
            android.util.Log.e("ResolutionChange", "EXCEPTION in changeResolutionSimple:", e)
            Toast.makeText(context, "Failed to change resolution: ${e.message}", Toast.LENGTH_SHORT).show()
            
            // Reset flag on error
            isResolutionChanging = false
            
            // Revert UI on error
            currentResolution?.let { current ->
                binding.resolutionSelectorTop.text = "${current.width}x${current.height}"
            }
        }
    }

    fun setupResolutionSpinnerWithRetry(view: View, retryCount: Int) {
        val maxRetries = 5
        val retryDelay = 500L // 500ms between retries
        
        android.util.Log.d("ResolutionManager", "setupResolutionSpinnerWithRetry: attempt ${retryCount + 1}/$maxRetries")
        android.util.Log.d("ResolutionManager", "Current camera state: cameraStateStore.mCurrentCamera = ${cameraStateStore.mCurrentCamera != null}")
        
        // Try to refresh camera reference if it's null
        if (cameraStateStore.mCurrentCamera == null) {
            refreshCameraReferenceCallback()
        }
        
        if (!isCameraReadyForResolutionChange()) {
            if (retryCount < maxRetries) {
                android.util.Log.w("ResolutionManager", "Camera not ready, retrying in ${retryDelay}ms...")
                Handler(Looper.getMainLooper()).postDelayed({
                    setupResolutionSpinnerWithRetry(view, retryCount + 1)
                }, retryDelay)
                return
            } else {
                android.util.Log.e("ResolutionManager", "Camera not ready after $maxRetries attempts")
                // Settings panel resolution display removed - no longer needed
                return
            }
        }
        
        // Camera is ready, proceed with setup
        setupResolutionSpinner(view)
    }

    fun isCameraReadyForResolutionChange(): Boolean {
        if (cameraStateStore.mCurrentCamera == null) {
            android.util.Log.d("ResolutionManager", "Camera readiness check: cameraStateStore.mCurrentCamera is null")
            return false
        }
        
        try {
            // First check if camera can provide a camera request (basic functionality)
            val cameraRequest = (cameraStateStore.mCurrentCamera as? CameraUVC)?.getCameraRequest()
            if (cameraRequest == null) {
                android.util.Log.d("ResolutionManager", "Camera readiness check: camera request is null")
                return false
            }
            
            // Then try to get resolutions to verify camera is fully functional
            val resolutions = cameraStateStore.mCurrentCamera?.getAllPreviewSizes()
            val isReady = resolutions != null && resolutions.isNotEmpty()
            android.util.Log.d("ResolutionManager", "Camera readiness check: resolutions available = $isReady (count: ${resolutions?.size ?: 0})")
            
            // Additional check: verify the camera can provide current resolution info
            if (isReady) {
                val currentWidth = cameraRequest.previewWidth
                val currentHeight = cameraRequest.previewHeight
                android.util.Log.d("ResolutionManager", "Camera readiness check: current resolution = ${currentWidth}x${currentHeight}")
                return currentWidth > 0 && currentHeight > 0
            }
            
            return false
        } catch (e: Exception) {
            android.util.Log.w("ResolutionManager", "Camera readiness check failed: ${e.message}")
            return false
        }
    }

    fun setupResolutionSpinner(view: View) {
        // Resolution spinner was removed from settings panel
        // This method is kept for compatibility but no longer sets up the spinner
        android.util.Log.d("ResolutionClick", "setupResolutionSpinner() called but spinner was removed from settings panel - returning early")
            return
    }

    fun changeResolution(newResolution: PreviewSize, txtCurrentResolution: TextView) {
        writeDebugLog("D", "MainActivity.kt:1674", "changeResolution() ENTRY", mapOf(
            "requestedWidth" to newResolution.width,
            "requestedHeight" to newResolution.height,
            "currentWidth" to (currentResolution?.width ?: -1),
            "currentHeight" to (currentResolution?.height ?: -1),
            "cameraAvailable" to (cameraStateStore.mCurrentCamera != null),
            "isRecording" to isRecording()
        ))
        android.util.Log.d("ResolutionChange", "========================================")
        android.util.Log.d("ResolutionChange", "changeResolution() CALLED")
        android.util.Log.d("ResolutionChange", "Requested resolution: ${newResolution.width}x${newResolution.height}")
        android.util.Log.d("ResolutionChange", "Current resolution: ${currentResolution?.width}x${currentResolution?.height}")
        android.util.Log.d("ResolutionChange", "Camera available: ${cameraStateStore.mCurrentCamera != null}")
        android.util.Log.d("ResolutionChange", "Is recording: ${isRecording()}")
        Toast.makeText(context, "changeResolution() called for ${newResolution.width}x${newResolution.height}", Toast.LENGTH_SHORT).show()
        
        if (isRecording()) {
            Toast.makeText(context, "Cannot change resolution while recording", Toast.LENGTH_SHORT).show()
            android.util.Log.w("ResolutionChange", "BLOCKED: Cannot change resolution while recording")
            return
        }
        
        // Check if resolution change is already in progress
        if (isResolutionChanging) {
            Toast.makeText(context, "Resolution change in progress, please wait...", Toast.LENGTH_SHORT).show()
            android.util.Log.w("ResolutionChange", "BLOCKED: Resolution change already in progress")
            return
        }
        
        if (currentResolution?.width == newResolution.width && currentResolution?.height == newResolution.height) {
            android.util.Log.d("ResolutionChange", "SKIPPED: Same resolution selected, no change needed")
            Toast.makeText(context, "Resolution already set to ${newResolution.width}x${newResolution.height}", Toast.LENGTH_SHORT).show()
            return
        }
        
        cameraStateStore.mCurrentCamera?.let { camera ->
            try {
                // Set flag to prevent concurrent changes
                isResolutionChanging = true
                android.util.Log.d("ResolutionChange", "Set isResolutionChanging = true")
                
                Toast.makeText(context, "Changing resolution to ${newResolution.width}x${newResolution.height}...", Toast.LENGTH_SHORT).show()
                
                android.util.Log.d("ResolutionChange", "Starting resolution change")
                android.util.Log.d("ResolutionChange", "  FROM: ${currentResolution?.width}x${currentResolution?.height}")
                android.util.Log.d("ResolutionChange", "  TO: ${newResolution.width}x${newResolution.height}")
                
                // Update UI immediately (matching reference app - trust that updateResolution will work)
                currentResolution = newResolution
                updateCurrentResolutionDisplay(txtCurrentResolution)
                updateResolutionUI() // Update top toolbar resolution display
                
                android.util.Log.d("ResolutionChange", "Updated UI state:")
                android.util.Log.d("ResolutionChange", "  currentResolution: ${currentResolution?.width}x${currentResolution?.height}")
                
                // Get camera request before update to see current state
                val cameraRequest = (camera as? CameraUVC)?.getCameraRequest()
                android.util.Log.d("ResolutionChange", "Camera request BEFORE update:")
                android.util.Log.d("ResolutionChange", "  previewWidth: ${cameraRequest?.previewWidth}")
                android.util.Log.d("ResolutionChange", "  previewHeight: ${cameraRequest?.previewHeight}")
                
                // Update resolution using the camera's updateResolution method
                // This will close and reopen the camera automatically
                android.util.Log.d("ResolutionChange", "Calling camera.updateResolution(${newResolution.width}, ${newResolution.height})")
                writeDebugLog("D", "MainActivity.kt:1709", "calling camera.updateResolution()", mapOf(
                    "width" to newResolution.width,
                    "height" to newResolution.height,
                    "cameraType" to camera.javaClass.simpleName
                ))
                Toast.makeText(context, "Calling camera.updateResolution(${newResolution.width}, ${newResolution.height})", Toast.LENGTH_SHORT).show()
                camera.updateResolution(newResolution.width, newResolution.height)
                
                // Cancel any existing handlers from previous resolution changes
                resolutionChangeRunnable?.let { oldRunnable ->
                    resolutionChangeHandler?.removeCallbacks(oldRunnable)
                    android.util.Log.d("ResolutionChange", "Cancelled previous resolution change handler")
                }
                resolutionChangeSafetyTimeout?.let { oldTimeout ->
                    resolutionChangeHandler?.removeCallbacks(oldTimeout)
                    android.util.Log.d("ResolutionChange", "Cancelled previous safety timeout")
                }
                
                // Store handler for tracking
                resolutionChangeHandler = Handler(Looper.getMainLooper())
                val handler = resolutionChangeHandler!!
                
                // Re-enable after delay (camera will reopen)
                resolutionChangeRunnable = object : Runnable {
                    override fun run() {
                        android.util.Log.d("ResolutionChange", "Delayed handler executing - checking camera state")
                        
                        // Clear the runnable reference since we're executing
                        resolutionChangeRunnable = null
                        
                        // Check if camera is actually opened before resetting flag
                        val camera = cameraStateStore.mCurrentCamera
                        val isCameraReadyForCapture = camera != null && (camera as? CameraUVC)?.isCameraOpened() == true
                        
                        if (!isCameraReadyForCapture) {
                            android.util.Log.w("ResolutionChange", "Camera not ready yet, retrying in 1 second...")
                            // Retry after 1 second - store new runnable
                            resolutionChangeRunnable = this
                            handler.postDelayed(this, 1000)
                            return
                        }
                        
                        // Camera is ready, reload resolutions and reset flag
                        android.util.Log.d("ResolutionChange", "Camera is ready, reloading resolutions...")
                        
                        // Reload resolutions with retry before verifying
                        reloadResolutionsWithRetry(
                            maxRetries = 5,
                            initialDelayMs = 500,
                            onSuccess = {
                                android.util.Log.d("ResolutionChange", "Resolutions reloaded successfully")
                                isResolutionChanging = false
                                
                                // CRITICAL: Reset initial setup flag so next selection will work
                                topSpinnerInitialSetup = false
                                android.util.Log.d("ResolutionChange", "Set isResolutionChanging = false, topSpinnerInitialSetup = false")
                                
                                // Clear handler references
                                resolutionChangeRunnable = null
                                resolutionChangeSafetyTimeout = null
                                
                                // Verify resolution after camera reopens
                                verifyCurrentResolution()
                            },
                            onFailure = {
                                android.util.Log.e("ResolutionChange", "Failed to reload resolutions, resetting flag anyway")
                                // Reset flag even on failure to prevent stuck state
                                isResolutionChanging = false
                                
                                // CRITICAL: Reset initial setup flag so next selection will work
                                topSpinnerInitialSetup = false
                                
                                // Clear handler references
                                resolutionChangeRunnable = null
                                resolutionChangeSafetyTimeout = null
                                
                                // Try to reload once more without retry
                                loadAvailableResolutions()
                                verifyCurrentResolution()
                            }
                        )
                    }
                }
                
                handler.postDelayed(resolutionChangeRunnable!!, 2500) // 2.5 seconds to allow camera to fully reopen
                
                // Safety timeout: Force reset flag after 10 seconds to prevent stuck state
                resolutionChangeSafetyTimeout = Runnable {
                    if (isResolutionChanging) {
                        android.util.Log.w("ResolutionChange", "Safety timeout: Forcing isResolutionChanging = false after 10 seconds")
                        isResolutionChanging = false
                        
                        // CRITICAL: Reset initial setup flag so next selection will work
                        topSpinnerInitialSetup = false
                        
                        // Clear handler references
                        resolutionChangeRunnable = null
                        resolutionChangeSafetyTimeout = null
                        
                        // Try to reload resolutions one last time
                        loadAvailableResolutions()
                        verifyCurrentResolution()
                    }
                }
                handler.postDelayed(resolutionChangeSafetyTimeout!!, 10000) // 10 second safety timeout
                
                writeDebugLog("D", "MainActivity.kt:1711", "camera.updateResolution() returned", mapOf(
                    "width" to newResolution.width,
                    "height" to newResolution.height
                ))
                android.util.Log.d("ResolutionChange", "Resolution change initiated - camera will restart in 1 second")
                Toast.makeText(context, "Resolution change initiated - camera restarting...", Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                android.util.Log.e("ResolutionChange", "EXCEPTION in changeResolution:", e)
                Toast.makeText(context, "Failed to change resolution: ${e.message}", Toast.LENGTH_SHORT).show()
                
                // Reset flag on error
                isResolutionChanging = false
                
                // CRITICAL: Reset initial setup flag so next selection will work
                topSpinnerInitialSetup = false
                android.util.Log.d("ResolutionChange", "Set isResolutionChanging = false (error), topSpinnerInitialSetup = false")
                
                // Clear handler references
                resolutionChangeRunnable = null
                resolutionChangeSafetyTimeout = null
                
                // Reload resolutions with retry to restore spinner state
                reloadResolutionsWithRetry(
                    maxRetries = 3,
                    initialDelayMs = 500,
                    onSuccess = {
                        android.util.Log.d("ResolutionChange", "Resolutions reloaded after error")
                        updateCurrentResolutionDisplay(txtCurrentResolution)
                    },
                    onFailure = {
                        android.util.Log.e("ResolutionChange", "Failed to reload resolutions after error")
                        // Still try to reload once more without retry
                        loadAvailableResolutions()
                        updateCurrentResolutionDisplay(txtCurrentResolution)
                    }
                )
            }
        } ?: run {
            android.util.Log.e("ResolutionChange", "FAILED: Camera not available (cameraStateStore.mCurrentCamera is null)")
            Toast.makeText(context, "Camera not available", Toast.LENGTH_SHORT).show()
            android.util.Log.w("ResolutionManager", "Camera not available for resolution change")
        }
    }

    fun updateCurrentResolutionDisplay(txtCurrentResolution: TextView) {
        currentResolution?.let { resolution ->
            txtCurrentResolution.text = "Current: ${resolution.width}x${resolution.height}"
        } ?: run {
            txtCurrentResolution.text = "Current: Not set"
        }
    }

    fun verifyCurrentResolution() {
        android.util.Log.d("ResolutionManager", "========================================")
        android.util.Log.d("ResolutionManager", "verifyCurrentResolution() CALLED")
        cameraStateStore.mCurrentCamera?.let { camera ->
            try {
                val cameraRequest = (camera as? CameraUVC)?.getCameraRequest()
                if (cameraRequest != null) {
                    val actualResolution = PreviewSize(cameraRequest.previewWidth, cameraRequest.previewHeight)
                    android.util.Log.d("ResolutionManager", "Camera actual resolution: ${actualResolution.width}x${actualResolution.height}")
                    android.util.Log.d("ResolutionManager", "App current resolution: ${currentResolution?.width}x${currentResolution?.height}")
                    
                    // Update currentResolution from camera's actual resolution (matching reference app approach)
                    val previousResolution = currentResolution
                    currentResolution = actualResolution
                    android.util.Log.d("ResolutionManager", "Updated current resolution to: ${currentResolution?.width}x${currentResolution?.height}")
                    
                    // Update UI with verified resolution (updates top toolbar spinner)
                    updateResolutionUI()
                    
                    // Log if resolution changed
                    val widthChanged = previousResolution?.width != actualResolution.width
                    val heightChanged = previousResolution?.height != actualResolution.height
                    if (widthChanged || heightChanged) {
                        android.util.Log.i("ResolutionManager", "Resolution changed: ${previousResolution?.width}x${previousResolution?.height} -> ${actualResolution.width}x${actualResolution.height}")
                    } else {
                        // Resolution unchanged - no action needed
                    }
                } else {
                    android.util.Log.w("ResolutionManager", "Could not get camera request to verify resolution")
                }
            } catch (e: Exception) {
                android.util.Log.e("ResolutionManager", "Failed to verify current resolution: ${e.message}", e)
            }
        } ?: run {
            android.util.Log.w("ResolutionManager", "Camera not available for resolution verification")
        }
        android.util.Log.d("ResolutionManager", "========================================")
    }

    fun refreshResolutionSpinnerIfOpen() {
        // This method refreshes the resolution spinner if the settings dialog is currently open
        settingsBottomSheetProvider()?.let { dialog ->
            if (dialog.isShowing) {
                try {
                    val bottomSheetView = dialog.findViewById<View>(android.R.id.content)
                    bottomSheetView?.let { view ->
                        android.util.Log.d("ResolutionManager", "Refreshing resolution spinner after camera restart")
                        setupResolutionSpinnerWithRetry(view, 0)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ResolutionManager", "Failed to refresh resolution spinner: ${e.message}")
                }
            }
        }
    }

    fun onCameraDetached() {
        // Reset resolution changing flag if camera detaches during resolution change
        if (isResolutionChanging) {
            android.util.Log.w("ResolutionManager", "Camera detached during resolution change - resetting flag")
            isResolutionChanging = false
            // Clear resolutions since camera is gone
            availableResolutions.clear()
        }
    }

    fun onCameraOpened() {
        // loadAvailableResolutions is called from camera opened callback in MainActivity
        if (isResolutionChanging) {
            android.util.Log.d("ResolutionManager", "Camera opened after resolution change - resetting flag")
            isResolutionChanging = false
        }
    }
}
