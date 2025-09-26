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
import com.oralvis.oralviscamera.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private var mCameraClient: MultiCameraClient? = null
    private var mCurrentCamera: MultiCameraClient.ICamera? = null
    private val mCameraMap = hashMapOf<Int, MultiCameraClient.ICamera>()
    private var isRecording = false
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
        
        // Exposure control (Note: setExposure method doesn't exist in CameraUVC)
        binding.seekExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.txtExposure.text = progress.toString()
                    // Note: Exposure control not available for UVC cameras
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
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
                                        
                                        // Set aspect ratio to fill screen better
                                        binding.cameraTextureView.setAspectRatio(16, 9)
                                        
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
                    
                    // Create camera request with better resolution for full screen
                    val cameraRequest = CameraRequest.Builder()
                        .setPreviewWidth(1280)
                        .setPreviewHeight(720)
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
                    // Note: getExposure, getFocus, getWhiteBalance methods don't exist in CameraUVC
                    // Set default values for these controls
                    binding.seekExposure.progress = 50
                    binding.txtExposure.text = "50"
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
                    
                    // Note: Auto Exposure methods don't exist in CameraUVC
                    binding.btnAutoExposure.text = "Auto Exposure"
                    binding.btnAutoExposure.setBackgroundColor(
                        ContextCompat.getColor(this, android.R.color.darker_gray)
                    )
                    
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
                            Toast.makeText(this@MainActivity, "Photo saved: $path", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            } catch (e: Exception) {
                Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(this, "Camera not connected", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun createVideoFile(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val videoFileName = "OralVis_Video_$timestamp.mp4"
        
        // Use app-specific directory (no permissions needed)
        val videoDir = File(getExternalFilesDir(null), "Videos")
        if (!videoDir.exists()) {
            videoDir.mkdirs()
        }
        
        val videoFile = File(videoDir, videoFileName)
        return videoFile.absolutePath
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
                                Toast.makeText(this@MainActivity, "Video saved: $path", Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
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
                    // Note: resetExposure, resetFocus, resetWhiteBalance methods don't exist in CameraUVC
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
        // Note: Auto Exposure methods don't exist in CameraUVC
        Toast.makeText(this, "Auto Exposure control not available for this camera", Toast.LENGTH_SHORT).show()
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
        
        mCameraClient?.unRegister()
        mCameraClient?.destroy()
        mCameraClient = null
    }
}
