package com.oralvis.oralviscamera

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IPlayCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.utils.MediaUtils
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import com.jiangdg.usb.USBMonitor
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.*
import java.io.File
import android.content.Intent
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import com.oralvis.oralviscamera.databinding.ActivityMainBinding
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.MediaRecord
import com.oralvis.oralviscamera.database.Session
import com.oralvis.oralviscamera.session.SessionManager
import com.oralvis.oralviscamera.camera.CameraModePresets
import com.oralvis.oralviscamera.camera.CameraModePreset
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.jiangdg.ausbc.camera.bean.PreviewSize

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private var mCameraClient: MultiCameraClient? = null
    private var mCurrentCamera: MultiCameraClient.ICamera? = null
    private val mCameraMap = hashMapOf<Int, MultiCameraClient.ICamera>()
    private var isRecording = false
    
    // New features
    private lateinit var sessionManager: SessionManager
    private lateinit var mediaDatabase: MediaDatabase
    private var currentMode = "Normal"
    private var settingsBottomSheet: BottomSheetDialog? = null
    
    // Resolution management
    private var availableResolutions = mutableListOf<PreviewSize>()
    private var currentResolution: PreviewSize? = null
    private var resolutionAdapter: ArrayAdapter<String>? = null
    
    // Exposure update throttling
    private var lastExposureUpdate = 0L
    private val exposureUpdateInterval = 100L // Update every 100ms max
    private var pendingExposureValue = -1
    private val exposureUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val exposureUpdateRunnable = Runnable {
        if (pendingExposureValue >= 0) {
            mCurrentCamera?.let { camera ->
                if (camera is CameraUVC) {
                    camera.setExposure(pendingExposureValue)
                    pendingExposureValue = -1
                }
            }
        }
    }
    private var controlsVisible = false
    
    // Recording timer
    private var recordingStartTime = 0L
    private var recordingHandler: android.os.Handler? = null
    private var recordingRunnable: Runnable? = null
    
    companion object {
        private const val REQUEST_PERMISSION = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        // Initialize new features
        sessionManager = SessionManager(this)
        mediaDatabase = MediaDatabase.getDatabase(this)
        
        setupUI()
        checkPermissions()
    }
    
    private fun setupUI() {
        // Camera action buttons
        binding.btnCapture.setOnClickListener {
            capturePhotoWithRetry()
        }
        
        binding.btnRecord.setOnClickListener {
            toggleRecordingWithRetry()
        }
        
        // Toggle camera controls
        binding.btnToggleControls.setOnClickListener {
            toggleCameraControls()
        }
        
        // Reset controls
        binding.btnResetControls.setOnClickListener {
            resetCameraControls()
        }
        
        // Auto control buttons
        binding.btnAutoFocus.setOnClickListener {
            toggleAutoFocus()
        }
        
        binding.btnAutoExposure.setOnClickListener {
            toggleAutoExposure()
        }
        
        binding.btnAutoWhiteBalance.setOnClickListener {
            toggleAutoWhiteBalance()
        }
        
        // Setup camera control seekbars
        setupCameraControlSeekBars()
        
        // New UI elements
        setupNewUI()
        
        // Initialize recording timer
        recordingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    }
    
    private fun setupCameraControlSeekBars() {
        // Brightness control
        binding.seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.txtBrightness.text = progress.toString()
                    mCurrentCamera?.let { camera ->
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
                    mCurrentCamera?.let { camera ->
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
                    mCurrentCamera?.let { camera ->
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
                    mCurrentCamera?.let { camera ->
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
                    mCurrentCamera?.let { camera ->
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
                    mCurrentCamera?.let { camera ->
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
                    mCurrentCamera?.let { camera ->
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
                        mCurrentCamera?.let { camera ->
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
                mCurrentCamera?.let { camera ->
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
    
    private fun setupNewUI() {
        // Settings button only
        binding.btnSettings.setOnClickListener {
            showSettingsBottomSheet()
        }
    }
    
    private fun switchToMode(mode: String) {
        currentMode = mode
        updateModeUI()
        applyModePreset(mode)
    }
    
    private fun updateModeUI() { /* no-op: mode toggles shown in settings panel only */ }
    
    private fun applyModePreset(mode: String) {
        val preset = when (mode) {
            "Normal" -> CameraModePresets.NORMAL
            "Fluorescence" -> CameraModePresets.FLUORESCENCE
            else -> CameraModePresets.NORMAL
        }
        
        mCurrentCamera?.let { camera ->
            if (camera is CameraUVC) {
                try {
                    camera.setAutoExposure(preset.autoExposure)
                    camera.setAutoWhiteBalance(preset.autoWhiteBalance)
                    camera.setContrast(preset.contrast)
                    camera.setSaturation(preset.saturation)
                    camera.setBrightness(preset.brightness)
                    camera.setGamma(preset.gamma)
                    camera.setHue(preset.hue)
                    camera.setSharpness(preset.sharpness)
                    camera.setGain(preset.gain)
                    camera.setExposure(preset.exposure)
                    
                    // Update UI controls
                    updateCameraControlValues()
                    
                    Toast.makeText(this, "Switched to $mode mode", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to apply $mode preset: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun showSettingsBottomSheet() {
        val bottomSheetView = layoutInflater.inflate(R.layout.bottom_sheet_camera_settings, null)
        settingsBottomSheet = BottomSheetDialog(this)
        settingsBottomSheet?.setContentView(bottomSheetView)
        
        // Setup settings controls
        setupSettingsControls(bottomSheetView)
        
        // Setup resolution spinner with retry logic to ensure camera is ready
        setupResolutionSpinnerWithRetry(bottomSheetView, 0)
        
        settingsBottomSheet?.show()
    }
    
    private fun setupSettingsControls(view: View) {
        // Close button
        view.findViewById<View>(R.id.btnCloseSettings).setOnClickListener {
            settingsBottomSheet?.dismiss()
        }
        
        // Mode buttons in settings
        view.findViewById<View>(R.id.btnNormalMode).setOnClickListener {
            switchToMode("Normal")
            settingsBottomSheet?.dismiss()
        }
        
        view.findViewById<View>(R.id.btnFluorescenceMode).setOnClickListener {
            switchToMode("Fluorescence")
            settingsBottomSheet?.dismiss()
        }
        
        // Setup reset button
        view.findViewById<View>(R.id.btnResetSettings).setOnClickListener {
            resetCameraControls()
            settingsBottomSheet?.dismiss()
        }
    }
    
    
    private fun openGallery() {
        val intent = Intent(this, com.oralvis.oralviscamera.gallery.GalleryActivity::class.java)
        startActivity(intent)
    }
    
    private fun loadAvailableResolutions() {
        // Try to get current camera, if not available, wait and retry
        val camera = mCurrentCamera ?: run {
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
                
                // Always update current resolution from camera request to ensure accuracy
                val cameraRequest = (camera as? CameraUVC)?.getCameraRequest()
                if (cameraRequest != null) {
                    val actualResolution = PreviewSize(cameraRequest.previewWidth, cameraRequest.previewHeight)
                    android.util.Log.d("ResolutionManager", "Camera actual resolution: ${actualResolution.width}x${actualResolution.height}")
                    android.util.Log.d("ResolutionManager", "App stored resolution: ${currentResolution?.width}x${currentResolution?.height}")
                    
                    // Update current resolution to match camera's actual resolution
                    currentResolution = actualResolution
                    android.util.Log.d("ResolutionManager", "Updated current resolution to: ${currentResolution?.width}x${currentResolution?.height}")
                } else {
                    android.util.Log.w("ResolutionManager", "Could not get camera request to determine current resolution")
                }
                
                android.util.Log.d("ResolutionManager", "Available resolutions: ${availableResolutions.size}")
                availableResolutions.forEach { res ->
                    android.util.Log.d("ResolutionManager", "Resolution: ${res.width}x${res.height}")
                }
            } else {
                android.util.Log.w("ResolutionManager", "No resolutions available from camera")
                // Set some common default resolutions if camera doesn't provide any
                if (availableResolutions.isEmpty()) {
                    availableResolutions.addAll(listOf(
                        PreviewSize(1920, 1080),
                        PreviewSize(1280, 720),
                        PreviewSize(640, 480)
                    ))
                    currentResolution = PreviewSize(640, 480)
                    android.util.Log.d("ResolutionManager", "Using default resolutions")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ResolutionManager", "Failed to load resolutions: ${e.message}")
        }
    }
    
    private fun setupResolutionSpinnerWithRetry(view: View, retryCount: Int) {
        val maxRetries = 5
        val retryDelay = 500L // 500ms between retries
        
        android.util.Log.d("ResolutionManager", "setupResolutionSpinnerWithRetry: attempt ${retryCount + 1}/$maxRetries")
        android.util.Log.d("ResolutionManager", "Current camera state: mCurrentCamera = ${mCurrentCamera != null}")
        
        // Try to refresh camera reference if it's null
        if (mCurrentCamera == null) {
            refreshCameraReference()
        }
        
        if (!isCameraReadyForResolutionChange()) {
            if (retryCount < maxRetries) {
                android.util.Log.w("ResolutionManager", "Camera not ready, retrying in ${retryDelay}ms...")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    setupResolutionSpinnerWithRetry(view, retryCount + 1)
                }, retryDelay)
                return
            } else {
                android.util.Log.e("ResolutionManager", "Camera not ready after $maxRetries attempts")
                val txtCurrentResolution = view.findViewById<TextView>(R.id.txtCurrentResolution)
                txtCurrentResolution?.text = "Camera not ready - please try again"
                return
            }
        }
        
        // Camera is ready, proceed with setup
        setupResolutionSpinner(view)
    }
    
    private fun refreshCameraReference() {
        android.util.Log.d("ResolutionManager", "Attempting to refresh camera reference")
        try {
            // Try to find an active camera in the camera map
            if (mCameraMap.isNotEmpty()) {
                val activeCamera = mCameraMap.values.firstOrNull()
                if (activeCamera != null) {
                    mCurrentCamera = activeCamera
                    android.util.Log.d("ResolutionManager", "Refreshed camera reference successfully")
                } else {
                    android.util.Log.w("ResolutionManager", "No active camera found in camera map")
                }
            } else {
                android.util.Log.w("ResolutionManager", "Camera map is empty")
            }
        } catch (e: Exception) {
            android.util.Log.e("ResolutionManager", "Failed to refresh camera reference: ${e.message}")
        }
    }
    
    private fun isCameraReadyForRecording(): Boolean {
        if (mCurrentCamera == null) {
            android.util.Log.d("RecordingManager", "Camera readiness check: mCurrentCamera is null")
            return false
        }
        
        try {
            // Check if camera can provide a camera request (basic functionality)
            val cameraRequest = (mCurrentCamera as? CameraUVC)?.getCameraRequest()
            if (cameraRequest == null) {
                android.util.Log.d("RecordingManager", "Camera readiness check: camera request is null")
                return false
            }
            
            // Check if camera is in a valid state for recording
            val currentWidth = cameraRequest.previewWidth
            val currentHeight = cameraRequest.previewHeight
            val isReady = currentWidth > 0 && currentHeight > 0
            
            android.util.Log.d("RecordingManager", "Camera readiness check: resolution = ${currentWidth}x${currentHeight}, ready = $isReady")
            return isReady
        } catch (e: Exception) {
            android.util.Log.w("RecordingManager", "Camera readiness check failed: ${e.message}")
            return false
        }
    }
    
    private fun isCameraReadyForResolutionChange(): Boolean {
        if (mCurrentCamera == null) {
            android.util.Log.d("ResolutionManager", "Camera readiness check: mCurrentCamera is null")
            return false
        }
        
        try {
            // First check if camera can provide a camera request (basic functionality)
            val cameraRequest = (mCurrentCamera as? CameraUVC)?.getCameraRequest()
            if (cameraRequest == null) {
                android.util.Log.d("ResolutionManager", "Camera readiness check: camera request is null")
                return false
            }
            
            // Then try to get resolutions to verify camera is fully functional
            val resolutions = mCurrentCamera?.getAllPreviewSizes()
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
    
    private fun setupResolutionSpinner(view: View) {
        val spinner = view.findViewById<Spinner>(R.id.spinnerResolution)
        val txtCurrentResolution = view.findViewById<TextView>(R.id.txtCurrentResolution)
        
        // Ensure we have current camera and resolutions loaded
        if (!isCameraReadyForResolutionChange()) {
            txtCurrentResolution.text = "Camera not ready"
            android.util.Log.w("ResolutionManager", "Camera not ready for resolution setup")
            return
        }
        
        // Reload resolutions to ensure we have the latest data
        loadAvailableResolutions()
        
        if (availableResolutions.isEmpty()) {
            txtCurrentResolution.text = "No resolutions available"
            android.util.Log.w("ResolutionManager", "No resolutions available")
            return
        }
        
        // Create resolution strings for spinner
        val resolutionStrings = availableResolutions.map { resolution ->
            "${resolution.width}x${resolution.height}"
        }
        
        // Setup adapter
        resolutionAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            resolutionStrings
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        
        spinner.adapter = resolutionAdapter
        
        // Update current resolution display
        updateCurrentResolutionDisplay(txtCurrentResolution)
        
        // Handle selection changes
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var isInitialSetup = true
            
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                android.util.Log.d("ResolutionManager", "Spinner selection changed to position: $position, isInitialSetup: $isInitialSetup")
                
                if (position >= 0 && position < availableResolutions.size) {
                    val selectedResolution = availableResolutions[position]
                    android.util.Log.d("ResolutionManager", "Selected resolution: ${selectedResolution.width}x${selectedResolution.height}")
                    
                    // Always allow resolution changes, but log if it's initial setup
                    if (isInitialSetup) {
                        android.util.Log.d("ResolutionManager", "Initial setup - setting current resolution display")
                        isInitialSetup = false
                        // Update display but don't change resolution if it's already the current one
                        if (currentResolution?.width == selectedResolution.width && 
                            currentResolution?.height == selectedResolution.height) {
                            android.util.Log.d("ResolutionManager", "Resolution already matches current, skipping change")
                            return
                        }
                    }
                    
                    android.util.Log.d("ResolutionManager", "User selected resolution: ${selectedResolution.width}x${selectedResolution.height}")
                    changeResolution(selectedResolution, txtCurrentResolution)
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
        
        // Set current selection after setting up the listener
        currentResolution?.let { current ->
            val currentIndex = availableResolutions.indexOfFirst { 
                it.width == current.width && it.height == current.height 
            }
            if (currentIndex >= 0) {
                android.util.Log.d("ResolutionManager", "Setting spinner to current resolution index: $currentIndex")
                spinner.setSelection(currentIndex)
            }
        }
        
        android.util.Log.d("ResolutionManager", "Resolution spinner setup completed with ${availableResolutions.size} resolutions")
    }
    
    private fun changeResolution(newResolution: PreviewSize, txtCurrentResolution: TextView) {
        android.util.Log.d("ResolutionManager", "changeResolution called with: ${newResolution.width}x${newResolution.height}")
        android.util.Log.d("ResolutionManager", "Current resolution: ${currentResolution?.width}x${currentResolution?.height}")
        
        if (isRecording) {
            Toast.makeText(this, "Cannot change resolution while recording", Toast.LENGTH_SHORT).show()
            android.util.Log.w("ResolutionManager", "Cannot change resolution while recording")
            return
        }
        
        if (currentResolution?.width == newResolution.width && currentResolution?.height == newResolution.height) {
            android.util.Log.d("ResolutionManager", "Same resolution selected, no change needed")
            Toast.makeText(this, "Resolution already set to ${newResolution.width}x${newResolution.height}", Toast.LENGTH_SHORT).show()
            return
        }
        
        mCurrentCamera?.let { camera ->
            try {
                Toast.makeText(this, "Changing resolution to ${newResolution.width}x${newResolution.height}...", Toast.LENGTH_SHORT).show()
                
                android.util.Log.d("ResolutionManager", "Starting resolution change from ${currentResolution?.width}x${currentResolution?.height} to: ${newResolution.width}x${newResolution.height}")
                
                // Store the new resolution before camera restart
                val previousResolution = currentResolution
                currentResolution = newResolution
                updateCurrentResolutionDisplay(txtCurrentResolution)
                
                // Don't close settings dialog immediately - let user see the change
                
                // Update resolution using the camera's updateResolution method
                // This will close and reopen the camera automatically
                camera.updateResolution(newResolution.width, newResolution.height)
                
                android.util.Log.d("ResolutionManager", "Resolution change initiated successfully")
                
                // Show success message after a delay to let the camera restart
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runOnUiThread {
                        Toast.makeText(this, "Resolution changed to ${newResolution.width}x${newResolution.height}", Toast.LENGTH_SHORT).show()
                        // Don't close settings dialog automatically - let user close it manually
                        // This allows them to make multiple resolution changes if needed
                    }
                }, 2000)
                
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to change resolution: ${e.message}", Toast.LENGTH_SHORT).show()
                android.util.Log.e("ResolutionManager", "Failed to change resolution", e)
                
                // Reset to previous resolution on error
                loadAvailableResolutions()
                updateCurrentResolutionDisplay(txtCurrentResolution)
            }
        } ?: run {
            Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show()
            android.util.Log.w("ResolutionManager", "Camera not available for resolution change")
        }
    }
    
    private fun updateCurrentResolutionDisplay(txtCurrentResolution: TextView) {
        currentResolution?.let { resolution ->
            txtCurrentResolution.text = "Current: ${resolution.width}x${resolution.height}"
        } ?: run {
            txtCurrentResolution.text = "Current: Not set"
        }
    }
    
    private fun verifyCurrentResolution() {
        mCurrentCamera?.let { camera ->
            try {
                val cameraRequest = (camera as? CameraUVC)?.getCameraRequest()
                if (cameraRequest != null) {
                    val actualResolution = PreviewSize(cameraRequest.previewWidth, cameraRequest.previewHeight)
                    android.util.Log.d("ResolutionManager", "Camera actual resolution: ${actualResolution.width}x${actualResolution.height}")
                    android.util.Log.d("ResolutionManager", "App current resolution: ${currentResolution?.width}x${currentResolution?.height}")
                    
                    // Update current resolution to match actual camera resolution
                    currentResolution = actualResolution
                } else {
                    android.util.Log.w("ResolutionManager", "Could not get camera request to verify resolution")
                }
            } catch (e: Exception) {
                android.util.Log.e("ResolutionManager", "Failed to verify current resolution: ${e.message}")
            }
        }
    }
    
    private fun refreshResolutionSpinnerIfOpen() {
        // This method refreshes the resolution spinner if the settings dialog is currently open
        settingsBottomSheet?.let { dialog ->
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
    
    private fun checkPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            Toast.makeText(this, "Requesting permissions: ${missingPermissions.joinToString(", ")}", Toast.LENGTH_SHORT).show()
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_PERMISSION)
        } else {
            Toast.makeText(this, "All permissions already granted!", Toast.LENGTH_SHORT).show()
            initializeCamera()
        }
    }
    
    private fun initializeCamera() {
        binding.statusText.text = "Initializing camera..."
        
        mCameraClient = MultiCameraClient(this, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                device ?: return
                if (mCameraMap.containsKey(device.deviceId)) {
                    return
                }
                // Create camera instance and store in map
                val camera = CameraUVC(this@MainActivity, device)
                mCameraMap[device.deviceId] = camera
                
                binding.statusText.text = "USB Camera detected - Requesting permission..."
                
                // Request permission for the USB device
                mCameraClient?.requestPermission(device)
            }
            
            override fun onDetachDec(device: UsbDevice?) {
                mCameraMap.remove(device?.deviceId)
                mCurrentCamera = null
                binding.statusText.text = "USB Camera removed"
                binding.statusText.visibility = View.VISIBLE
            }
            
            override fun onConnectDev(device: UsbDevice?, ctrlBlock: com.jiangdg.usb.USBMonitor.UsbControlBlock?) {
                device ?: return
                ctrlBlock ?: return
                
                binding.statusText.text = "Camera connected - Opening camera..."
                
                // Get camera from map and set control block
                mCameraMap[device.deviceId]?.apply {
                    setUsbControlBlock(ctrlBlock)
                }?.also { camera ->
                    mCurrentCamera = camera
                    
                    // Set camera state callback
                    camera.setCameraStateCallBack(object : ICameraStateCallBack {
                        override fun onCameraState(
                            self: MultiCameraClient.ICamera,
                            code: ICameraStateCallBack.State,
                            msg: String?
                        ) {
                            runOnUiThread {
                                when (code) {
                                    ICameraStateCallBack.State.OPENED -> {
                                        binding.statusText.text = "Camera opened successfully"
                                        binding.statusText.visibility = View.GONE
                                        
                                        // Set preview aspect ratio to match device screen to fill fully
                                        val displayMetrics = resources.displayMetrics
                                        val screenWidth = displayMetrics.widthPixels
                                        val screenHeight = displayMetrics.heightPixels
                                        binding.cameraTextureView.setAspectRatio(screenWidth, screenHeight)
                                        
                        // Load available resolutions and set current resolution
                        loadAvailableResolutions()
                                        updateCameraControlValues()
                        
                        // Verify and log current resolution after camera restart
                        verifyCurrentResolution()
                        
                        // Refresh resolution list if settings dialog is open
                        refreshResolutionSpinnerIfOpen()
                                    }
                                    ICameraStateCallBack.State.CLOSED -> {
                                        binding.statusText.text = "Camera closed"
                                        binding.statusText.visibility = View.VISIBLE
                                        // Clear resolution data when camera is closed
                                        availableResolutions.clear()
                                        android.util.Log.d("ResolutionManager", "Camera closed - cleared resolution data")
                                        
                                        // Reset recording state when camera is closed
                                        if (isRecording) {
                                            android.util.Log.d("RecordingManager", "Camera closed during recording - resetting recording state")
                                            isRecording = false
                                            binding.btnRecord.text = "Record"
                                            binding.btnRecord.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_light))
                                            stopRecordingTimer()
                                        }
                                    }
                                    ICameraStateCallBack.State.ERROR -> {
                                        binding.statusText.text = "Camera error: $msg"
                                        binding.statusText.visibility = View.VISIBLE
                                    }
                                }
                            }
                        }
                    })
                    
                    // Create camera request with resolution matching screen aspect ratio
                    val displayMetrics = resources.displayMetrics
                    val screenWidth = displayMetrics.widthPixels
                    val screenHeight = displayMetrics.heightPixels
                    val screenAspectRatio = screenWidth.toFloat() / screenHeight.toFloat()
                    
                    // Calculate recording resolution that matches screen aspect ratio
                    val baseWidth = 1920 // Use higher base resolution for better quality
                    val recordingWidth = baseWidth
                    val recordingHeight = (baseWidth / screenAspectRatio).toInt()
                    
                    // Log resolution info for debugging
                    android.util.Log.d("CameraResolution", "Screen: ${screenWidth}x${screenHeight} (${String.format("%.2f", screenAspectRatio)})")
                    android.util.Log.d("CameraResolution", "Camera Request: ${recordingWidth}x${recordingHeight} (${String.format("%.2f", recordingWidth.toFloat() / recordingHeight.toFloat())})")
                    android.util.Log.d("CameraResolution", "Expected Video: ${recordingWidth}x${recordingHeight} (${String.format("%.2f", recordingWidth.toFloat() / recordingHeight.toFloat())})")
                    
                    val cameraRequest = CameraRequest.Builder()
                        .setPreviewWidth(recordingWidth)
                        .setPreviewHeight(recordingHeight)
                        .setRenderMode(CameraRequest.RenderMode.OPENGL)
                        .setDefaultRotateType(RotateType.ANGLE_0)
                        .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO)
                        .create()
                    
                    // Open camera with texture view
                    camera.openCamera(binding.cameraTextureView, cameraRequest)
                }
            }
            
            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: com.jiangdg.usb.USBMonitor.UsbControlBlock?) {
                mCurrentCamera?.closeCamera()
                mCurrentCamera = null
                binding.statusText.text = "Camera disconnected"
                binding.statusText.visibility = View.VISIBLE
            }
            
            override fun onCancelDev(device: UsbDevice?) {
                binding.statusText.text = "Permission denied"
            }
        })
        
        mCameraClient?.register()
    }
    
    private fun updateCameraControlValues() {
        mCurrentCamera?.let { camera ->
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
                    binding.btnAutoFocus.text = if (autoFocus) "Auto Focus ON" else "Auto Focus OFF"
                    binding.btnAutoFocus.setBackgroundColor(
                        ContextCompat.getColor(this, if (autoFocus) android.R.color.holo_green_dark else android.R.color.darker_gray)
                    )
                    
                    // Auto Exposure control (Now working with reflection!)
                    if (camera.isAutoExposureSupported()) {
                        val autoExposure = camera.getAutoExposure()
                        binding.btnAutoExposure.text = if (autoExposure) "Auto Exposure ON" else "Auto Exposure OFF"
                        binding.btnAutoExposure.setBackgroundColor(
                            ContextCompat.getColor(this, if (autoExposure) android.R.color.holo_green_dark else android.R.color.darker_gray)
                        )
                    } else {
                        binding.btnAutoExposure.text = "Auto Exposure (N/A)"
                        binding.btnAutoExposure.setBackgroundColor(
                            ContextCompat.getColor(this, android.R.color.darker_gray)
                        )
                    }
                    
                    val autoWB = camera.getAutoWhiteBalance() ?: false
                    binding.btnAutoWhiteBalance.text = if (autoWB) "Auto WB ON" else "Auto WB OFF"
                    binding.btnAutoWhiteBalance.setBackgroundColor(
                        ContextCompat.getColor(this, if (autoWB) android.R.color.holo_green_dark else android.R.color.darker_gray)
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
    
    private fun setDefaultCameraControlValues() {
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
        binding.btnAutoFocus.text = "Auto Focus"
        binding.btnAutoFocus.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        
        binding.btnAutoExposure.text = "Auto Exposure"
        binding.btnAutoExposure.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        
        binding.btnAutoWhiteBalance.text = "Auto WB"
        binding.btnAutoWhiteBalance.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
    }
    
    private fun capturePhotoWithRetry() {
        capturePhotoWithRetry(0)
    }
    
    private fun capturePhotoWithRetry(retryCount: Int) {
        val maxRetries = 3
        val retryDelay = 1000L // 1 second between retries
        
        android.util.Log.d("PhotoManager", "capturePhotoWithRetry: attempt ${retryCount + 1}/$maxRetries")
        
        // Try to refresh camera reference if it's null
        if (mCurrentCamera == null) {
            android.util.Log.d("PhotoManager", "Camera reference is null, attempting to refresh...")
            refreshCameraReference()
        }
        
        // Check if camera is ready for photo capture
        if (!isCameraReadyForPhotoCapture()) {
            if (retryCount < maxRetries) {
                android.util.Log.w("PhotoManager", "Camera not ready, retrying in ${retryDelay}ms...")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    capturePhotoWithRetry(retryCount + 1)
                }, retryDelay)
                return
            } else {
                android.util.Log.e("PhotoManager", "Camera not ready after $maxRetries attempts")
                Toast.makeText(this, "Camera not ready for photo capture - please try again", Toast.LENGTH_SHORT).show()
                return
            }
        }
        
        // Camera is ready, proceed with photo capture
        capturePhoto()
    }
    
    private fun isCameraReadyForPhotoCapture(): Boolean {
        if (mCurrentCamera == null) {
            android.util.Log.d("PhotoManager", "Camera readiness check: mCurrentCamera is null")
            return false
        }
        
        try {
            // Check if camera can provide a camera request (basic functionality)
            val cameraRequest = (mCurrentCamera as? CameraUVC)?.getCameraRequest()
            if (cameraRequest == null) {
                android.util.Log.d("PhotoManager", "Camera readiness check: camera request is null")
                return false
            }
            
            // Check if camera is in a valid state for photo capture
            val currentWidth = cameraRequest.previewWidth
            val currentHeight = cameraRequest.previewHeight
            val isReady = currentWidth > 0 && currentHeight > 0
            
            android.util.Log.d("PhotoManager", "Camera readiness check: resolution = ${currentWidth}x${currentHeight}, ready = $isReady")
            return isReady
        } catch (e: Exception) {
            android.util.Log.w("PhotoManager", "Camera readiness check failed: ${e.message}")
            return false
        }
    }
    
    private fun capturePhoto() {
        // Try to refresh camera reference if it's null (same as recording management)
        if (mCurrentCamera == null) {
            android.util.Log.d("PhotoManager", "Camera reference is null, attempting to refresh...")
            refreshCameraReference()
        }
        
        // Check if camera is ready for photo capture
        if (!isCameraReadyForPhotoCapture()) {
            android.util.Log.w("PhotoManager", "Camera not ready for photo capture")
            Toast.makeText(this, "Camera not ready for photo capture - please try again", Toast.LENGTH_SHORT).show()
            return
        }
        
        mCurrentCamera?.let { camera ->
            try {
                android.util.Log.d("PhotoManager", "Starting photo capture...")
                val imagePath = createImageFile()
                camera.captureImage(object : ICaptureCallBack {
                    override fun onBegin() {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Capturing photo...", Toast.LENGTH_SHORT).show()
                            android.util.Log.d("PhotoManager", "Photo capture started")
                        }
                    }
                    
                    override fun onError(error: String?) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Capture failed: $error", Toast.LENGTH_SHORT).show()
                            android.util.Log.e("PhotoManager", "Photo capture failed: $error")
                        }
                    }
                    
                    override fun onComplete(path: String?) {
                        runOnUiThread {
                            val finalPath = path ?: imagePath
                            Toast.makeText(this@MainActivity, "Photo saved: $finalPath", Toast.LENGTH_SHORT).show()
                            // Log to database
                            logMediaToDatabase(finalPath, "Image")
                            android.util.Log.d("PhotoManager", "Photo capture completed: $finalPath")
                        }
                    }
                }, imagePath)
            } catch (e: Exception) {
                android.util.Log.e("PhotoManager", "Photo capture failed with exception: ${e.message}")
                Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            android.util.Log.w("PhotoManager", "Camera not available for photo capture")
            Toast.makeText(this, "Camera not connected", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun createVideoFile(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val videoFileName = "OralVis_Video_${currentMode}_$timestamp.mp4"
        
        // Use session-based directory
        val sessionId = sessionManager.getCurrentSessionId()
        val sessionDir = File(getExternalFilesDir(null), "Sessions/$sessionId")
        val videoDir = File(sessionDir, "Videos")
        if (!videoDir.exists()) {
            videoDir.mkdirs()
        }
        
        val videoFile = File(videoDir, videoFileName)
        return videoFile.absolutePath
    }
    
    private fun createImageFile(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "OralVis_Image_${currentMode}_$timestamp.jpg"
        
        // Use session-based directory
        val sessionId = sessionManager.getCurrentSessionId()
        val sessionDir = File(getExternalFilesDir(null), "Sessions/$sessionId")
        val imageDir = File(sessionDir, "Images")
        if (!imageDir.exists()) {
            imageDir.mkdirs()
        }
        
        val imageFile = File(imageDir, imageFileName)
        return imageFile.absolutePath
    }
    
    private fun logMediaToDatabase(filePath: String, mediaType: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Only create session when media is actually captured
                val sessionId = sessionManager.createSessionIfNeeded()
                val fileName = File(filePath).name
                val mediaRecord = MediaRecord(
                    sessionId = sessionId,
                    fileName = fileName,
                    mode = currentMode,
                    mediaType = mediaType,
                    captureTime = Date(),
                    filePath = filePath
                )
                mediaDatabase.mediaDao().insertMedia(mediaRecord)
                
                // Create session in database if it doesn't exist
                createSessionInDatabaseIfNeeded(sessionId)
                
                android.util.Log.d("SessionManager", "Media logged to session: $sessionId")
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to log media: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun createSessionInDatabaseIfNeeded(sessionId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val existingSession = mediaDatabase.sessionDao().getBySessionId(sessionId)
                if (existingSession == null) {
                    val session = Session(
                        sessionId = sessionId,
                        createdAt = Date(),
                        displayName = null
                    )
                    mediaDatabase.sessionDao().insert(session)
                    android.util.Log.d("SessionManager", "Created new session in database: $sessionId")
                }
            } catch (e: Exception) {
                android.util.Log.e("SessionManager", "Failed to create session in database: ${e.message}")
            }
        }
    }
    
    private fun toggleRecordingWithRetry() {
        toggleRecordingWithRetry(0)
    }
    
    private fun toggleRecordingWithRetry(retryCount: Int) {
        val maxRetries = 3
        val retryDelay = 1000L // 1 second between retries
        
        android.util.Log.d("RecordingManager", "toggleRecordingWithRetry: attempt ${retryCount + 1}/$maxRetries")
        
        // Try to refresh camera reference if it's null
        if (mCurrentCamera == null) {
            android.util.Log.d("RecordingManager", "Camera reference is null, attempting to refresh...")
            refreshCameraReference()
        }
        
        // Check if camera is ready for recording
        if (!isCameraReadyForRecording()) {
            if (retryCount < maxRetries) {
                android.util.Log.w("RecordingManager", "Camera not ready, retrying in ${retryDelay}ms...")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    toggleRecordingWithRetry(retryCount + 1)
                }, retryDelay)
                return
            } else {
                android.util.Log.e("RecordingManager", "Camera not ready after $maxRetries attempts")
                Toast.makeText(this, "Camera not ready for recording - please try again", Toast.LENGTH_SHORT).show()
                return
            }
        }
        
        // Camera is ready, proceed with recording
        toggleRecording()
    }
    
    private fun toggleRecording() {
        // Try to refresh camera reference if it's null (same as resolution management)
        if (mCurrentCamera == null) {
            android.util.Log.d("RecordingManager", "Camera reference is null, attempting to refresh...")
            refreshCameraReference()
        }
        
        // Check if camera is ready for recording
        if (!isCameraReadyForRecording()) {
            android.util.Log.w("RecordingManager", "Camera not ready for recording")
            Toast.makeText(this, "Camera not ready for recording - please try again", Toast.LENGTH_SHORT).show()
            return
        }
        
        mCurrentCamera?.let { camera ->
            if (!isRecording) {
                try {
                    android.util.Log.d("RecordingManager", "Starting video recording...")
                    // Create video file in app-specific directory
                    val videoFile = createVideoFile()
                    camera.captureVideoStart(object : ICaptureCallBack {
                        override fun onBegin() {
                            runOnUiThread {
                                isRecording = true
                                binding.btnRecord.text = "Stop Recording"
                                binding.btnRecord.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                                startRecordingTimer()
                                Toast.makeText(this@MainActivity, "Recording started", Toast.LENGTH_SHORT).show()
                                android.util.Log.d("RecordingManager", "Recording started successfully")
                            }
                        }
                        
                        override fun onError(error: String?) {
                            runOnUiThread {
                                isRecording = false
                                binding.btnRecord.text = "Record"
                                binding.btnRecord.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_light))
                                stopRecordingTimer()
                                Toast.makeText(this@MainActivity, "Recording failed: $error", Toast.LENGTH_SHORT).show()
                                android.util.Log.e("RecordingManager", "Recording failed: $error")
                            }
                        }
                        
                        override fun onComplete(path: String?) {
                            runOnUiThread {
                                isRecording = false
                                binding.btnRecord.text = "Record"
                                binding.btnRecord.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_light))
                                stopRecordingTimer()
                                val finalPath = path ?: videoFile
                                Toast.makeText(this@MainActivity, "Video saved: $finalPath", Toast.LENGTH_SHORT).show()
                                // Log to database
                                logMediaToDatabase(finalPath, "Video")
                                android.util.Log.d("RecordingManager", "Recording completed: $finalPath")
                            }
                        }
                    }, videoFile)
                } catch (e: Exception) {
                    android.util.Log.e("RecordingManager", "Recording failed with exception: ${e.message}")
                    Toast.makeText(this, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                try {
                    android.util.Log.d("RecordingManager", "Stopping video recording...")
                    camera.captureVideoStop()
                    isRecording = false
                    binding.btnRecord.text = "Record"
                    binding.btnRecord.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                    stopRecordingTimer()
                    Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
                    android.util.Log.d("RecordingManager", "Recording stopped successfully")
                } catch (e: Exception) {
                    android.util.Log.e("RecordingManager", "Stop recording failed with exception: ${e.message}")
                    stopRecordingTimer()
                    Toast.makeText(this, "Stop recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } ?: run {
            android.util.Log.w("RecordingManager", "Camera not available for recording")
            Toast.makeText(this, "Camera not connected", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun toggleCameraControls() {
        controlsVisible = !controlsVisible
        binding.cameraControlsPanel.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        binding.btnToggleControls.text = if (controlsVisible) "Hide Controls" else "Camera Controls"
    }
    
    private fun resetCameraControls() {
        mCurrentCamera?.let { camera ->
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
                    
                    Toast.makeText(this, "All camera controls reset to default", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Reset failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } ?: run {
            // Reset UI controls to default values when no camera
            setDefaultCameraControlValues()
            Toast.makeText(this, "UI controls reset to default", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_PERMISSION) {
            val deniedPermissions = mutableListOf<String>()
            for (i in permissions.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permissions[i])
                }
            }
            
            if (deniedPermissions.isEmpty()) {
                Toast.makeText(this, "Permissions granted! Initializing camera...", Toast.LENGTH_SHORT).show()
                initializeCamera()
            } else {
                val message = "Denied permissions: ${deniedPermissions.joinToString(", ")}"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                
                // Don't finish the app, just show a message and continue
                binding.statusText.text = "Some permissions denied. App may have limited functionality."
                binding.statusText.visibility = View.VISIBLE
                
                // Still try to initialize camera in case USB camera doesn't need all permissions
                initializeCamera()
            }
        }
    }
    
    private fun startRecordingTimer() {
        recordingStartTime = System.currentTimeMillis()
        binding.recordingTimer.visibility = View.VISIBLE
        
        recordingRunnable = object : Runnable {
            override fun run() {
                val elapsedTime = System.currentTimeMillis() - recordingStartTime
                val seconds = (elapsedTime / 1000) % 60
                val minutes = (elapsedTime / (1000 * 60)) % 60
                val hours = (elapsedTime / (1000 * 60 * 60)) % 24
                
                val timeString = if (hours > 0) {
                    String.format("%02d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format("%02d:%02d", minutes, seconds)
                }
                
                binding.recordingTimer.text = timeString
                recordingHandler?.postDelayed(this, 1000)
            }
        }
        recordingHandler?.post(recordingRunnable!!)
    }
    
    private fun stopRecordingTimer() {
        recordingRunnable?.let { recordingHandler?.removeCallbacks(it) }
        binding.recordingTimer.visibility = View.GONE
        binding.recordingTimer.text = "00:00"
    }
    
    private fun toggleAutoFocus() {
        mCurrentCamera?.let { camera ->
            if (camera is CameraUVC) {
                try {
                    val currentAutoFocus = camera.getAutoFocus() ?: false
                    camera.setAutoFocus(!currentAutoFocus)
                    
                    val newState = !currentAutoFocus
                    binding.btnAutoFocus.text = if (newState) "Auto Focus ON" else "Auto Focus OFF"
                    binding.btnAutoFocus.setBackgroundColor(
                        ContextCompat.getColor(this, if (newState) android.R.color.holo_green_dark else android.R.color.darker_gray)
                    )
                    
                    Toast.makeText(this, "Auto Focus: ${if (newState) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Auto Focus control failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } ?: run {
            Toast.makeText(this, "Camera not connected", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun toggleAutoExposure() {
        mCurrentCamera?.let { camera ->
            if (camera is CameraUVC) {
                try {
                    if (camera.isAutoExposureSupported()) {
                        val currentAutoExposure = camera.getAutoExposure()
                        camera.setAutoExposure(!currentAutoExposure)
                        
                        val newState = !currentAutoExposure
                        binding.btnAutoExposure.text = if (newState) "Auto Exposure ON" else "Auto Exposure OFF"
                        binding.btnAutoExposure.setBackgroundColor(
                            ContextCompat.getColor(this, if (newState) android.R.color.holo_green_dark else android.R.color.darker_gray)
                        )
                        
                        Toast.makeText(this, "Auto Exposure: ${if (newState) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Auto Exposure not supported by this camera", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Auto Exposure control failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } ?: run {
            Toast.makeText(this, "Camera not connected", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun toggleAutoWhiteBalance() {
        mCurrentCamera?.let { camera ->
            if (camera is CameraUVC) {
                try {
                    val currentAutoWB = camera.getAutoWhiteBalance() ?: false
                    camera.setAutoWhiteBalance(!currentAutoWB)
                    
                    val newState = !currentAutoWB
                    binding.btnAutoWhiteBalance.text = if (newState) "Auto WB ON" else "Auto WB OFF"
                    binding.btnAutoWhiteBalance.setBackgroundColor(
                        ContextCompat.getColor(this, if (newState) android.R.color.holo_green_dark else android.R.color.darker_gray)
                    )
                    
                    Toast.makeText(this, "Auto White Balance: ${if (newState) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Auto White Balance control failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } ?: run {
            Toast.makeText(this, "Camera not connected", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Clean up exposure update handler
        exposureUpdateHandler.removeCallbacks(exposureUpdateRunnable)
        
        // Check if current session has any media, if not, clean it up
        cleanupEmptySession()
        
        mCameraClient?.unRegister()
        mCameraClient?.destroy()
        mCameraClient = null
    }
    
    private fun cleanupEmptySession() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentSessionId = sessionManager.getCurrentSessionId()
                if (currentSessionId != null) {
                    // Check if session has any media
                    val mediaCount = mediaDatabase.mediaDao().getMediaCountBySession(currentSessionId)
                    if (mediaCount == 0) {
                        // Session is empty, remove it from database
                        val session = mediaDatabase.sessionDao().getBySessionId(currentSessionId)
                        if (session != null) {
                            mediaDatabase.sessionDao().delete(session)
                            android.util.Log.d("SessionManager", "Cleaned up empty session: $currentSessionId")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SessionManager", "Failed to cleanup empty session: ${e.message}")
            }
        }
    }
}
