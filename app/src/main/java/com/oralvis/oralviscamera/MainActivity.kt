package com.oralvis.oralviscamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import java.io.FileOutputStream
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IPlayCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.utils.CameraUtils
import com.jiangdg.ausbc.utils.MediaUtils
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import com.jiangdg.usb.USBMonitor
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.*
import java.io.File
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.ImageView
import android.view.ViewGroup
import com.oralvis.oralviscamera.databinding.ActivityMainBinding
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.MediaRecord
import com.oralvis.oralviscamera.database.MediaRepository
import com.oralvis.oralviscamera.database.Session
import com.oralvis.oralviscamera.database.Patient
import com.oralvis.oralviscamera.database.PatientDao
import com.oralvis.oralviscamera.session.SessionManager
import com.oralvis.oralviscamera.feature.session.SessionController
import com.oralvis.oralviscamera.home.PatientPickerAdapter
import com.oralvis.oralviscamera.AddPatientActivity
import com.oralvis.oralviscamera.session.SessionMedia
import com.oralvis.oralviscamera.session.SessionMediaAdapter
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jiangdg.ausbc.camera.bean.PreviewSize
import com.oralvis.oralviscamera.feature.guided.SessionBridge
import com.oralvis.oralviscamera.feature.guided.GuidedController
import kotlin.math.abs
import com.oralvis.oralviscamera.feature.usb.CameraCommandReceiver
import com.oralvis.oralviscamera.feature.usb.UsbSerialManager
import com.oralvis.oralviscamera.feature.usb.UsbController
import com.oralvis.oralviscamera.ui.main.MainUiBinder
import com.oralvis.oralviscamera.feature.camera.state.CameraStateStore
import com.oralvis.oralviscamera.feature.camera.lifecycle.CameraLifecycleManager
import com.oralvis.oralviscamera.feature.camera.preview.PreviewSurfaceManager
import com.oralvis.oralviscamera.feature.camera.preview.BasePreviewCallbackProvider
import com.oralvis.oralviscamera.feature.camera.preview.PreviewCallbackRouter
import com.oralvis.oralviscamera.feature.camera.capture.PhotoCaptureHost
import com.oralvis.oralviscamera.feature.camera.capture.PhotoCaptureHandler
import com.oralvis.oralviscamera.feature.camera.capture.VideoCaptureHost
import com.oralvis.oralviscamera.feature.camera.capture.VideoCaptureHandler
import com.oralvis.oralviscamera.feature.camera.mode.CameraMode
import com.oralvis.oralviscamera.feature.camera.mode.CameraModeController
import com.oralvis.oralviscamera.feature.camera.mode.CameraModeUi
import com.oralvis.oralviscamera.feature.camera.mode.FluorescenceModeAdapter
import com.oralvis.oralviscamera.feature.camera.startup.CameraStartupCoordinator
import com.oralvis.oralviscamera.feature.session.flow.SessionFlowCoordinator
import com.oralvis.oralviscamera.feature.camera.controls.CameraControlCoordinator
import com.oralvis.oralviscamera.GlobalPatientManager

class MainActivity : AppCompatActivity(), CameraCommandReceiver, PhotoCaptureHost, VideoCaptureHost {
    
    // #region agent log
    private fun writeDebugLog(hypothesisId: String, location: String, message: String, data: Map<String, Any> = emptyMap()) {
        try {
            // Try workspace path first (for development), fallback to app files directory
            val workspaceLog = File("c:\\Users\\Chaitany Kakde\\StudioProjects\\Oralvis-Orignal\\.cursor\\debug.log")
            val appLog = File(getExternalFilesDir(null), "debug.log")
            val logFile = if (workspaceLog.parentFile?.exists() == true) workspaceLog else appLog
            
            val logEntry = org.json.JSONObject().apply {
                put("sessionId", "debug-session")
                put("runId", "run1")
                put("hypothesisId", hypothesisId)
                put("location", location)
                put("message", message)
                put("timestamp", System.currentTimeMillis())
                put("data", org.json.JSONObject(data))
            }
            logFile.parentFile?.mkdirs()
            logFile.appendText(logEntry.toString() + "\n")
            android.util.Log.d("DebugLog", "[$hypothesisId] $location: $message")
        } catch (e: Exception) {
            android.util.Log.e("DebugLog", "Failed to write debug log", e)
        }
    }
    // #endregion
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var uiBinder: MainUiBinder
    private var mCameraClient: MultiCameraClient? = null
    private val mCameraMap = hashMapOf<Int, MultiCameraClient.ICamera>()

    // Mode switching (Phase 5D — extracted to CameraModeController + FluorescenceModeAdapter)
    private val fluorescenceModeAdapter = FluorescenceModeAdapter(this)
    private lateinit var cameraModeController: CameraModeController
    private var isUpdatingModeToggleFromCode = false
    private val cameraModeUi = object : CameraModeUi {
        override fun updateCameraControlValues() { cameraControlCoordinator.updateCameraControlValues() }
        override fun setDefaultCameraControlValues() { cameraControlCoordinator.setDefaultCameraControlValues() }
        override fun showToast(message: String) { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
        override fun syncModeToggle(mode: CameraMode) { syncModeToggleWithController(mode) }
    }

    // Camera lifecycle (Phase 5A — extracted to CameraStateStore + CameraLifecycleManager)
    private val cameraStateStore = CameraStateStore()
    private val cameraLifecycleManager = CameraLifecycleManager(cameraStateStore)
    private lateinit var cameraStartupCoordinator: CameraStartupCoordinator

    // Preview surface and callback routing (Phase 5B)
    private val basePreviewCallbackProvider = BasePreviewCallbackProvider()
    private val previewCallbackRouter = PreviewCallbackRouter({ guidedController }, basePreviewCallbackProvider)
    private lateinit var previewSurfaceManager: PreviewSurfaceManager

    // Capture handlers (Phase 5C)
    private lateinit var photoCaptureHandler: PhotoCaptureHandler
    private lateinit var videoCaptureHandler: VideoCaptureHandler
    private var recordingHandlerForVideo: Handler? = null

    // Session & patient coordination (Phase 3 — extracted to SessionFlowCoordinator)
    private lateinit var sessionFlowCoordinator: SessionFlowCoordinator

    // Camera controls & resolution (Phase 4 — extracted to CameraControlCoordinator)
    private lateinit var cameraControlCoordinator: CameraControlCoordinator

    // USB hardware integration
    private var usbSerialManager: UsbSerialManager? = null
    private var usbController: UsbController? = null
    // FIX 1: run immediate scan for already-attached devices once per lifecycle
    private var hasRunInitialUsbDeviceScan = false
    // FIX 2: gate patient/runtime dialogs until USB permission is resolved
    private var usbPermissionPending = false

    /** Sequential USB permission: only one request outstanding at a time. UVC first, then CDC. */
    private enum class UsbPermissionStage { NONE, REQUESTING_UVC, REQUESTING_CDC, COMPLETE }
    private var usbPermissionStage = UsbPermissionStage.NONE
    private var deferredRuntimePermissionCheck = false

    /** OralVis CDC (serial) device – never treat as UVC camera. */
    private fun isCdcDevice(device: UsbDevice) = device.vendorId == 0x1209 && device.productId == 0xC550

    /**
     * Delayed reconnection after USB attach/detach storm.
     * When USB permission is granted, the device often goes through rapid attach/detach cycles.
     * The LAST event is frequently a detach, leaving us with no camera. This runnable fires
     * after the storm settles and reconnects to the first permitted UVC device it finds.
     * Also handles CDC: if camera is already open, ensures CDC is requested/started.
     */
    private val reconnectCameraRunnable = Runnable {
        val client = mCameraClient ?: return@Runnable
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = client.getDeviceList() ?: return@Runnable

        // If camera is already open, check CDC
        if (cameraStateStore.mCurrentCamera != null) {
            ensureCdcStarted(client, usbManager, devices)
            return@Runnable
        }
        if (cameraStateStore.mPendingCameraOpen != null) return@Runnable  // open pending

        // Try to reconnect UVC camera
        for (device in devices) {
            if (isCdcDevice(device)) continue
            if (!CameraUtils.isUsbCamera(device) && !CameraUtils.isFilterDevice(this, device)) continue
            if (!usbManager.hasPermission(device)) continue
            // Ensure in map so onConnectDev can find it
            if (!mCameraMap.containsKey(device.deviceId)) {
                try { mCameraMap[device.deviceId] = CameraUVC(this, device) } catch (_: Throwable) { continue }
            }
            android.util.Log.i("CAMERA_LIFE", "reconnectCamera: found permitted UVC device ${device.deviceId} — connecting")
            client.requestPermission(device)
            return@Runnable
        }
        android.util.Log.d("CAMERA_LIFE", "reconnectCamera: no permitted UVC device found")
    }

    /**
     * Ensure CDC device is permitted and started. Called when UVC camera is already open.
     * - If CDC has permission → start it immediately.
     * - If CDC needs permission → request it (one dialog at a time).
     */
    private fun ensureCdcStarted(client: MultiCameraClient, usbManager: UsbManager, devices: List<UsbDevice>) {
        for (device in devices) {
            if (device.vendorId == 0x1209 && device.productId == 0xC550) {
                if (usbManager.hasPermission(device)) {
                    android.util.Log.i("CDC_TRACE", "ensureCdcStarted: CDC already permitted — starting controller")
                    usbController?.start()
                } else if (usbPermissionStage != UsbPermissionStage.REQUESTING_CDC) {
                    // Request CDC permission (will trigger onConnectDev for CDC when granted)
                    usbPermissionStage = UsbPermissionStage.REQUESTING_CDC
                    usbPermissionPending = true
                    android.util.Log.i("CDC_TRACE", "ensureCdcStarted: requesting CDC permission deviceId=${device.deviceId}")
                    client.requestPermission(device)
                }
                return
            }
        }
    }

    /** Schedule reconnect check after a delay (cancel any pending one first). */
    private fun scheduleReconnectCamera(delayMs: Long = 2000L) {
        binding.root.removeCallbacks(reconnectCameraRunnable)
        binding.root.postDelayed(reconnectCameraRunnable, delayMs)
    }

    // CDC 20-second stability gate (diagnostic/isolation)
    private val cdcStabilityHandler = Handler(Looper.getMainLooper())
    private var cdcStabilityRunnable: Runnable? = null
    private val CDC_STABILITY_DELAY_MS = 20_000L // 20 seconds

    // New features
    private lateinit var sessionManager: SessionManager
    private lateinit var sessionController: SessionController
    private lateinit var mediaDatabase: MediaDatabase
    private lateinit var mediaRepository: MediaRepository
    private lateinit var patientDao: PatientDao
    private lateinit var themeManager: ThemeManager
    private var settingsBottomSheet: BottomSheetDialog? = null
    private var globalPatientObserver: androidx.lifecycle.Observer<com.oralvis.oralviscamera.database.Patient?>? = null
    
    // Resolution management & camera controls moved to CameraControlCoordinator (Phase 4)
    private var resolutionAdapter: ArrayAdapter<String>? = null
    
    private var controlsVisible = false
    private var selectedPatient: Patient? = null

    // Session media tracking
    private val sessionMediaList = mutableListOf<SessionMedia>()
    private var sessionMediaAdapter: SessionMediaAdapter? = null
    private var mediaIdCounter = 0L
    
    // Patient and Clinic context (for S3 folder structure: s3://{bucket}/{GlobalPatientId}/{ClinicId}/{FileName})
    private var globalPatientId: String? = null
    private var clinicId: String? = null

    // Guided auto-capture
    private var guidedController: GuidedController? = null

    // Log collection for debugging
    private lateinit var logCollector: LogCollector
    
    /**
     * Get the current Global Patient ID for S3 folder structure
     * Format: s3://{bucket}/{GlobalPatientId}/{ClinicId}/{FileName}
     */
    fun getGlobalPatientId(): String? = globalPatientId
    
    /**
     * Get the current Clinic ID for S3 folder structure
     * Format: s3://{bucket}/{GlobalPatientId}/{ClinicId}/{FileName}
     */
    fun getClinicId(): String? = clinicId
    
    companion object {
        private const val REQUEST_PERMISSION = 1001
        const val EXTRA_GLOBAL_PATIENT_ID = "GLOBAL_PATIENT_ID"
        const val EXTRA_CLINIC_ID = "CLINIC_ID"

        fun createIntent(context: Context) =
            Intent(context, MainActivity::class.java)
    }

    // Lifecycle-safe dialog management
    private var pendingPatientDialog = false

    /**
     * Shows a DialogFragment safely, respecting Android lifecycle.
     * Prevents IllegalStateException crashes from showing dialogs after onSaveInstanceState.
     */
    fun showDialogSafely(dialog: androidx.fragment.app.DialogFragment, tag: String) {
        if (isFinishing || isDestroyed) {
            android.util.Log.w("MainActivity", "Skipping dialog show: Activity finishing or destroyed")
            return
        }
        if (supportFragmentManager.isStateSaved) {
            android.util.Log.w("MainActivity", "Deferring dialog show: Activity state already saved")
            // Defer to onResume if this is a patient dialog
            if (dialog is com.oralvis.oralviscamera.PatientSessionDialogFragment) {
                pendingPatientDialog = true
            }
            return
        }
        dialog.show(supportFragmentManager, tag)
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.READ_MEDIA_IMAGES
            permissions += Manifest.permission.READ_MEDIA_VIDEO
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }

        return permissions.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.e("SETTINGS_DEBUG_CRITICAL", "=== MAINACTIVITY ONCREATE STARTED ===")
        android.util.Log.d("SettingsDebug", "ONCREATE_START - MainActivity onCreate called")
        super.onCreate(savedInstanceState)
        android.util.Log.d("SettingsDebug", "super.onCreate() completed")

        // AUTHENTICATION GATE: Check login status for ALL entry paths (normal + USB)
        // This prevents USB-triggered launches from bypassing login validation
        val loginManager = LoginManager(this)
        if (!loginManager.isLoggedIn()) {
            android.util.Log.d("AuthGate", "User not logged in - redirecting to LoginActivity")
            android.util.Log.d("AuthGate", "Launch intent action: ${intent?.action}")
            android.util.Log.d("AuthGate", "Launch intent extras: ${intent?.extras}")

            // Redirect to login screen, preserving the original intent for post-login handling if needed
            val loginIntent = Intent(this, LoginActivity::class.java)
            // Pass the original intent as extra so LoginActivity can handle USB-triggered launches after login
            loginIntent.putExtra("pending_intent", intent)
            startActivity(loginIntent)
            finish() // Close MainActivity to prevent back navigation exposing inner app
            return
        }
        android.util.Log.d("AuthGate", "User authenticated - proceeding with MainActivity initialization")

        // Handle any pending USB intents that were preserved during authentication
        val pendingUsbIntent = intent.getParcelableExtra<Intent>("pending_usb_intent")
        if (pendingUsbIntent != null) {
            android.util.Log.d("AuthGate", "Processing pending USB intent after login: ${pendingUsbIntent.action}")
            // The USB device attachment will be handled by the existing USB callbacks
            // Camera detection and USB serial initialization will proceed normally
        }
        
        // Force landscape orientation - lock to horizontal
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        
        enableEdgeToEdge()
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        uiBinder = MainUiBinder(binding)

        // Verify settings button exists
        android.util.Log.d("SettingsDebug", "Binding initialized - btnSettings exists: ${binding.btnSettings != null}")

        // Phase 5B: Preview surface handling in PreviewSurfaceManager
        previewSurfaceManager = PreviewSurfaceManager(
            store = cameraStateStore,
            onInitializeCameraIfReady = { cameraStartupCoordinator.onSurfaceReady() },
            onFirstFrameReceived = { cameraStartupCoordinator.onFirstFrameReceived() },
            onSurfaceReadyForUsb = { requestPermissionForAlreadyAttachedDevices() }
        )
        previewSurfaceManager.attachTo(binding.cameraTextureView)

        // Register USBMonitor early so ACTION_USB_PERMISSION and attach events are never lost.
        // USB permission is requested only after surface is ready (in onSurfaceTextureAvailable),
        // matching the reference app so when user grants, surface exists and camera opens immediately.
        ensureUsbMonitorRegistered()

        uiBinder.applyWindowInsets()
        
        // Initialize new features
        try {
            android.util.Log.d("SettingsDebug", "Initializing GlobalPatientManager...")
            GlobalPatientManager.initialize(this)
            android.util.Log.d("SettingsDebug", "GlobalPatientManager initialized")
        
            android.util.Log.d("SettingsDebug", "Initializing LocalPatientIdManager...")
            LocalPatientIdManager.initialize(this)
            android.util.Log.d("SettingsDebug", "LocalPatientIdManager initialized")

            android.util.Log.d("SettingsDebug", "Creating SessionManager...")
            sessionManager = SessionManager(this)
            android.util.Log.d("SettingsDebug", "SessionManager created")
            android.util.Log.d("SettingsDebug", "Creating SessionController...")
            sessionController = SessionController(sessionManager)
            android.util.Log.d("SettingsDebug", "SessionController created")
        
            android.util.Log.d("SettingsDebug", "Getting MediaDatabase...")
            mediaDatabase = MediaDatabase.getDatabase(this)
            android.util.Log.d("SettingsDebug", "MediaDatabase obtained")
        
            android.util.Log.d("SettingsDebug", "Creating MediaRepository...")
            mediaRepository = MediaRepository(this)
            android.util.Log.d("SettingsDebug", "MediaRepository created")
        
            // Phase 5C: Capture handlers (require sessionController, mediaRepository, binding)
        recordingHandlerForVideo = Handler(Looper.getMainLooper())
        photoCaptureHandler = PhotoCaptureHandler(mediaRepository, this, lifecycleScope)
        videoCaptureHandler = VideoCaptureHandler(this)
        cameraModeController = CameraModeController(
            getCamera = { cameraStateStore.mCurrentCamera },
            modeUi = cameraModeUi,
            fluorescenceAdapter = fluorescenceModeAdapter,
            runOnUiThread = { r -> runOnUiThread(r) }
        )

        // Phase 4: Camera controls & resolution authority coordinator
        cameraControlCoordinator = CameraControlCoordinator(
            context = this,
            binding = binding,
            cameraStateStore = cameraStateStore,
            cameraMapProvider = { mCameraMap },
            isRecording = { videoCaptureHandler.isRecording },
            settingsBottomSheetProvider = { settingsBottomSheet },
            writeDebugLog = { tag, loc, msg, data -> writeDebugLog(tag, loc, msg, data) },
            refreshCameraReferenceCallback = { refreshCameraReference() }
        )

        // Phase 3: Session & patient authority coordinator
        sessionFlowCoordinator = SessionFlowCoordinator(
            activity = this,
            binding = binding,
            sessionController = sessionController,
            mediaDatabase = mediaDatabase,
            previewCallbackRouter = previewCallbackRouter,
            cameraStateStore = cameraStateStore,
            guidedControllerProvider = { guidedController },
            initializeGuidedCapture = { initializeGuidedCapture() },
            sessionMediaList = sessionMediaList,
            sessionMediaAdapterProvider = { sessionMediaAdapter },
            nextSessionMediaId = { ++mediaIdCounter },
            getSelectedPatient = { selectedPatient },
            setSelectedPatient = { selectedPatient = it },
            getGlobalPatientId = { globalPatientId },
            setGlobalPatientId = { globalPatientId = it },
            isUsbPermissionPending = { usbPermissionPending }
        )

        cameraStartupCoordinator = CameraStartupCoordinator(
            cameraLifecycleManager = cameraLifecycleManager,
            cameraStateStore = cameraStateStore,
            previewSurfaceManager = previewSurfaceManager,
            previewCallbackRouter = previewCallbackRouter,
            ensureUsbMonitorRegistered = { ensureUsbMonitorRegistered() },
            openCamera = { cam, req -> cam.openCamera(binding.cameraTextureView, req) },
            // Map enum → String for legacy lifecycle manager ("Normal"/"Fluorescence")
            getCurrentMode = {
                if (cameraModeController.currentMode == CameraMode.CARIES) "Fluorescence" else "Normal"
            },
            applyModePreset = { modeString ->
                val enumMode = if (modeString == "Fluorescence") CameraMode.CARIES else CameraMode.NORMAL
                cameraModeController.applyModePreset(enumMode)
            },
            isGuidedInitialized = { guidedController?.isInitialized() == true },
            initializeGuidedIfNeeded = { guidedController?.initializeIfNeeded() ?: Unit },
            updateCameraControlValuesFromMode = { cameraModeController.updateCameraControlValues() },
            cleanupCameraClient = {
                mCameraClient?.unRegister()
                mCameraClient?.destroy()
                mCameraClient = null
            }
        )

            android.util.Log.d("SettingsDebug", "Getting patientDao...")
        patientDao = mediaDatabase.patientDao()
            android.util.Log.d("SettingsDebug", "patientDao obtained")

            android.util.Log.d("SettingsDebug", "Creating ThemeManager...")
        themeManager = ThemeManager(this)
            android.util.Log.d("SettingsDebug", "ThemeManager created")

        // Derive client and patient context:
        // - ClientId comes from LoginManager (user-entered Client ID from login)
        // - GlobalPatientId is resolved from the currently selected Patient when saving/uploading
            android.util.Log.d("SettingsDebug", "Getting clinicId from LoginManager...")
        clinicId = LoginManager(this).getClientId()
            android.util.Log.d("SettingsDebug", "clinicId obtained: $clinicId")

            android.util.Log.d("SettingsDebug", "Getting globalPatientId from intent...")
        globalPatientId = intent.getStringExtra(EXTRA_GLOBAL_PATIENT_ID)
            android.util.Log.d("SettingsDebug", "globalPatientId obtained: $globalPatientId")

            // DEFERRAL: Initialize guided capture AFTER camera is ready (moved to onFirstFrameReceived)
            // This prevents OpenCV initialization from starving camera startup
            android.util.Log.d("SettingsDebug", "Guided capture initialization deferred until camera ready")

        // Clear previous patient selection on app start
            android.util.Log.d("SettingsDebug", "Clearing current patient...")
        GlobalPatientManager.clearCurrentPatient()
            android.util.Log.d("SettingsDebug", "Current patient cleared")
        
        // Apply saved theme
            android.util.Log.d("SettingsDebug", "Applying theme...")
        applyTheme()
            android.util.Log.d("SettingsDebug", "Theme applied")

        } catch (e: Exception) {
            android.util.Log.e("SETTINGS_DEBUG_CRITICAL", "EXCEPTION during initialization: ${e.message}", e)
            android.util.Log.e("SETTINGS_DEBUG_CRITICAL", "Stack trace:", e)
            // Re-throw to crash the app so we can see the error
            throw e
        }
        
        android.util.Log.e("SETTINGS_DEBUG_CRITICAL", "=== ABOUT TO CALL setupUI() ===")
        android.util.Log.d("SettingsDebug", "About to call setupUI() in onCreate")
        setupUI()
        android.util.Log.d("SettingsDebug", "setupUI() completed in onCreate")
        android.util.Log.e("SETTINGS_DEBUG_CRITICAL", "=== setupUI() COMPLETED ===")
        
        // Initialize camera core UI (settings panel, controls, seekbars, spinners)
        // This ensures camera controls work on cold start, before any guided session
        android.util.Log.d("CameraCoreUI", "About to call setupCameraCoreUI() in onCreate")
        setupCameraCoreUI()
        android.util.Log.d("CameraCoreUI", "setupCameraCoreUI() completed in onCreate")
        
        observeGlobalPatient()
        maybeInitializePatientFromIntent()
        initializeFromGlobalPatient()
        android.util.Log.d("SettingsDebug", "About to call checkPermissions() in onCreate")
        checkPermissions()
        android.util.Log.d("SettingsDebug", "checkPermissions() completed in onCreate")

        // Initialize USB serial manager for hardware control
        usbSerialManager = UsbSerialManager(
            context = this,
            commandReceiver = this,
            onConnectionStateChanged = { isConnected ->
                updateUsbConnectionStatus(isConnected)
            }
        )
        usbController = UsbController(usbSerialManager!!)
        android.util.Log.i("UsbSerial", "USB serial manager and controller initialized")

        // DEFERRAL: Move heavy initialization to after camera ready
        // Remove the immediate initializeGuidedCapture() call from onCreate

        // Initialize log collector for debugging
        logCollector = LogCollector(this)
    }

    /**
     * Schedule CDC startup after 20 seconds of stable UVC streaming.
     * This is a diagnostic/isolation gate to prove CDC causality if issues occur.
     * 
     * CDC will only start if:
     * - Camera remains open for 20 continuous seconds
     * - Camera has not been closed/reopened during that window
     * 
     * If camera closes before 20s → CDC start is cancelled.
     */
    private fun scheduleCdcStartAfterStability() {
        // Cancel any existing scheduled CDC start
        cancelCdcStartSchedule()
        
        android.util.Log.i("CDC_TRACE", "CDC scheduling started | time=${System.currentTimeMillis()} | waiting 20s | cameraStateStore.hasReceivedFirstFrame=$cameraStateStore.hasReceivedFirstFrame | cameraStateStore.isCameraReady=$cameraStateStore.isCameraReady")
        android.util.Log.i("CdcStability", "First frame received — scheduling CDC start in 20 seconds")
        
        // Create runnable that will start CDC after stability period
        cdcStabilityRunnable = Runnable {
            android.util.Log.i("CDC_TRACE", "CDC start triggered after delay | time=${System.currentTimeMillis()} | cameraOpen=${cameraStateStore.isCameraReady && cameraStateStore.mCurrentCamera != null}")
            // Verify camera is still open before starting CDC
            if (cameraStateStore.isCameraReady && cameraStateStore.mCurrentCamera != null) {
                android.util.Log.i("CdcStability", "✅ CDC started after 20 seconds of stable UVC streaming")
                usbController?.start()
            } else {
                android.util.Log.w("CdcStability", "CDC start cancelled — camera closed before delay elapsed")
            }
            cdcStabilityRunnable = null
        }
        
        // Schedule CDC start after 20 seconds
        cdcStabilityHandler.postDelayed(cdcStabilityRunnable!!, CDC_STABILITY_DELAY_MS)
    }

    /**
     * Cancel scheduled CDC start if camera closes or Activity pauses.
     */
    private fun cancelCdcStartSchedule() {
        cdcStabilityRunnable?.let { runnable ->
            cdcStabilityHandler.removeCallbacks(runnable)
            android.util.Log.i("CDC_TRACE", "CDC scheduling CANCELLED")
            android.util.Log.d("CdcStability", "CDC start cancelled — camera closed or Activity paused before delay elapsed")
            cdcStabilityRunnable = null
        }
    }

    /**
     * Handle new intents, including USB device attachment while app is running.
     * Since user is already in MainActivity, they must be authenticated.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        android.util.Log.d("AuthGate", "onNewIntent received - action: ${intent.action}")
        android.util.Log.d("AuthGate", "onNewIntent extras: ${intent.extras}")

        // Handle USB device attachment while app is already running
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            android.util.Log.d("AuthGate", "USB device attached while MainActivity running - camera will auto-detect")
            // Camera detection will happen through the existing USB callbacks
            // No need to redirect - user is already authenticated and in the app
        }

        // Handle pending USB intents that were forwarded after login
        val pendingUsbIntent = intent.getParcelableExtra<Intent>("pending_usb_intent")
        if (pendingUsbIntent != null) {
            android.util.Log.d("AuthGate", "Received pending USB intent via onNewIntent: ${pendingUsbIntent.action}")
            // The USB device attachment will be handled by existing callbacks
            // No additional action needed - authentication gate has already passed
        }

        // Update the current intent
        setIntent(intent)
    }
    
    override fun onResume() {
        super.onResume()
        // Reapply theme when returning from other activities to sync theme changes
        applyTheme()

        // Universal camera + CDC recovery after initial USB scan.
        // Handles: grant-while-stopped, attach/detach storm, CDC not started, etc.
        if (hasRunInitialUsbDeviceScan) {
            if (cameraStateStore.mCurrentCamera == null && cameraStateStore.mPendingCameraOpen == null) {
                // No camera — schedule UVC reconnect
                android.util.Log.d("CAMERA_LIFE", "onResume: no camera open — scheduling reconnect checks")
                binding.root.removeCallbacks(reconnectCameraRunnable)
                binding.root.postDelayed(reconnectCameraRunnable, 500)
                binding.root.postDelayed(reconnectCameraRunnable, 2500)
                binding.root.postDelayed(reconnectCameraRunnable, 5000)
            } else if (cameraStateStore.mCurrentCamera != null) {
                // Camera is open — ensure CDC is also started (may have been lost due to
                // grant-while-stopped on the CDC permission dialog)
                android.util.Log.d("CAMERA_LIFE", "onResume: camera open — checking CDC")
                binding.root.postDelayed(reconnectCameraRunnable, 500)
            }
        }

        // CDC START REMOVED: CDC now starts only after first frame received
        // This ensures UVC streaming is fully stable before claiming CDC interface
        // See onFirstFrameReceived() for CDC startup

        // Patient selection is now handled by PatientSelectionActivity before reaching here.
        // No auto-prompt dialog on resume. User can change patient via nav bar button.

        // Show any pending dialogs that were deferred due to lifecycle state
        // (e.g. user clicked nav bar patient button while state was saved)
        if (pendingPatientDialog) {
            pendingPatientDialog = false
            android.util.Log.d("MainActivity", "Showing deferred patient dialog in onResume")
            showDialogSafely(com.oralvis.oralviscamera.PatientSessionDialogFragment(), "PatientSessionDialog")
        }
    }

    /**
     * Check if patient selection is required and prompt user if needed.
     * Called from onResume() and after USB permission is resolved.
     * Deferred until [hasRunInitialUsbDeviceScan] so we never show the patient dialog before
     * the USB permission dialog (avoids activity stopped + cancel when both dialogs appear).
     */
    private fun checkAndPromptForPatientSelection() {
        if (!hasRunInitialUsbDeviceScan) return
        sessionFlowCoordinator.checkAndPromptForPatientSelection()
    }
    
    override fun onPause() {
        super.onPause()
        
        // Cancel scheduled CDC start if Activity is pausing
        cancelCdcStartSchedule()
        // Cancel pending reconnect attempts while in background
        binding.root.removeCallbacks(reconnectCameraRunnable)
        
        // Stop USB serial manager to release USB connection and threads
        usbController?.stop()
        android.util.Log.d("UsbSerial", "USB serial manager stopped in onPause")
    }
    
    // Duplicate onDestroy removed - merged into the existing onDestroy at bottom of file
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Force landscape even if device rotates
        if (newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    // Removed startNewSession() - sessions are now auto-created by GlobalPatientManager
    
    private fun initializeGuidedCapture() {
        android.util.Log.d("SettingsDebug", "initializeGuidedCapture() method started")
        val cameraFrame = binding.cameraFrame
        android.util.Log.d("SettingsDebug", "cameraFrame obtained: $cameraFrame")
        android.util.Log.d("SettingsDebug", "Creating GuidedCaptureManager...")
        guidedController = GuidedController(
            context = this,
            rootContainer = cameraFrame,
            sessionBridge = object : SessionBridge {
                override fun ensureGuidedSessionId(): String {
                    // Generate a unique guided session id for each press of "Start Session".
                    return "guided_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"
                }

                override fun onGuidedCaptureRequested(
                    guidedSessionId: String,
                    dentalArch: String,
                    sequenceNumber: Int
                ) {
                    android.util.Log.d("GuidedCapture", "onGuidedCaptureRequested called - dentalArch: $dentalArch, sequenceNumber: $sequenceNumber, guidedSessionId: $guidedSessionId")
                    // Reuse the existing capture pipeline, then update the latest record with guided metadata.
                    if (cameraStateStore.mCurrentCamera != null) {
                        android.util.Log.d("GuidedCapture", "Camera available, using capturePhotoWithGuidedMetadata")
                        photoCaptureHandler.capturePhotoWithGuidedMetadata(guidedSessionId, dentalArch, sequenceNumber)
                    } else {
                        android.util.Log.d("GuidedCapture", "Camera not available, using captureBlankImageWithGuidedMetadata")
                        photoCaptureHandler.captureBlankImageWithGuidedMetadata(guidedSessionId, dentalArch, sequenceNumber)
                    }
                }

                override fun onGuidedSessionComplete(guidedSessionId: String?) {
                    // Guided capture sequence completed - keep patient selected and session active
                    android.util.Log.d("GuidedCapture", "Guided session completed: $guidedSessionId - keeping patient selected")

                    // Clear session media from camera preview (but keep in gallery via database)
                    clearSessionMediaPreview()

                    // Show completion message
                    Toast.makeText(this@MainActivity, "Guided capture completed!", Toast.LENGTH_SHORT).show()
                }

                override fun onRecaptureLower(guidedSessionId: String) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val mediaDao = mediaDatabase.mediaDao()
                        mediaDao.getAllMedia().collect { list ->
                            list.filter {
                                it.guidedSessionId == guidedSessionId &&
                                    it.dentalArch == GuidedController.DENTAL_ARCH_LOWER
                            }.forEach { record ->
                                mediaDao.deleteMediaById(record.id)
                                try {
                                    File(record.filePath).delete()
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                }

                override fun onRecaptureUpper(guidedSessionId: String) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val mediaDao = mediaDatabase.mediaDao()
                        mediaDao.getAllMedia().collect { list ->
                            list.filter {
                                it.guidedSessionId == guidedSessionId &&
                                    it.dentalArch == GuidedController.DENTAL_ARCH_UPPER
                            }.forEach { record ->
                                mediaDao.deleteMediaById(record.id)
                                try {
                                    File(record.filePath).delete()
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                }
            }
        )
        guidedController?.initializeIfNeeded()
    }

    private fun setupUI() {
        android.util.Log.d("SettingsDebug", "SETUP_UI_CALLED - setupUI() method called - BEGIN")
        uiBinder.bindBottomNavigation(
            onNavCameraClicked = {
                // Already on camera screen; no action required
            },
            onNavGalleryClicked = {
                onNavGalleryClicked()
            },
            onNavFindPatientsClicked = {
                onNavFindPatientsClicked()
            },
            onNavPatientClicked = {
                onNavPatientClicked()
            }
        )

        uiBinder.bindCaptureControls(
            onCaptureClicked = {
                onCaptureClicked()
            },
            onRecordClicked = {
                onRecordClicked()
            }
        )

        uiBinder.bindStartSessionButtons(
            onStartSessionClicked = {
                onStartSessionClicked()
            }
        )

        uiBinder.bindDebugSettingsButton(
            onDebugSettingsClicked = {
                android.util.Log.e("DEBUG_SETTINGS_BUTTON", "DEBUG_SETTINGS_BUTTON_CLICKED - Testing settings panel logic")
                toggleSettingsPanel()
            }
        )
    }

    /**
     * Navigation and action handlers invoked by MainUiBinder callbacks.
     * Logic is identical to the original inline click listeners.
     */
    private fun onNavGalleryClicked() {
        android.util.Log.d("NavigationDebug", "navGallery clicked")
        android.util.Log.d("NavigationDebug", "hasPatientSelected: ${GlobalPatientManager.hasPatientSelected()}")
        if (GlobalPatientManager.hasPatientSelected()) {
            android.util.Log.d("NavigationDebug", "Opening GalleryActivity")
            val intent = Intent(this, GalleryActivity::class.java)
            startActivity(intent)
        } else {
            android.util.Log.d("NavigationDebug", "Showing patient selection toast")
            Toast.makeText(this, "Please select a patient first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onNavFindPatientsClicked() {
        val intent = Intent(this, FindPatientsActivity::class.java)
        startActivity(intent)
    }

    private fun onNavPatientClicked() {
        openPatientDialogForSession()
    }

    private fun onCaptureClicked() {
        if (!GlobalPatientManager.hasPatientSelected()) {
            Toast.makeText(this, "Please select a patient first", Toast.LENGTH_SHORT).show()
            openPatientDialogForSession()
            return
        }

        if (guidedController?.isGuidedActive() == true) {
            android.util.Log.d("MainActivity", "Manual capture during guided session - routing through guided capture")
            if (guidedController?.handleManualCapture() == true) return
        }

        if (cameraStateStore.mCurrentCamera != null) {
            photoCaptureHandler.capturePhotoWithRetry()
        } else {
            photoCaptureHandler.captureBlankImage()
        }
    }

    private fun onRecordClicked() {
        if (!GlobalPatientManager.hasPatientSelected()) {
            Toast.makeText(this, "Please select a patient first", Toast.LENGTH_SHORT).show()
            openPatientDialogForSession()
            return
        }
        if (cameraStateStore.mCurrentCamera != null) {
            videoCaptureHandler.toggleRecordingWithRetry()
        } else {
            videoCaptureHandler.captureBlankVideo()
        }
    }

    private fun onStartSessionClicked() {
        sessionFlowCoordinator.onStartSessionClicked()
    }

    /**
     * Setup Camera Core UI - Must work on cold start (before any guided session)
     * Initializes settings panel, camera controls, seekbars, and spinners
     * Called from onCreate() to ensure camera controls are functional immediately
     */
    private fun setupCameraCoreUI() {
        android.util.Log.d("CameraCoreUI", "setupCameraCoreUI() - Initializing camera core UI")
        uiBinder.bindCameraCoreUi(
            onSettingsClicked = {
                android.util.Log.d("SettingsDebug", "Settings button clicked")
                toggleSettingsPanel()
            },
            onCloseSettingsClicked = {
                hideSettingsPanel()
            },
            onSettingsScrimClicked = {
                hideSettingsPanel()
            },
            onEditPatientClicked = {
                val intent = Intent(this, FindPatientsActivity::class.java)
                startActivity(intent)
            }
        )

        // Setup resolution spinner
        setupSpinners()
        
        // Setup all settings panel button actions (includes seekbar listeners)
        setupSettingsPanelActions()

        // Setup Normal/Caries mode toggle
        setupModeToggle()
        
        android.util.Log.d("CameraCoreUI", "setupCameraCoreUI() - Complete")
    }

    private fun setupModeToggle() {
        // Initialize toggle to reflect current controller mode (default NORMAL)
        isUpdatingModeToggleFromCode = true
        binding.modeToggle.isChecked = (cameraModeController.currentMode == CameraMode.CARIES)
        isUpdatingModeToggleFromCode = false

        binding.modeToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingModeToggleFromCode) return@setOnCheckedChangeListener
            val targetMode = if (isChecked) CameraMode.CARIES else CameraMode.NORMAL
            cameraModeController.applyCameraMode(targetMode)
        }
    }

    private fun syncModeToggleWithController(mode: CameraMode = cameraModeController.currentMode) {
        val shouldBeChecked = (mode == CameraMode.CARIES)
        if (binding.modeToggle.isChecked != shouldBeChecked) {
            isUpdatingModeToggleFromCode = true
            binding.modeToggle.isChecked = shouldBeChecked
            isUpdatingModeToggleFromCode = false
        }
    }
    
    /**
     * Setup Guided Session UI - Only needed during guided capture session
     * Initializes session-specific UI components like media recycler
     * Called from proceedWithSessionStart() when user starts guided session
     */
    fun setupGuidedSessionUI() {
        android.util.Log.d("GuidedSessionUI", "setupGuidedSessionUI() - Initializing session UI")
        uiBinder.setupGuidedSessionUi(
            onSetupSessionMediaRecycler = {
                setupSessionMediaRecycler()
            }
        )

        android.util.Log.d("GuidedSessionUI", "setupGuidedSessionUI() - Complete")
    }

    // ======================================================================
    // CameraCommandReceiver Interface Implementation (USB Hardware Control)
    // ======================================================================
    
    /**
     * Trigger a photo capture from USB hardware command.
     * Implements safety guards: camera readiness, capture lock, activity lifecycle.
     */
    override fun triggerCapture(): Boolean {
        if (!cameraStateStore.isCameraReady) {
            android.util.Log.w("UsbCommand", "CAPTURE ignored: camera not ready")
            return false
        }
        if (isFinishing || isDestroyed) {
            android.util.Log.w("UsbCommand", "CAPTURE ignored: activity finishing")
            return false
        }
        if (!photoCaptureHandler.tryAcquireCaptureLock()) {
            android.util.Log.w("UsbCommand", "CAPTURE ignored: already capturing")
            return false
        }

        try {
            runOnUiThread {
                try {
                    if (!GlobalPatientManager.hasPatientSelected()) {
                        android.util.Log.w("UsbCommand", "CAPTURE ignored: no patient selected")
                        showPatientSelectionPrompt()
                        return@runOnUiThread
                    }
                    if (guidedController?.isGuidedActive() == true) {
                        if (guidedController?.handleManualCapture() == true) {
                            android.util.Log.i("UsbCommand", "CAPTURE handled by guided capture manager")
                        } else {
                            android.util.Log.w("UsbCommand", "CAPTURE rejected by guided capture manager")
                        }
                    } else {
                        if (cameraStateStore.mCurrentCamera != null) {
                            photoCaptureHandler.capturePhotoWithRetry()
                            android.util.Log.i("UsbCommand", "CAPTURE executed via direct camera capture")
                        } else {
                            android.util.Log.w("UsbCommand", "CAPTURE ignored: camera not available")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("UsbCommand", "Error during capture: ${e.message}", e)
                } finally {
                    photoCaptureHandler.releaseCaptureLock()
                }
            }
            return true
        } catch (e: Exception) {
            android.util.Log.e("UsbCommand", "Error dispatching capture: ${e.message}", e)
            photoCaptureHandler.releaseCaptureLock()
            return false
        }
    }
    
    /**
     * Switch to Normal (RGB) mode from USB hardware command.
     * Delegates to CameraModeController (Phase 5D).
     */
    override fun switchToNormalMode(): Boolean = cameraModeController.switchToNormalMode()

    /**
     * Switch to Fluorescence (UV) mode from USB hardware command.
     * Delegates to CameraModeController (Phase 5D).
     */
    override fun switchToFluorescenceMode(): Boolean = cameraModeController.switchToFluorescenceMode()
    
    /**
     * Check if a guided capture session is currently active.
     */
    override fun isGuidedSessionActive(): Boolean {
        return guidedController?.isGuidedActive() == true
    }
    
    /**
     * Update USB connection status UI.
     * Shows connection status feedback to user.
     */
    private fun updateUsbConnectionStatus(isConnected: Boolean) {
        runOnUiThread {
            try {
                if (isConnected) {
                    android.util.Log.i("UsbSerial", "🎉 OralVis hardware connected - Remote control active!")
                    Toast.makeText(this, "🎮 OralVis hardware connected - Remote control ready!", Toast.LENGTH_SHORT).show()
                } else {
                    android.util.Log.w("UsbSerial", "⚠️ OralVis hardware disconnected")
                    Toast.makeText(this, "⚠️ OralVis hardware disconnected", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("UsbSerial", "Error updating connection status: ${e.message}")
            }
        }
        }
        
    /**
     * Show a prompt to user to select a patient when USB capture is attempted without one.
     */
    private fun showPatientSelectionPrompt() {
        sessionFlowCoordinator.showPatientSelectionPrompt()
    }

    // ======================================================================
    // End of CameraCommandReceiver Implementation
    // ======================================================================

    /**
     * PHASE B — GUIDED CAPTURE SESSION: Start user-triggered guided capture session.
     * This is separate from camera activation (Phase A) and adds session-specific logic.
     */
    private fun proceedWithSessionStart() {
        sessionFlowCoordinator.proceedWithSessionStart()
    }

    /**
     * Opens the patient dialog to choose or create a patient before starting a session.
     */
    private fun openPatientDialogForSession() {
        sessionFlowCoordinator.openPatientDialogForSession()
    }
    
    private fun setupCameraControlSeekBars() {
        cameraControlCoordinator.setupCameraControlSeekBars()
    }
    

    // Camera activation is now coordinated by CameraStartupCoordinator.

    /**
     * Sets up all settings panel button click listeners.
     * Called during normal UI initialization to ensure settings work on cold launch.
     */
    private fun setupSettingsPanelActions() {
        android.util.Log.d("SettingsDebug", "Setting up settings panel actions")

        // Reset controls
        binding.btnResetControls.setOnClickListener {
            resetCameraControls()
        }

        binding.btnShareLogs.setOnClickListener {
            shareFullLogs()
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

        // Setup camera control seekbars (brightness, contrast, etc.)
        setupCameraControlSeekBars()
    }
    
    private fun setupSessionMediaRecycler() {
        sessionMediaAdapter = SessionMediaAdapter(
            onMediaClick = { media ->
                // Preview the media
                openMediaPreview(media.filePath, media.isVideo)
            },
            onRemoveClick = { media ->
                // Remove from list and delete file
                removeSessionMedia(media)
            }
        )
        
        binding.sessionMediaRecycler.apply {
            // Use GridLayoutManager with 2 columns for vertical grid display
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@MainActivity, 2)
            adapter = sessionMediaAdapter
            setHasFixedSize(false)
        }
        
        updateSessionMediaUI()
    }
    
    override fun addSessionMedia(filePath: String, isVideo: Boolean) {
        sessionFlowCoordinator.addSessionMedia(filePath, isVideo)
    }
    
    private fun removeSessionMedia(media: SessionMedia) {
        sessionFlowCoordinator.removeSessionMedia(media)
    }
    
    private fun updateSessionMediaUI() {
        sessionFlowCoordinator.updateSessionMediaUI()
    }

    /**
     * Clear session media from camera preview UI (but keep in gallery database)
     * Called when guided session completes to reset camera view while preserving gallery data
     */
    private fun clearSessionMediaPreview() {
        sessionFlowCoordinator.clearSessionMediaPreview()
    }
    
    private fun proceedWithSettingsPanelAnimation(panelWidth: Int) {
        // Show scrim (dim background)
        android.util.Log.d("SettingsDebug", "Setting scrim visibility to VISIBLE")
        binding.settingsScrim.visibility = View.VISIBLE
        binding.settingsScrim.alpha = 0f
        binding.settingsScrim.animate()
            .alpha(1f)
            .setDuration(300)
            .start()

        // Show settings panel
        android.util.Log.d("SettingsDebug", "Setting panel visibility to VISIBLE")
        binding.settingsPanel.visibility = View.VISIBLE
        binding.settingsPanel.alpha = 0f
        binding.settingsPanel.translationX = panelWidth.toFloat()

        android.util.Log.d("SettingsDebug", "Starting panel animation with translationX: ${panelWidth.toFloat()}")

        val animator = binding.settingsPanel.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(300)

        animator.withEndAction {
            android.util.Log.d("SettingsDebug", "=== ANIMATION END ACTION ===")
            android.util.Log.d("SettingsDebug", "Final panel visibility: ${binding.settingsPanel.visibility}")
            android.util.Log.d("SettingsDebug", "Final panel alpha: ${binding.settingsPanel.alpha}")
            android.util.Log.d("SettingsDebug", "Final panel translationX: ${binding.settingsPanel.translationX}")
            android.util.Log.d("SettingsDebug", "Final scrim visibility: ${binding.settingsScrim.visibility}")
            android.util.Log.d("SettingsDebug", "Final scrim alpha: ${binding.settingsScrim.alpha}")

            // DIAGNOSTIC LOGGING: Capture final state after animation
            android.util.Log.e("SETTINGS_DIAGNOSTIC",
                "ANIMATION_COMPLETE: attached=${binding.settingsPanel.isAttachedToWindow}, " +
                "w=${binding.settingsPanel.width}, h=${binding.settingsPanel.height}, " +
                "focus=${binding.settingsPanel.hasWindowFocus()}, visibility=${binding.settingsPanel.visibility}, " +
                "alpha=${binding.settingsPanel.alpha}, translationX=${binding.settingsPanel.translationX}")

            // Check if panel is actually visible on screen
            val panelRect = android.graphics.Rect()
            binding.settingsPanel.getGlobalVisibleRect(panelRect)
            android.util.Log.d("SettingsDebug", "Panel global visible rect: $panelRect")
            android.util.Log.d("SettingsDebug", "Panel should now be visible to user!")

            // #region agent log
            writeDebugLog("A", "MainActivity.kt:851", "settingsPanel animation complete - setting up spinner", mapOf("timestamp" to System.currentTimeMillis()))
            // #endregion
            android.util.Log.d("ResolutionClick", "Settings panel animation complete - setting up resolution spinner")

            // CRITICAL FIX: Setup resolution spinner when side panel is shown
            // The spinner exists in the side panel but listener was never attached
            android.util.Log.d("SettingsDebug", "About to call setupResolutionSpinnerWithRetry")
            setupResolutionSpinnerWithRetry(binding.settingsPanel, 0)
            android.util.Log.d("SettingsDebug", "setupResolutionSpinnerWithRetry completed")
        }

        android.util.Log.d("SettingsDebug", "Starting animation...")
        animator.start()
        android.util.Log.d("SettingsDebug", "Animation started")
    }
    
    private fun openMediaPreview(filePath: String, isVideo: Boolean) {
        try {
            val intent = Intent(this, MediaViewerActivity::class.java).apply {
                putExtra("media_path", filePath)
                putExtra("is_video", isVideo)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open media preview", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun toggleSettingsPanel() {
        android.util.Log.d("SettingsDebug", "=== TOGGLE SETTINGS PANEL START ===")
        android.util.Log.d("SettingsDebug", "toggleSettingsPanel() called at ${System.currentTimeMillis()}")

        // DIAGNOSTIC LOGGING: Capture state when toggle is called
        android.util.Log.e("SETTINGS_DIAGNOSTIC",
            "TOGGLE_CALLED: attached=${binding.settingsPanel.isAttachedToWindow}, " +
            "w=${binding.settingsPanel.width}, h=${binding.settingsPanel.height}, " +
            "focus=${binding.settingsPanel.hasWindowFocus()}, visibility=${binding.settingsPanel.visibility}, " +
            "hasWindowFocus=${hasWindowFocus()}, activityFocus=${window?.decorView?.hasWindowFocus()}")

        try {
            android.util.Log.d("SettingsDebug", "settingsPanel is null: ${binding.settingsPanel == null}")
            if (binding.settingsPanel == null) {
                android.util.Log.e("SettingsDebug", "ERROR: settingsPanel is null!")
                return
            }

            android.util.Log.d("SettingsDebug", "settingsPanel visibility: ${binding.settingsPanel.visibility}")
            android.util.Log.d("SettingsDebug", "View.VISIBLE constant: ${View.VISIBLE}")
            android.util.Log.d("SettingsDebug", "View.GONE constant: ${View.GONE}")

        if (binding.settingsPanel.visibility == View.VISIBLE) {
                android.util.Log.d("SettingsDebug", "Panel is VISIBLE, calling hideSettingsPanel()")
            hideSettingsPanel()
        } else {
                android.util.Log.d("SettingsDebug", "Panel is NOT VISIBLE, calling showSettingsPanel()")
            showSettingsPanel()
            }

            android.util.Log.d("SettingsDebug", "=== TOGGLE SETTINGS PANEL END ===")
        } catch (e: Exception) {
            android.util.Log.e("SettingsDebug", "EXCEPTION in toggleSettingsPanel: ${e.message}", e)
        }
    }
    
    private fun showSettingsPanel() {
        android.util.Log.d("SettingsDebug", "=== SHOW SETTINGS PANEL START ===")
        android.util.Log.d("SettingsDebug", "showSettingsPanel() called at ${System.currentTimeMillis()}")

        val panel = binding.settingsPanel

        // DIAGNOSTIC LOGGING: Capture view state before animation
        android.util.Log.e("SETTINGS_DIAGNOSTIC",
            "BEFORE_ANIMATION: attached=${panel.isAttachedToWindow}, " +
            "w=${panel.width}, h=${panel.height}, visibility=${panel.visibility}")

        try {
        // #region agent log
        writeDebugLog("A", "MainActivity.kt:800", "showSettingsPanel() called", mapOf("timestamp" to System.currentTimeMillis()))
        // #endregion
        android.util.Log.d("ResolutionClick", "showSettingsPanel() called")

            // 1. If panel is GONE → set to INVISIBLE (MANDATORY)
            if (panel.visibility == View.GONE) {
                android.util.Log.d("SettingsDebug", "Panel was GONE, setting to INVISIBLE")
                panel.visibility = View.INVISIBLE
            }

            // 2. Wait for layout using doOnLayout
            panel.doOnLayout { view ->
                android.util.Log.d("SettingsDebug", "=== DO ON LAYOUT CALLED ===")
                android.util.Log.d("SettingsDebug", "Settings panel measured - width: ${view.width}, height: ${view.height}")

                // 3. Read width AFTER layout
                val width = view.width
                if (width == 0) {
                    android.util.Log.e("SettingsDebug", "Width still 0, abort animation")
                    return@doOnLayout
                }

                android.util.Log.d("SettingsDebug", "Panel width measured as: $width")

                // 4. Set translationX = width (start off-screen to the right)
                view.translationX = width.toFloat()
                android.util.Log.d("SettingsDebug", "Set translationX to $width")
                
                // 4.5. Reset alpha to 1f (fully visible state)
                // CRITICAL FIX: hideSettingsPanel() animates alpha to 0f, 
                // so we must reset it to 1f on every open to ensure panel is visible
                view.alpha = 1f
                android.util.Log.d("SettingsDebug", "Reset alpha to 1f (fix for reopen bug)")

                // 5. Set visibility = VISIBLE
                view.visibility = View.VISIBLE
                android.util.Log.d("SettingsDebug", "Set visibility to VISIBLE")
                
                // 5.5. Show scrim (background overlay)
        binding.settingsScrim.visibility = View.VISIBLE
                binding.settingsScrim.alpha = 0f  // Start transparent
        binding.settingsScrim.animate()
                    .alpha(1f)  // Fade in to visible
            .setDuration(300)
            .start()
                android.util.Log.d("SettingsDebug", "Showing scrim with fade-in animation")

                // 5.6. Bring to front and ensure touch priority
                view.bringToFront()
                view.isClickable = true
                view.isFocusable = true

                // Disable touch interception on camera views to ensure settings panel gets all touches
                binding.cameraFrame.isClickable = false
                binding.cameraFrame.isFocusable = false
                binding.cameraFrame.setOnTouchListener { _, _ -> false } // Don't consume touches

                android.util.Log.d("SettingsDebug", "Brought panel to front, enabled touch, disabled camera touch interception")

                // 6. Animate translationX → 0
                view.animate()
            .translationX(0f)
            .setDuration(300)
            .start()

                android.util.Log.d("SettingsDebug", "Started slide-in animation")
            }

            android.util.Log.d("SettingsDebug", "=== SHOW SETTINGS PANEL END ===")
        } catch (e: Exception) {
            android.util.Log.e("SettingsDebug", "EXCEPTION in showSettingsPanel: ${e.message}", e)
        }
    }
    
    private fun hideSettingsPanel() {
        // Hide scrim
        binding.settingsScrim.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                binding.settingsScrim.visibility = View.GONE
            }
            .start()
        
        // Hide settings panel - ensure we have the measured width
        if (binding.settingsPanel.width > 0) {
            // View is already measured, animate directly
        binding.settingsPanel.animate()
            .alpha(0f)
            .translationX(binding.settingsPanel.width.toFloat())
            .setDuration(300)
            .withEndAction {
                binding.settingsPanel.visibility = View.GONE
                    // Restore camera touch behavior
                    binding.cameraFrame.isClickable = true
                    binding.cameraFrame.isFocusable = true
                    binding.cameraFrame.setOnTouchListener(null) // Remove touch listener
            }
            .start()
        } else {
            // View not measured yet, use doOnLayout
            binding.settingsPanel.doOnLayout { view ->
                view.animate()
                    .alpha(0f)
                    .translationX(view.width.toFloat())
                    .setDuration(300)
                    .withEndAction {
                        view.visibility = View.GONE
                        // Restore camera touch behavior
                        binding.cameraFrame.isClickable = true
                        binding.cameraFrame.isFocusable = true
                        binding.cameraFrame.setOnTouchListener(null) // Remove touch listener
                    }
                    .start()
            }
        }
    }
    
    private fun showResolutionDropdown() {
        cameraControlCoordinator.showResolutionDropdown()
        showSettingsPanel()
    }
    
    private fun showModeSelector() {
        // This will be handled by the mode spinner in the settings panel
        showSettingsPanel()
    }
    
    private fun setupSpinners() {
        // Setup resolution spinner
        val resolutionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf<String>())
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.resolutionSpinner.adapter = resolutionAdapter
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
            cameraModeController.applyCameraMode(CameraMode.NORMAL)
            settingsBottomSheet?.dismiss()
        }
        
        view.findViewById<View>(R.id.btnFluorescenceMode).setOnClickListener {
            cameraModeController.applyCameraMode(CameraMode.CARIES)
            settingsBottomSheet?.dismiss()
        }
        
        // Setup reset button
        view.findViewById<View>(R.id.btnResetSettings).setOnClickListener {
            resetCameraControls()
            settingsBottomSheet?.dismiss()
        }
    }
    
    
    private fun openGallery() {
        // Use the same session manager instance to ensure consistency
        val currentSessionId = sessionController.getCurrentSessionIdOrCreate()
        
        android.util.Log.d("GalleryDebug", "Opening gallery with session ID: $currentSessionId")
        
        // Test database before opening gallery
        testDatabaseBeforeGallery(currentSessionId)
        
        // Show gallery fragment instead of starting new activity
        showGalleryFragment()
    }
    
    private fun showGalleryFragment() {
        android.util.Log.d("GalleryDebug", "Showing gallery fragment")
        
        // Create and show the gallery fragment
        val galleryFragment = com.oralvis.oralviscamera.gallery.GalleryFragment()
        
        supportFragmentManager.beginTransaction()
            .add(R.id.main, galleryFragment, "GalleryFragment")
            .commit()
        
        // Hide camera controls when gallery is shown
        binding.settingsPanel.visibility = View.GONE
    }
    
    private fun hideGalleryFragment() {
        android.util.Log.d("GalleryDebug", "Hiding gallery fragment")
        
        val galleryFragment = supportFragmentManager.findFragmentByTag("GalleryFragment")
        if (galleryFragment != null) {
            supportFragmentManager.beginTransaction()
                .remove(galleryFragment)
                .commit()
        }
        
        // Show camera controls when gallery is hidden
        // Camera controls are always visible in the main layout
    }
    
    private fun testDatabaseBeforeGallery(sessionId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val allMedia = mediaDatabase.mediaDao().getAllMedia()
                allMedia.collect { mediaList ->
                    android.util.Log.d("GalleryDebug", "MainActivity - Total media in DB: ${mediaList.size}")
                    mediaList.forEach { media ->
                        android.util.Log.d("GalleryDebug", "MainActivity - Media: ${media.fileName} - Session: ${media.sessionId}")
                    }
                    
                    val sessionMedia = mediaList.filter { it.sessionId == sessionId }
                    android.util.Log.d("GalleryDebug", "MainActivity - Media for session $sessionId: ${sessionMedia.size}")
                    
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "DB has ${mediaList.size} total, ${sessionMedia.size} for current session", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("GalleryDebug", "MainActivity - Database test failed: ${e.message}")
            }
        }
    }

    /**
     * Observe global patient changes and update UI accordingly
     */
    private fun observeGlobalPatient() {
        // Remove existing observer if it exists
        globalPatientObserver?.let {
            GlobalPatientManager.currentPatient.removeObserver(it)
        }

        // Create new observer
        globalPatientObserver = androidx.lifecycle.Observer<com.oralvis.oralviscamera.database.Patient?> { patient ->
            if (patient != null) {
                selectedPatient = patient
                globalPatientId = patient.code
                updatePatientInfoDisplay()
                binding.patientInfoCard.visibility = View.VISIBLE
            } else {
                selectedPatient = null
                globalPatientId = null
                binding.patientInfoCard.visibility = View.GONE
            }
        }

        // Add the observer
        GlobalPatientManager.currentPatient.observe(this, globalPatientObserver!!)
    }

    
    /**
     * Initialize from global patient if one is already selected
     */
    private fun initializeFromGlobalPatient() {
        val patient = GlobalPatientManager.getCurrentPatient()
        if (patient != null) {
            selectedPatient = patient
            globalPatientId = patient.code
            updatePatientInfoDisplay()
            binding.patientInfoCard.visibility = View.VISIBLE
        } else {
            binding.patientInfoCard.visibility = View.GONE
        }
    }
    
    /**
     * If MainActivity was launched with an explicit patient extra (from PatientSelectionActivity),
     * and no patient is yet selected in GlobalPatientManager, resolve it from the database and
     * set it globally. This makes patient selection robust even if the process was recreated
     * and GlobalPatientManager state was lost.
     */
    private fun maybeInitializePatientFromIntent() {
        if (GlobalPatientManager.hasPatientSelected()) return
        
        val explicitPatientId = intent?.getLongExtra("patient_id", -1L) ?: -1L
        if (explicitPatientId == -1L) return
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val patient = mediaDatabase.patientDao().getPatientById(explicitPatientId)
                if (patient != null) {
                    withContext(Dispatchers.Main) {
                        android.util.Log.d(
                            "PatientSelection",
                            "Initializing patient from intent: id=${patient.id}, code=${patient.code}"
                        )
                        GlobalPatientManager.setCurrentPatient(this@MainActivity, patient)
                    }
                } else {
                    android.util.Log.w(
                        "PatientSelection",
                        "Unable to initialize patient from intent, id=$explicitPatientId not found"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("PatientSelection", "Error initializing patient from intent", e)
            }
        }
    }
    
    /**
     * Updates the patient info display with current patient details from GlobalPatientManager
     */
    fun updatePatientInfoDisplay() {
        val patient = GlobalPatientManager.getCurrentPatient()
        patient?.let {
            binding.txtPatientName.text = it.displayName
            val localId = LocalPatientIdManager.getLocalId(it.id)
            binding.txtPatientDetails.text = "ID: $localId • Age: ${it.age ?: "N/A"}"
        }
    }
    
    /**
     * Applies the current theme to all UI elements
     */
    private fun applyTheme() {
        // Get theme colors
        val backgroundColor = themeManager.getBackgroundColor(this)
        val surfaceColor = themeManager.getSurfaceColor(this)
        val cardColor = themeManager.getCardColor(this)
        val textPrimary = themeManager.getTextPrimaryColor(this)
        val textSecondary = themeManager.getTextSecondaryColor(this)
        val borderColor = themeManager.getBorderColor(this)
        
        // Apply background color
        binding.main.setBackgroundColor(backgroundColor)
        
        // ============= NAVIGATION BAR =============
        binding.navigationRailCard.setCardBackgroundColor(surfaceColor)
        
        // Update nav icons and backgrounds - Camera is selected
        binding.navLogo.setColorFilter(textPrimary)
        
        // Camera - selected
        binding.navCamera.setBackgroundResource(R.drawable.nav_icon_selected_background)
        binding.navCamera.setColorFilter(textPrimary)
        
        // Gallery - not selected
        binding.navGallery.setBackgroundResource(R.drawable.nav_icon_background)
        binding.navGallery.setColorFilter(textSecondary)
        
        // Find Patients - not selected
        binding.navFindPatients.setBackgroundResource(R.drawable.nav_icon_background)
        binding.navFindPatients.setColorFilter(textSecondary)
        
        // Patient Selection - not selected
        binding.navPatient.setBackgroundResource(R.drawable.nav_icon_background)
        binding.navPatient.setColorFilter(textSecondary)
        
        // Nav text labels
        val navLayout = binding.navigationRailCard.getChildAt(0) as? android.view.ViewGroup
        navLayout?.let { layout ->
            for (i in 0 until layout.childCount) {
                val child = layout.getChildAt(i)
                if (child is TextView) {
                    if (child.text == "Camera") {
                        child.setTextColor(textPrimary)
                    } else {
                        child.setTextColor(textSecondary)
                    }
                }
            }
        }
        
        // ============= TOP INFO BAR =============
        binding.topInfoBar.setBackgroundColor(if (themeManager.isDarkTheme) android.graphics.Color.TRANSPARENT else surfaceColor)
        
        // Mode toggle labels
        binding.modeNormalLabel.setTextColor(textPrimary)
        binding.modeCariesLabel.setTextColor(textPrimary)
        
        // Status text background
        val statusBg = if (themeManager.isDarkTheme) {
            ContextCompat.getDrawable(this, R.drawable.status_background)
        } else {
            android.graphics.drawable.GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = 20f
                setStroke(2, borderColor)
            }
        }
        binding.statusText.background = statusBg
        binding.statusText.setTextColor(textPrimary)
        
        // Recording timer
        binding.recordingTimer.setTextColor(textPrimary)
        
        // Resolution selector
        val resolutionBg = if (themeManager.isDarkTheme) {
            ContextCompat.getDrawable(this, R.drawable.status_background)
        } else {
            android.graphics.drawable.GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = 20f
                setStroke(2, borderColor)
            }
        }
        // Top toolbar resolution spinner uses spinner_background drawable which is theme-aware
        // No additional theme changes needed for spinner
        
        // Settings button
        binding.btnSettings.setColorFilter(textPrimary)
        val settingsBg = if (themeManager.isDarkTheme) {
            ContextCompat.getDrawable(this, R.drawable.nav_icon_background)
        } else {
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(cardColor)
                cornerRadius = 20f
            }
        }
        binding.btnSettings.background = settingsBg
        
        // ============= CAMERA PREVIEW =============
        binding.cameraPreviewCard.setCardBackgroundColor(if (themeManager.isDarkTheme) Color.BLACK else Color.WHITE)
        binding.cameraPreviewCard.strokeColor = borderColor
        
        // ============= SESSION MEDIA CARD =============
        binding.sessionMediaCard.setCardBackgroundColor(cardColor)
        // Update text colors in session media card
        val sessionMediaLayout = binding.sessionMediaCard.getChildAt(0) as? android.view.ViewGroup
        sessionMediaLayout?.let { updateTextColors(it, textPrimary, textSecondary) }
        
        // ============= PATIENT INFO CARD =============
        binding.patientInfoCard.setCardBackgroundColor(cardColor)
        binding.txtPatientName.setTextColor(textPrimary)
        binding.txtPatientDetails.setTextColor(textSecondary)
        
        // ============= BOTTOM CONTROL PANEL =============
        binding.bottomControlPanel.setBackgroundColor(if (themeManager.isDarkTheme) surfaceColor else cardColor)
        
        // ============= SETTINGS PANEL =============
        binding.settingsPanel.setBackgroundColor(cardColor)
        binding.settingsScrim.setBackgroundColor(if (themeManager.isDarkTheme) Color.parseColor("#80000000") else Color.parseColor("#80FFFFFF"))
        
        // Update settings header background
        val settingsHeaderBg = if (themeManager.isDarkTheme) {
            ContextCompat.getDrawable(this, R.drawable.settings_header_background)
        } else {
            android.graphics.drawable.GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = 0f
            }
        }
        binding.settingsHeader.background = settingsHeaderBg
        
        // Update settings header title
        val headerLayout = binding.settingsHeader
        for (i in 0 until headerLayout.childCount) {
            val child = headerLayout.getChildAt(i)
            if (child is TextView) {
                child.setTextColor(textPrimary)
            }
        }
        
        // Update close button in settings
        binding.btnCloseSettings.setColorFilter(textPrimary)
        
        // Update all text in settings panel
        val settingsScrollView = binding.settingsPanel.getChildAt(1) as? android.widget.ScrollView
        settingsScrollView?.let { scrollView ->
            val settingsContent = scrollView.getChildAt(0) as? ViewGroup
            settingsContent?.let { content ->
                updateTextColors(content, textPrimary, textSecondary)
                updateSeekBarColors(content)
            }
        }
        
        // Update resolution spinner in settings
        val resolutionSpinnerBg = if (themeManager.isDarkTheme) {
            ContextCompat.getDrawable(this, R.drawable.spinner_background)
        } else {
            ContextCompat.getDrawable(this, R.drawable.spinner_background_light)
        }
        binding.resolutionSpinner.background = resolutionSpinnerBg
        
        // Mode spinner removed; mode is controlled by top-left Normal/Caries toggle
    }
    
    /**
     * Helper function to recursively update text colors in a ViewGroup
     */
    private fun updateTextColors(viewGroup: ViewGroup, primaryColor: Int, secondaryColor: Int) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            when (child) {
                is TextView -> {
                    child.setTextColor(primaryColor)
                }
                is ViewGroup -> {
                    updateTextColors(child, primaryColor, secondaryColor)
                }
            }
        }
    }
    
    /**
     * Helper function to recursively update SeekBar colors in a ViewGroup
     */
    private fun updateSeekBarColors(viewGroup: ViewGroup) {
        val progressColor = if (themeManager.isDarkTheme) Color.WHITE else Color.parseColor("#1F2937")
        val thumbColor = if (themeManager.isDarkTheme) Color.WHITE else Color.parseColor("#2563EB")
        
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            when (child) {
                is SeekBar -> {
                    child.progressTintList = android.content.res.ColorStateList.valueOf(progressColor)
                    child.thumbTintList = android.content.res.ColorStateList.valueOf(thumbColor)
                }
                is ViewGroup -> {
                    updateSeekBarColors(child)
                }
            }
        }
    }
    
    // Mode spinner was removed; mode is now controlled by a Normal/Caries toggle.
    
    /**
     * Saves the current session and captured media
     */
    private fun saveCurrentSession() {
        sessionFlowCoordinator.saveCurrentSession()
    }
    
    private fun loadAvailableResolutions() {
        cameraControlCoordinator.loadAvailableResolutions()
    }
    
    /**
     * Reload resolutions with retry logic and exponential backoff
     * @param maxRetries Maximum number of retry attempts
     * @param initialDelayMs Initial delay before first retry (increases exponentially)
     * @param onSuccess Callback when resolutions are successfully loaded
     * @param onFailure Callback when all retries are exhausted
     */
    private fun reloadResolutionsWithRetry(
        maxRetries: Int = 5,
        initialDelayMs: Long = 500,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null
    ) {
        cameraControlCoordinator.reloadResolutionsWithRetry(maxRetries, initialDelayMs, onSuccess, onFailure)
    }
    
    private fun updateResolutionUI() {
        cameraControlCoordinator.updateResolutionUI()
    }
    
    /**
     * Check if a resolution change is currently pending (flag set or handler active)
     */
    private fun isResolutionChangePending(): Boolean {
        return cameraControlCoordinator.isResolutionChangePending()
    }
    
    private fun setupTopToolbarResolutionSelector() {
        cameraControlCoordinator.setupTopToolbarResolutionSelector()
    }
    
    private fun showResolutionPopupMenu(anchorView: View) {
        cameraControlCoordinator.showResolutionPopupMenu(anchorView)
    }
    
    /**
     * Simplified resolution change method matching demo app behavior
     * Just calls updateResolution() directly - MultiCameraClient handles camera stop/start
     * This matches the demo app's simple approach - no complex state management needed
     */
    private fun changeResolutionSimple(newResolution: PreviewSize) {
        cameraControlCoordinator.changeResolutionSimple(newResolution)
    }
    
    // Keep old method name for backward compatibility but redirect to new method
    private fun setupTopToolbarResolutionSpinner() {
        setupTopToolbarResolutionSelector()
    }
    
    private fun setupResolutionSpinnerWithRetry(view: View, retryCount: Int) {
        cameraControlCoordinator.setupResolutionSpinnerWithRetry(view, retryCount)
    }
    
    override fun refreshCameraReference() {
        android.util.Log.d("ResolutionManager", "Attempting to refresh camera reference")
        try {
            // Try to find an active camera in the camera map
            if (mCameraMap.isNotEmpty()) {
                val activeCamera = mCameraMap.values.firstOrNull()
                if (activeCamera != null) {
                    cameraStateStore.mCurrentCamera = activeCamera
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
    
    override fun isCameraReadyForRecording(): Boolean {
        if (cameraStateStore.mCurrentCamera == null) {
            android.util.Log.d("RecordingManager", "Camera readiness check: cameraStateStore.mCurrentCamera is null")
            return false
        }
        
        try {
            // Check if camera can provide a camera request (basic functionality)
            val cameraRequest = (cameraStateStore.mCurrentCamera as? CameraUVC)?.getCameraRequest()
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
        return cameraControlCoordinator.isCameraReadyForResolutionChange()
    }
    
    private fun setupResolutionSpinner(view: View) {
        cameraControlCoordinator.setupResolutionSpinner(view)
    }
    
    private fun changeResolution(newResolution: PreviewSize, txtCurrentResolution: TextView) {
        cameraControlCoordinator.changeResolution(newResolution, txtCurrentResolution)
    }
    
    private fun updateCurrentResolutionDisplay(txtCurrentResolution: TextView) {
        cameraControlCoordinator.updateCurrentResolutionDisplay(txtCurrentResolution)
    }
    
    private fun verifyCurrentResolution() {
        cameraControlCoordinator.verifyCurrentResolution()
    }
    
    private fun refreshResolutionSpinnerIfOpen() {
        cameraControlCoordinator.refreshResolutionSpinnerIfOpen()
    }
    
    private fun checkPermissions() {
        // FIX 2: Defer runtime permission dialogs until USB permission is resolved
        if (usbPermissionPending) {
            deferredRuntimePermissionCheck = true
            cameraStartupCoordinator.onSurfaceReady()
            return
        }
        val requiredPermissions = getRequiredPermissions()
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            cameraStartupCoordinator.onSurfaceReady()
            return
        }

        val needsRationale = missingPermissions.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
        if (needsRationale) {
            showPermissionExplanationDialog(missingPermissions)
        } else {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_PERMISSION)
        }

        // FORCE camera initialization on cold launch regardless of permission status
        // USB cameras may work without Android CAMERA permission
        cameraStartupCoordinator.onSurfaceReady()
    }
    
    private fun showPermissionExplanationDialog(missingPermissions: List<String>) {
        val hasStoragePermission = missingPermissions.any { 
            it == Manifest.permission.WRITE_EXTERNAL_STORAGE || 
            it == Manifest.permission.READ_EXTERNAL_STORAGE ||
            it == Manifest.permission.READ_MEDIA_IMAGES ||
            it == Manifest.permission.READ_MEDIA_VIDEO
        }
        
        val message = if (hasStoragePermission) {
            "This app needs storage permission to save photos and videos. Please allow storage access in settings."
        } else {
            "This app needs camera and audio permissions to function properly. Please allow these permissions."
        }
        
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage(message)
            .setPositiveButton("Allow") { _, _ ->
                ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_PERMISSION)
            }
            .setNegativeButton("Cancel") { _, _ ->
                Toast.makeText(this, "Permissions are required for the app to work properly", Toast.LENGTH_LONG).show()
                binding.statusText.text = "Permissions denied. Some features may not work."
                binding.statusText.visibility = View.VISIBLE
                // Still try to initialize camera - USB camera might work without all permissions
                cameraStartupCoordinator.onSurfaceReady()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * Ensures USBMonitor is registered so ACTION_USB_PERMISSION and device attach
     * are always received. Call early in onCreate. Camera open is gated by
     * SurfaceTexture in onConnectDev / initializeCameraIfReady.
     */
    private fun ensureUsbMonitorRegistered() {
        if (mCameraClient != null) return

        binding.statusText.text = "Initializing camera..."
        
        mCameraClient = MultiCameraClient(this, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                device ?: return
                // Sequential pipeline: never add CDC to mCameraMap or request permission here.
                // CDC is requested only after UVC is granted, from tryRequestNextUsbPermissionOrComplete().
                if (device.vendorId == 0x1209 && device.productId == 0xC550) {
                    return
                }
                if (mCameraMap.containsKey(device.deviceId)) {
                    return
                }
                if (!CameraUtils.isUsbCamera(device) && !CameraUtils.isFilterDevice(this@MainActivity, device)) {
                    return
                }
                // Create camera instance and store in map (UVC only)
                val camera = CameraUVC(this@MainActivity, device)
                mCameraMap[device.deviceId] = camera
                binding.statusText.text = "USB Camera detected - Requesting permission..."
                usbPermissionPending = true
                mCameraClient?.requestPermission(device)
            }
            
            override fun onDetachDec(device: UsbDevice?) {
                if (device != null && device.vendorId == 0x1209 && device.productId == 0xC550) {
                    // CDC only: never in mCameraMap; don't clear camera state
                    return
                }
                cameraStateStore.mPendingCameraOpen = null
                cancelCdcStartSchedule()
                mCameraMap.remove(device?.deviceId)
                cameraStateStore.mCurrentCamera?.closeCamera()
                cameraStateStore.mCurrentCamera = null
                binding.statusText.text = "USB Camera removed"
                binding.statusText.visibility = View.VISIBLE
                cameraControlCoordinator.onCameraDetached()
                setupTopToolbarResolutionSelector()
                // After USB grant the device often goes through rapid attach/detach cycles.
                // Schedule a reconnect so when the storm settles, we pick the device back up.
                android.util.Log.d("CAMERA_LIFE", "onDetachDec: UVC device detached — scheduling reconnect")
                scheduleReconnectCamera(2500)
            }
            
            override fun onConnectDev(device: UsbDevice?, ctrlBlock: com.jiangdg.usb.USBMonitor.UsbControlBlock?) {
                device ?: return
                ctrlBlock ?: return
                // Camera is connecting — cancel any pending reconnect attempts
                binding.root.removeCallbacks(reconnectCameraRunnable)
                // FIX 2: USB permission resolved; allow deferred patient/runtime dialogs
                usbPermissionPending = false
                // Camera preparation and open/pending FIRST; tryDeferredPermissionChecks LAST
                // so initializeCameraIfReady() runs after mPendingCameraOpen is set (first-run fix).

                android.util.Log.d("CAMERA_LIFE", "Camera connected")
                binding.statusText.text = "Camera connected - Opening camera..."
                
                // Get camera from map and set control block
                mCameraMap[device.deviceId]?.apply {
                    setUsbControlBlock(ctrlBlock)
                }?.also { camera ->
                    cameraStateStore.mCurrentCamera = camera
                    
                    // Set camera state callback (Phase 5A: CameraLifecycleManager owns state; MainActivity runs OPENED/CLOSED/ERROR UI and USB/guided)
                    camera.setCameraStateCallBack(cameraLifecycleManager.createStateCallback(
                        runOnUiThread = { runnable -> runOnUiThread(runnable) },
                        onOpened = {
                            binding.statusText.text = "Camera opened successfully"
                            binding.statusText.visibility = View.GONE
                            val displayMetrics = resources.displayMetrics
                            val screenWidth = displayMetrics.widthPixels
                            val screenHeight = displayMetrics.heightPixels
                            binding.cameraTextureView.setAspectRatio(screenWidth, screenHeight)
                            loadAvailableResolutions()
                            cameraControlCoordinator.onCameraOpened()
                            cameraModeController.setCurrentModeNormal()
                            android.util.Log.d("CameraMode", "Normal mode set (preset will be applied after first frame)")
                            verifyCurrentResolution()
                            refreshResolutionSpinnerIfOpen()
                            cameraStartupCoordinator.activateCameraPipeline()
                            val deviceConnection = ctrlBlock.connection
                            if (deviceConnection != null) {
                                try {
                                    usbSerialManager?.onCameraOpened(deviceConnection, device!!)
                                    android.util.Log.d("CameraSerial", "✅ Stored camera's device connection - starting CDC immediately")
                                    usbController?.start()
                                    android.util.Log.d("CameraSerial", "✅ CDC started (hardware buttons active)")
                                } catch (e: Exception) {
                                    android.util.Log.e("CameraSerial", "❌ Error storing camera connection or starting CDC: ${e.message}", e)
                                }
                            } else {
                                android.util.Log.w("CameraSerial", "⚠️ Camera control block has null connection - CDC cannot start")
                            }
                            // Guided reattach done inside activateCameraPipeline() via previewCallbackRouter
                        },
                        onClosed = {
                            previewCallbackRouter.onCameraClosed(camera)
                            cancelCdcStartSchedule()
                            binding.statusText.text = "Camera closed"
                            binding.statusText.visibility = View.VISIBLE
                            android.util.Log.d("ResolutionManager", "Camera closed - isResolutionChanging: ${cameraControlCoordinator.isResolutionChanging}")
                            if (cameraControlCoordinator.isResolutionChanging) {
                                android.util.Log.d("ResolutionManager", "Camera closed during resolution change - this is expected, delayed handler will manage flag")
                            }
                            videoCaptureHandler.resetRecordingState()
                            usbSerialManager?.onCameraClosed()
                        },
                        onError = { msg ->
                            binding.statusText.text = "Camera error: $msg"
                            binding.statusText.visibility = View.VISIBLE
                        }
                    ))
                    
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
                        .setRawPreviewData(true)  // Enable raw preview data for motion analysis
                        .create()
                    
                    // Open camera when SurfaceTexture is ready; else defer until it is
                    if (cameraStateStore.isSurfaceTextureReady) {
                        android.util.Log.d("CAMERA_LIFE", "Opening camera with resolution ${recordingWidth}x${recordingHeight}")
                        camera.openCamera(binding.cameraTextureView, cameraRequest)
                    } else {
                        cameraStateStore.mPendingCameraOpen = Pair(camera, cameraRequest)
                        android.util.Log.d("CameraGate", "Deferred openCamera - SurfaceTexture not ready, will open when ready")
                    }
                }
                // Sequential permission: after this device was handled, request next or complete
                if (device.vendorId == 0x1209 && device.productId == 0xC550) {
                    // CDC device granted: not in mCameraMap; mark complete and start CDC if camera already open
                    usbPermissionStage = UsbPermissionStage.COMPLETE
                    usbPermissionPending = false
                    tryDeferredPermissionChecks()
                    if (cameraStateStore.mCurrentCamera != null && cameraStateStore.isCameraReady) {
                        usbController?.start()
                    }
                } else {
                    // UVC device granted: ALWAYS try to request CDC next (regardless of current stage).
                    // On first run the stage might have been cleared by attach/detach storm or
                    // onCancelDev(null), so don't gate on REQUESTING_UVC.
                    android.util.Log.d("CAMERA_LIFE", "UVC connected — requesting CDC permission next (stage was $usbPermissionStage)")
                    tryRequestNextUsbPermissionOrComplete()
                }
            }
            
            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: com.jiangdg.usb.USBMonitor.UsbControlBlock?) {
                if (device != null && device.vendorId == 0x1209 && device.productId == 0xC550) {
                    // CDC only: don't close UVC camera
                    return
                }
                cameraStateStore.mPendingCameraOpen = null
                cancelCdcStartSchedule()
                cameraStateStore.mCurrentCamera?.closeCamera()
                cameraStateStore.mCurrentCamera = null
                binding.statusText.text = "Camera disconnected"
                binding.statusText.visibility = View.VISIBLE
            }
            
            override fun onCancelDev(device: UsbDevice?) {
                // When activity was stopped during the USB dialog, the system may deliver "cancel"
                // with device=null even though the user granted. Do NOT clear pending state so
                // onResume + reconnectCameraRunnable can re-check and connect.
                if (device != null) {
                    usbPermissionPending = false
                    usbPermissionStage = UsbPermissionStage.NONE
                    tryDeferredPermissionChecks()
                    binding.statusText.text = "Permission denied"
                } else {
                    android.util.Log.i("CAMERA_LIFE", "onCancelDev(device=null) - likely grant while stopped; scheduling reconnect")
                    // Schedule reconnect to pick up the granted permission (UVC or CDC)
                    scheduleReconnectCamera(1000)
                }
            }
        })
        
        mCameraClient?.register()
    }

    /**
     * Sequential USB permission: request at most ONE device per call.
     * UVC first; after UVC is granted, request CDC from onConnectDev via tryRequestNextUsbPermissionOrComplete().
     * Android allows only one outstanding request at a time.
     */
    private fun requestPermissionForAlreadyAttachedDevices() {
        if (hasRunInitialUsbDeviceScan) return
        val client = mCameraClient ?: return
        hasRunInitialUsbDeviceScan = true

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = client.getDeviceList() ?: return

        // Ensure first UVC device (not CDC) is in mCameraMap (for onConnectDev to resolve camera)
        for (device in devices) {
            if (isCdcDevice(device)) continue
            if (!CameraUtils.isUsbCamera(device) && !CameraUtils.isFilterDevice(this, device)) continue
            if (mCameraMap.containsKey(device.deviceId)) break
            val camera = CameraUVC(this, device)
            mCameraMap[device.deviceId] = camera
            break
        }

        // Request only UVC if needed (one request at a time). If UVC already granted, trigger
        // processConnect so onConnectDev(UVC) runs and camera opens (needed on 2nd+ launch).
        for (device in devices) {
            if (isCdcDevice(device)) continue
            if (!CameraUtils.isUsbCamera(device) && !CameraUtils.isFilterDevice(this, device)) continue
            if (!usbManager.hasPermission(device)) {
                usbPermissionStage = UsbPermissionStage.REQUESTING_UVC
                usbPermissionPending = true
                binding.statusText.text = "USB Camera detected - Requesting permission..."
                android.util.Log.i("CAMERA_LIFE", "Requesting USB permission for UVC camera deviceId=${device.deviceId} vid=${device.vendorId} pid=${device.productId}")
                client.requestPermission(device)
                return
            }
            // Already have UVC permission: trigger connect so onConnectDev runs and camera opens
            android.util.Log.i("CAMERA_LIFE", "UVC already granted - triggering connect for deviceId=${device.deviceId}")
            client.requestPermission(device)
            return
        }

        // UVC already granted (or no UVC); request CDC if needed (one request at a time)
        for (device in devices) {
            if (device.vendorId == 0x1209 && device.productId == 0xC550) {
                if (!usbManager.hasPermission(device)) {
                    usbPermissionStage = UsbPermissionStage.REQUESTING_CDC
                    usbPermissionPending = true
                    binding.statusText.text = "OralVis controller - Requesting permission..."
                    android.util.Log.i("CDC_TRACE", "Requesting permission for OralVis CDC device (separate) deviceId=${device.deviceId}")
                    client.requestPermission(device)
                    return
                }
                break
            }
        }

        usbPermissionStage = UsbPermissionStage.COMPLETE
        usbPermissionPending = false
    }

    /**
     * After UVC permission granted: request CDC if needed, or mark COMPLETE.
     * Called from onConnectDev(UVC) only. Never requests both in same call stack.
     */
    private fun tryRequestNextUsbPermissionOrComplete() {
        val client = mCameraClient ?: return
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = client.getDeviceList() ?: return

        usbPermissionStage = UsbPermissionStage.NONE

        for (device in devices) {
            if (device.vendorId == 0x1209 && device.productId == 0xC550) {
                if (!usbManager.hasPermission(device)) {
                    usbPermissionStage = UsbPermissionStage.REQUESTING_CDC
                    usbPermissionPending = true
                    binding.statusText.text = "OralVis controller - Requesting permission..."
                    android.util.Log.i("CDC_TRACE", "Requesting permission for CDC (after UVC granted) deviceId=${device.deviceId}")
                    client.requestPermission(device)
                    return
                }
                break
            }
        }

        usbPermissionStage = UsbPermissionStage.COMPLETE
        usbPermissionPending = false
        tryDeferredPermissionChecks()
    }

    /**
     * FIX 2: Run deferred checkPermissions and/or patient dialog after USB permission
     * has been resolved (granted or denied).
     */
    private fun tryDeferredPermissionChecks() {
        if (deferredRuntimePermissionCheck) {
            deferredRuntimePermissionCheck = false
            checkPermissions()
        }
        checkAndPromptForPatientSelection()
    }
    
    private fun updateCameraControlValues() {
        cameraControlCoordinator.updateCameraControlValues()
    }
    
    private fun setDefaultCameraControlValues() {
        cameraControlCoordinator.setDefaultCameraControlValues()
    }
    
    override fun isCameraReadyForPhotoCapture(): Boolean {
        if (cameraStateStore.mCurrentCamera == null) {
            android.util.Log.d("PhotoManager", "Camera readiness check: cameraStateStore.mCurrentCamera is null")
            return false
        }
        
        try {
            // Check if camera can provide a camera request (basic functionality)
            val cameraRequest = (cameraStateStore.mCurrentCamera as? CameraUVC)?.getCameraRequest()
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
    
    override fun createVideoFile(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val videoFileName = "OralVis_Video_${cameraModeController.currentMode}_$timestamp.mp4"
        
        // Use session-based directory
        val sessionId = sessionController.getCurrentSessionId()
        val sessionDir = File(getExternalFilesDir(null), "Sessions/$sessionId")
        val videoDir = File(sessionDir, "Videos")
        if (!videoDir.exists()) {
            videoDir.mkdirs()
        }
        
        val videoFile = File(videoDir, videoFileName)
        return videoFile.absolutePath
    }
    
    override fun createImageFile(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "OralVis_Image_${cameraModeController.currentMode}_$timestamp.jpg"
        
        // Use session-based directory
        val sessionId = sessionController.getCurrentSessionId()
        val sessionDir = File(getExternalFilesDir(null), "Sessions/$sessionId")
        val imageDir = File(sessionDir, "Images")
        if (!imageDir.exists()) {
            imageDir.mkdirs()
        }
        
        val imageFile = File(imageDir, imageFileName)
        return imageFile.absolutePath
    }
    
    private fun logMediaToDatabase(
        filePath: String,
        mediaType: String,
        guidedSessionId: String? = null,
        dentalArch: String? = null,
        sequenceNumber: Int? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                android.util.Log.d("MediaDatabase", "Starting database logging for: $filePath")
                
                // Use the same session manager instance to ensure consistency
                val sessionId = sessionController.getCurrentSessionIdOrCreate()
                android.util.Log.d("MediaDatabase", "Session ID: $sessionId")
                
                val fileName = java.io.File(filePath).name
                android.util.Log.d("MediaDatabase", "File name: $fileName")
                android.util.Log.d("MediaDatabase", "Mode: ${cameraModeController.currentMode}")
                android.util.Log.d("MediaDatabase", "Media type: $mediaType")
                android.util.Log.d("MediaDatabase", "dentalArch: $dentalArch")
                android.util.Log.d("MediaDatabase", "sequenceNumber: $sequenceNumber")
                android.util.Log.d("MediaDatabase", "guidedSessionId: $guidedSessionId")
                
                val modeString = if (cameraModeController.currentMode == CameraMode.CARIES) "Fluorescence" else "Normal"
                val mediaRecord = MediaRecord(
                    sessionId = sessionId,
                    fileName = fileName,
                    mode = modeString,
                    mediaType = mediaType,
                    captureTime = Date(),
                    filePath = filePath,
                    dentalArch = dentalArch,
                    sequenceNumber = sequenceNumber,
                    guidedSessionId = guidedSessionId
                )

                android.util.Log.d("CAPTURE_DEBUG", "About to insert media - selectedPatient: ${selectedPatient?.displayName} (id=${selectedPatient?.id})")
                android.util.Log.d("CAPTURE_DEBUG", "SessionId: $sessionId, patientId in record: ${mediaRecord.patientId}")
                android.util.Log.d("MediaDatabase", "Inserting media record: $mediaRecord")
                val insertedId = mediaDatabase.mediaDao().insertMedia(mediaRecord)
                android.util.Log.d("CAPTURE_DEBUG", "Media inserted with ID: $insertedId for patient ${selectedPatient?.id}")
                
                // Create session in database if it doesn't exist
                createSessionInDatabaseIfNeeded(sessionId)
                
                android.util.Log.d("MediaDatabase", "Media logged to session: $sessionId")
                
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Media logged to database successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaDatabase", "Failed to log media to database: ${e.message}", e)
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
                    val patientId = selectedPatient?.id ?: return@launch
                    val session = Session(
                        sessionId = sessionId,
                        patientId = patientId,
                        createdAt = Date(),
                        displayName = "Session ${SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date())}"
                    )
                    mediaDatabase.sessionDao().insert(session)
                    android.util.Log.d("SessionManager", "Created new session in database: $sessionId for patient: $patientId")
                }
            } catch (e: Exception) {
                android.util.Log.e("SessionManager", "Failed to create session in database: ${e.message}")
            }
        }
    }
    
    private fun toggleCameraControls() {
        cameraControlCoordinator.toggleCameraControls()
    }
    
    private fun resetCameraControls() {
        cameraControlCoordinator.resetCameraControls()
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_PERMISSION) {
            val deniedPermissions = mutableListOf<String>()
            val permanentlyDeniedPermissions = mutableListOf<String>()
            
            for (i in permissions.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permissions[i])
                    // Check if permission is permanently denied
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[i])) {
                        permanentlyDeniedPermissions.add(permissions[i])
                    }
                }
            }
            
            if (deniedPermissions.isEmpty()) {
                Toast.makeText(this, "Permissions granted! Initializing camera...", Toast.LENGTH_SHORT).show()
                cameraStartupCoordinator.onSurfaceReady()
            } else {
                // Check if storage permission is permanently denied
                val hasStoragePermissionDenied = permanentlyDeniedPermissions.any { 
                    it == Manifest.permission.WRITE_EXTERNAL_STORAGE || 
                    it == Manifest.permission.READ_EXTERNAL_STORAGE ||
                    it == Manifest.permission.READ_MEDIA_IMAGES ||
                    it == Manifest.permission.READ_MEDIA_VIDEO
                }
                
                if (hasStoragePermissionDenied) {
                    // Show dialog to guide user to settings
                    AlertDialog.Builder(this)
                        .setTitle("Storage Permission Required")
                        .setMessage("Storage permission is required to save photos and videos. Please enable it in app settings.")
                        .setPositiveButton("Open Settings") { _, _ ->
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                intent.data = Uri.parse("package:$packageName")
                                startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            Toast.makeText(this, "Storage permission is required to save media files", Toast.LENGTH_LONG).show()
                        }
                        .setCancelable(false)
                        .show()
                } else {
                    val message = "Some permissions were denied. App may have limited functionality."
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
                
                binding.statusText.text = "Some permissions denied. App may have limited functionality."
                binding.statusText.visibility = View.VISIBLE
                
                // Still try to initialize camera in case USB camera doesn't need all permissions
                cameraStartupCoordinator.onSurfaceReady()
            }
        }
    }
    
    private fun toggleAutoFocus() {
        cameraControlCoordinator.toggleAutoFocus()
    }
    
    private fun toggleAutoExposure() {
        cameraControlCoordinator.toggleAutoExposure()
    }
    
    private fun toggleAutoWhiteBalance() {
        cameraControlCoordinator.toggleAutoWhiteBalance()
    }
    
    private fun captureRealImageToRepositoryInternal(
        filePath: String,
        mediaType: String,
        guidedSessionId: String?,
        dentalArch: String?,
        sequenceNumber: Int?
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.d("RealCapture", "Processing real camera capture: $filePath")

                // Get current patient and session context
                val patientId = GlobalPatientManager.getCurrentPatientId()
                if (patientId == null) {
                    android.util.Log.w("RealCapture", "No patient selected, cannot process capture")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "No patient selected", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val sessionId = sessionController.getCurrentSessionId()
                android.util.Log.d("RealCapture", "Patient ID: $patientId, Session ID: $sessionId")

                // Read file from disk
                val file = java.io.File(filePath)
                if (!file.exists()) {
                    android.util.Log.e("RealCapture", "Captured file does not exist: $filePath")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Capture file not found", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val fileData = file.readBytes()
                val fileName = file.name

                // Use MediaRepository to create media record atomically
                val modeString = if (cameraModeController.currentMode == CameraMode.CARIES) "Fluorescence" else "Normal"
                val mediaRecord = mediaRepository.createMediaRecord(
                    patientId = patientId,
                    sessionId = sessionId,
                    mediaType = mediaType,
                    mode = modeString,
                    fileName = fileName,
                    dentalArch = dentalArch,
                    sequenceNumber = sequenceNumber,
                    guidedSessionId = guidedSessionId,
                    fileContent = fileData
                )

                if (mediaRecord != null) {
                    android.util.Log.d("RealCapture", "Media record created: ${mediaRecord.mediaId}")

                    // Update UI - add to session media list for immediate feedback
                    withContext(Dispatchers.Main) {
                        addSessionMedia(mediaRecord.filePath!!, isVideo = false)
                    }

                    android.util.Log.d("RealCapture", "Real camera capture processed successfully: ${mediaRecord.mediaId}")
                } else {
                    android.util.Log.e("RealCapture", "Failed to create media record")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to process capture", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                android.util.Log.e("RealCapture", "Failed to process real camera capture: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to process capture: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun captureRealImageToRepository(
        filePath: String,
        mediaType: String,
        guidedSessionId: String?,
        dentalArch: String?,
        sequenceNumber: Int?
    ) {
        captureRealImageToRepositoryInternal(filePath, mediaType, guidedSessionId, dentalArch, sequenceNumber)
    }

    override fun captureRealImageToRepository(filePath: String, mediaType: String) {
        captureRealImageToRepositoryInternal(filePath, mediaType, null, null, null)
    }
    
    override fun onDestroy() {
        super.onDestroy()

        // Cancel any scheduled CDC start
        cancelCdcStartSchedule()

        // Destroy USB serial manager
        usbController?.destroy()
        usbSerialManager = null
        android.util.Log.d("UsbSerial", "USB serial manager destroyed in onDestroy")

        // Remove the observer to prevent memory leaks and duplicate observer crashes
        globalPatientObserver?.let {
            GlobalPatientManager.currentPatient.removeObserver(it)
        }

        // Phase 5B: Unregister preview callbacks via router
        previewCallbackRouter.detachFromCamera(cameraStateStore.mCurrentCamera)

        // Phase 4: Clean up camera control coordinator
        cameraControlCoordinator.cleanupExposureHandler()

        // Check if current session has any media, if not, clean it up
        cleanupEmptySession()

        mCameraClient?.unRegister()
        mCameraClient?.destroy()
        mCameraClient = null
    }
    
    private fun cleanupEmptySession() {
        sessionFlowCoordinator.cleanupEmptySession()
    }
    
    /**
     * Collects and shares full app logs as a ZIP file
     */
    private fun shareFullLogs() {
        // Show progress indicator
        Toast.makeText(this, "Collecting full logs...", Toast.LENGTH_SHORT).show()
            
        lifecycleScope.launch {
            try {
                android.util.Log.d("LogSharing", "Starting full log collection...")

                val zipFile = logCollector.collectAndZipLogs()

                if (zipFile != null && zipFile.exists()) {
                    android.util.Log.d("LogSharing", "Log collection successful: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
                    Toast.makeText(this@MainActivity, "Full logs collected successfully!", Toast.LENGTH_SHORT).show()

                    // Share the ZIP file
                    logCollector.shareLogZip(zipFile)
                                } else {
                    android.util.Log.e("LogSharing", "Log collection failed - no ZIP file created")
                    Toast.makeText(this@MainActivity, "Failed to collect logs. Please try again.", Toast.LENGTH_LONG).show()
                }

                        } catch (e: Exception) {
                android.util.Log.e("LogSharing", "Exception during log collection", e)
                Toast.makeText(this@MainActivity, "Error collecting logs: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Clear all session state to ensure fresh session start
     */
    fun clearSessionState() {
        sessionFlowCoordinator.clearSessionState()
    }

    /**
     * Clear camera preview state
     */
    private fun clearCameraPreview() {
        try {
            // NOTE: Camera should remain connected - do NOT close it
            // This method now only clears UI state, not camera connection

            android.util.Log.d("CAMERA_LIFE", "Camera preview cleared but camera remains connected = ${cameraStateStore.mCurrentCamera != null}")

        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to clear camera preview: ${e.message}")
        }
    }

    // ========== PhotoCaptureHost / VideoCaptureHost (Phase 5C) ==========
    override fun runOnMain(r: Runnable) {
        runOnUiThread(r)
    }
    override fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    override fun isActivityValid(): Boolean = !isFinishing && !isDestroyed
    override fun getCurrentMode(): String =
        if (cameraModeController.currentMode == CameraMode.CARIES) "Fluorescence" else "Normal"
    override fun getPatientId(): Long? = GlobalPatientManager.getCurrentPatientId()
    override fun getSessionId(): String? = sessionController.getCurrentSessionId()
    override fun getCamera(): MultiCameraClient.ICamera? = cameraStateStore.mCurrentCamera
    override fun setRecordButtonBackground(resId: Int) {
        binding.btnRecord.setBackgroundResource(resId)
    }
    override fun setRecordingTimerVisible(visible: Boolean) {
        binding.recordingTimer.visibility = if (visible) View.VISIBLE else View.GONE
    }
    override fun updateRecordingTimerText(text: String) {
        binding.recordingTimer.text = text
    }
    override fun getMainHandler(): Handler = recordingHandlerForVideo!!
    override fun logMediaToDatabase(filePath: String, mediaType: String) {
        logMediaToDatabase(filePath, mediaType, null, null, null)
    }
}
