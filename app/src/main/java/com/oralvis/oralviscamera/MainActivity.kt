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
import com.oralvis.oralviscamera.databinding.ActivityMainBinding
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.MediaRecord
import com.oralvis.oralviscamera.session.SessionManager
import com.oralvis.oralviscamera.camera.CameraModePresets
import com.oralvis.oralviscamera.camera.CameraModePreset
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
            capturePhoto()
        }
        
        binding.btnRecord.setOnClickListener {
            toggleRecording()
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
        
        // Setup all the seekbars in settings (similar to main controls)
        setupSettingsSeekBars(view)
    }
    
    private fun setupSettingsSeekBars(view: View) {
        // Brightness
        val seekBrightness = view.findViewById<SeekBar>(R.id.seekBrightnessSettings)
        val txtBrightness = view.findViewById<TextView>(R.id.txtBrightnessSettings)
        seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    txtBrightness.text = progress.toString()
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
        
        // Contrast
        val seekContrast = view.findViewById<SeekBar>(R.id.seekContrastSettings)
        val txtContrast = view.findViewById<TextView>(R.id.txtContrastSettings)
        seekContrast.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    txtContrast.text = progress.toString()
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
        
        // Saturation
        val seekSaturation = view.findViewById<SeekBar>(R.id.seekSaturationSettings)
        val txtSaturation = view.findViewById<TextView>(R.id.txtSaturationSettings)
        seekSaturation.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    txtSaturation.text = progress.toString()
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
        
        // Auto controls
        view.findViewById<View>(R.id.btnAutoFocusSettings).setOnClickListener {
            toggleAutoFocus()
        }
        
        view.findViewById<View>(R.id.btnAutoExposureSettings).setOnClickListener {
            toggleAutoExposure()
        }
        
        view.findViewById<View>(R.id.btnAutoWhiteBalanceSettings).setOnClickListener {
            toggleAutoWhiteBalance()
        }
        
        // Reset button
        view.findViewById<View>(R.id.btnResetSettings).setOnClickListener {
            resetCameraControls()
            settingsBottomSheet?.dismiss()
        }
    }
    
    private fun openGallery() {
        val intent = Intent(this, com.oralvis.oralviscamera.gallery.GalleryActivity::class.java)
        startActivity(intent)
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
                                        
                                        updateCameraControlValues()
                                    }
                                    ICameraStateCallBack.State.CLOSED -> {
                                        binding.statusText.text = "Camera closed"
                                        binding.statusText.visibility = View.VISIBLE
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
    
    private fun capturePhoto() {
        mCurrentCamera?.let { camera ->
            try {
                val imagePath = createImageFile()
                camera.captureImage(object : ICaptureCallBack {
                    override fun onBegin() {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Capturing photo...", Toast.LENGTH_SHORT).show()
                        }
                    }
                    
                    override fun onError(error: String?) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Capture failed: $error", Toast.LENGTH_SHORT).show()
                        }
                    }
                    
                    override fun onComplete(path: String?) {
                        runOnUiThread {
                            val finalPath = path ?: imagePath
                            Toast.makeText(this@MainActivity, "Photo saved: $finalPath", Toast.LENGTH_SHORT).show()
                            // Log to database
                            logMediaToDatabase(finalPath, "Image")
                        }
                    }
                }, imagePath)
            } catch (e: Exception) {
                Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
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
                val sessionId = sessionManager.getCurrentSessionId()
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
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to log media: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun toggleRecording() {
        mCurrentCamera?.let { camera ->
            if (!isRecording) {
                try {
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
                            }
                        }
                        
                        override fun onError(error: String?) {
                            runOnUiThread {
                                isRecording = false
                                binding.btnRecord.text = "Record"
                                binding.btnRecord.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_light))
                                stopRecordingTimer()
                                Toast.makeText(this@MainActivity, "Recording failed: $error", Toast.LENGTH_SHORT).show()
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
                            }
                        }
                    }, videoFile)
                } catch (e: Exception) {
                    Toast.makeText(this, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                try {
                    camera.captureVideoStop()
                    isRecording = false
                    binding.btnRecord.text = "Record"
                    binding.btnRecord.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                    stopRecordingTimer()
                    Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    stopRecordingTimer()
                    Toast.makeText(this, "Stop recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } ?: run {
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
        
        mCameraClient?.unRegister()
        mCameraClient?.destroy()
        mCameraClient = null
    }
}
