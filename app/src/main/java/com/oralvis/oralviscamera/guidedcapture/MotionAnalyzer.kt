package com.oralvis.oralviscamera.guidedcapture

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * INTERNAL: MotionAnalyzer performs dense optical flow motion analysis on camera preview frames.
 * Do NOT import directly - use GuidedController from feature.guided.
 *
 * This class:
 * - Receives NV21 frames from the camera pipeline
 * - Samples frames at ~8-10 FPS (drops frames in between)
 * - Computes motion metrics using OpenCV Farneback dense optical flow
 * - Only processes during SCANNING_LOWER or SCANNING_UPPER states
 * - Emits MotionState with motionScore (average pixel movement magnitude)
 * - Runs on a background thread (HandlerThread)
 *
 * Design:
 * - Uses Farneback dense optical flow for robust, pixel-level motion detection
 * - Resizes frames to 320px width for performance (matching Windows app)
 * - Reuses Mat objects to avoid allocation overhead
 * - Applies exponential smoothing: motionScore = 0.8 * prevScore + 0.2 * rawScore
 * - Uses hysteresis logic for stable warning signals
 */
class MotionAnalyzer {

    companion object {
        private const val TAG = "MotionAnalyzer"
        
        // Frame sampling: target 8-10 FPS
        private const val SAMPLE_INTERVAL_MS = 100L // 10 FPS
        
        // Motion analysis parameters (matching Windows app)
        private const val MOTION_TARGET_WIDTH = 320
        
        // Smoothing factor for motionScore
        private const val SMOOTHING_ALPHA = 0.8 // 0.8 * prevScore + 0.2 * rawScore
        
        // Noise filtering: ignore very small motion values (likely noise when camera is still)
        private const val NOISE_THRESHOLD = 0.1 // Motion scores below this are treated as noise/zero
        
        // Hysteresis thresholds (matching Windows app)
        private const val STABILITY_ENTER_THRESH = 3.0
        private const val STABILITY_CLEAR_THRESH = 2.0
        private const val SPEED_ENTER_THRESH = 8.0
        private const val SPEED_CLEAR_THRESH = 6.0
        private const val HYSTERESIS_K_CONFIRM = 5
        
        // Frame initialization: skip first few frames which may have artifacts
        private const val INITIALIZATION_FRAMES = 3
    }

    var onMotionStateUpdated: ((MotionState) -> Unit)? = null

    private val isRunning = AtomicBoolean(false)
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    
    // Frame sampling
    private var lastSampleTimeMs: Long = 0
    
    // OpenCV Mat objects (reused to avoid allocation)
    private var prevGrayMat: Mat? = null
    private var currGrayMat: Mat? = null
    private var resizedMat: Mat? = null
    private var flowMat: Mat? = null // For Farneback optical flow
    
    // Motion score smoothing
    private var smoothedMotionScore: Double? = null
    
    // Frame counter for initialization skip
    private var frameCount: Int = 0
    
    // Hysteresis instances (both fed by motionScore)
    private val stabilityHysteresis = HysteresisState(
        enterThreshold = STABILITY_ENTER_THRESH,
        clearThreshold = STABILITY_CLEAR_THRESH,
        kConfirm = HYSTERESIS_K_CONFIRM
    )
    private val speedHysteresis = HysteresisState(
        enterThreshold = SPEED_ENTER_THRESH,
        clearThreshold = SPEED_CLEAR_THRESH,
        kConfirm = HYSTERESIS_K_CONFIRM
    )
    
    // Current scanning state (set externally)
    @Volatile
    var scanningState: ScanningState = ScanningState.READY_TO_SCAN_LOWER
        set(value) {
            val oldValue = field
            field = value
            Log.d(TAG, "scanningState changed: $oldValue -> $value")
            
            // Only clear previous frame when transitioning OUT of scanning states
            // Don't clear when transitioning between scanning states (SCANNING_LOWER <-> SCANNING_UPPER)
            val wasScanning = oldValue == ScanningState.SCANNING_LOWER || oldValue == ScanningState.SCANNING_UPPER
            val isScanning = value == ScanningState.SCANNING_LOWER || value == ScanningState.SCANNING_UPPER
            
            if (wasScanning && !isScanning) {
                // Transitioning from scanning to non-scanning: clear state
                Log.d(TAG, "Transitioning from scanning ($oldValue) to non-scanning ($value), clearing previous frame")
                clearPreviousFrame()
            } else if (!wasScanning && isScanning) {
                // Transitioning from non-scanning to scanning: keep state if possible, but reset frame counter for initialization
                Log.d(TAG, "Transitioning from non-scanning ($oldValue) to scanning ($value), resetting frame counter")
                workerHandler?.post {
                    frameCount = 0 // Reset frame counter to trigger initialization frames
                    // Don't clear prevGrayMat - keep it if available for smoother transition
                }
            } else if (!isScanning) {
                // Already in non-scanning state, clear if needed
                clearPreviousFrame()
            }
            // If transitioning between scanning states (SCANNING_LOWER <-> SCANNING_UPPER), don't clear
        }

    init {
        // Initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed")
        } else {
            Log.d(TAG, "OpenCV initialized successfully")
        }
    }

    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "MotionAnalyzer already started")
            return
        }
        
        workerThread = HandlerThread("MotionAnalyzer").apply {
            start()
            workerHandler = Handler(looper)
        }
        
        Log.d(TAG, "MotionAnalyzer started")
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }
        
        workerHandler?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        
        // Clean up OpenCV Mats
        releaseMats()
        
        // Clear state
        smoothedMotionScore = null
        lastSampleTimeMs = 0
        
        Log.d(TAG, "MotionAnalyzer stopped")
    }

    /**
     * Process an NV21 frame from the camera.
     * This method should be called from the camera preview callback.
     * Frame sampling (8-10 FPS) happens here.
     */
    fun onFrame(data: ByteArray, width: Int, height: Int) {
        if (!isRunning.get()) {
            Log.d(TAG, "onFrame: MotionAnalyzer not running")
            return
        }
        
        // Gate by scanning state
        if (scanningState != ScanningState.SCANNING_LOWER && 
            scanningState != ScanningState.SCANNING_UPPER) {
            // Only log occasionally to avoid spam, but log more frequently for upper scan debugging
            val now = System.currentTimeMillis()
            if (now % 2000 < 100) {
                Log.d(TAG, "onFrame: Not in scanning state (current: $scanningState)")
            }
            return
        }
        
        // Frame sampling: only process every SAMPLE_INTERVAL_MS
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSampleTimeMs < SAMPLE_INTERVAL_MS) {
            return
        }
        lastSampleTimeMs = nowMs
        
        // Enhanced logging for upper scan debugging
        if (scanningState == ScanningState.SCANNING_UPPER) {
            Log.d(TAG, "onFrame: Processing UPPER scan frame ${width}x${height}, state=$scanningState")
        } else {
            Log.d(TAG, "onFrame: Processing frame ${width}x${height}, state=$scanningState")
        }
        
        // Process on background thread
        workerHandler?.post {
            processFrame(data, width, height)
        }
    }

    /**
     * Process a single frame using Farneback dense optical flow.
     * Runs on background thread.
     */
    private fun processFrame(data: ByteArray, width: Int, height: Int) {
        try {
            // NV21 format: first width*height bytes are Y (luma) channel
            // Extract Y channel directly for grayscale (more efficient than conversion)
            val ySize = width * height
            val yData = ByteArray(ySize)
            System.arraycopy(data, 0, yData, 0, ySize)
            
            // Create Mat from Y channel
            val grayMat = Mat(height, width, CvType.CV_8UC1)
            grayMat.put(0, 0, yData)
            
            // Resize to target width (maintaining aspect ratio)
            val scale = MOTION_TARGET_WIDTH.toDouble() / width
            val targetHeight = (height * scale).toInt()
            val targetSize = Size(MOTION_TARGET_WIDTH.toDouble(), targetHeight.toDouble())
            if (resizedMat == null) {
                resizedMat = Mat()
            }
            Imgproc.resize(grayMat, resizedMat!!, targetSize)
            grayMat.release()
            
            // Increment frame counter
            frameCount++
            
            // Compute motion using Farneback optical flow if we have a previous frame
            val rawMotionScore = if (prevGrayMat != null && frameCount > INITIALIZATION_FRAMES) {
                val computed = computeMotionFarneback(resizedMat!!)
                // Apply noise filtering: treat very small values as zero (noise when camera is still)
                if (computed < NOISE_THRESHOLD) {
                    0.0
                } else {
                    computed
                }
            } else {
                // First few frames or no previous frame: no motion
                if (frameCount <= INITIALIZATION_FRAMES) {
                    Log.d(TAG, "Skipping motion computation for initialization frame $frameCount/$INITIALIZATION_FRAMES")
                }
                0.0
            }
            
            // Apply exponential smoothing
            val motionScore = if (smoothedMotionScore == null) {
                rawMotionScore
            } else {
                SMOOTHING_ALPHA * smoothedMotionScore!! + (1.0 - SMOOTHING_ALPHA) * rawMotionScore
            }
            smoothedMotionScore = motionScore
            
            // Update previous frame
            if (prevGrayMat == null) {
                prevGrayMat = Mat()
            }
            resizedMat!!.copyTo(prevGrayMat!!)
            
            // Update hysteresis states (both fed by the same motionScore)
            val speedWarning = speedHysteresis.update(motionScore)
            val stabilityWarning = stabilityHysteresis.update(motionScore)
            
            // Emit MotionState
            val motionState = MotionState(
                motionScore = motionScore,
                speedWarning = speedWarning,
                stabilityWarning = stabilityWarning
            )
            
            // Enhanced logging for debugging auto-capture issues
            val isStable = motionScore < 2.0 // Match AutoCaptureController threshold
            if (scanningState == ScanningState.SCANNING_UPPER) {
                // More verbose logging for upper scan debugging
                Log.d(TAG, "Motion [UPPER]: raw=$rawMotionScore, smoothed=$motionScore, stable=$isStable, speedWarn=$speedWarning, stabilityWarn=$stabilityWarning, frameCount=$frameCount")
            } else {
                Log.d(TAG, "Motion: raw=$rawMotionScore, smoothed=$motionScore, stable=$isStable, speedWarn=$speedWarning, stabilityWarn=$stabilityWarning, state=$scanningState")
            }
            
            // Log when motionScore is above threshold to help diagnose why capture isn't triggering
            if (!isStable && motionScore > 2.0) {
                if (scanningState == ScanningState.SCANNING_UPPER) {
                    Log.w(TAG, "MotionScore [UPPER] above threshold: $motionScore > 2.0 (capture will not trigger)")
                } else {
                    Log.w(TAG, "MotionScore above threshold: $motionScore > 2.0 (capture will not trigger)")
                }
            }
            
            // Emit on main thread to avoid threading issues
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onMotionStateUpdated?.invoke(motionState)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        }
    }

    /**
     * Compute motion score using Farneback dense optical flow.
     * Returns the average pixel movement magnitude across the frame.
     */
    private fun computeMotionFarneback(currentGray: Mat): Double {
        try {
            // Ensure flowMat is initialized with correct size and type (CV_32FC2 for 2-channel float)
            val rows = currentGray.rows()
            val cols = currentGray.cols()
            
            if (flowMat == null || flowMat!!.rows() != rows || flowMat!!.cols() != cols || flowMat!!.type() != CvType.CV_32FC2) {
                flowMat?.release()
                flowMat = Mat(rows, cols, CvType.CV_32FC2)
                Log.d(TAG, "Initialized flowMat: ${rows}x${cols}, type=CV_32FC2")
            }
            
            // Verify input Mats are valid
            if (prevGrayMat == null || prevGrayMat!!.empty()) {
                Log.w(TAG, "computeMotionFarneback: prevGrayMat is null or empty")
                return 0.0
            }
            
            if (currentGray.empty()) {
                Log.w(TAG, "computeMotionFarneback: currentGray is empty")
                return 0.0
            }
            
            // Verify sizes match
            if (prevGrayMat!!.rows() != rows || prevGrayMat!!.cols() != cols) {
                Log.w(TAG, "computeMotionFarneback: Size mismatch - prev: ${prevGrayMat!!.rows()}x${prevGrayMat!!.cols()}, curr: ${rows}x${cols}")
                return 0.0
            }
            
            // Compute Farneback dense optical flow
            // Parameters matching typical usage: pyramid levels, window size, iterations, etc.
            Video.calcOpticalFlowFarneback(
                prevGrayMat!!,
                currentGray,
                flowMat!!,
                0.5,      // pyr_scale: pyramid scale factor
                3,        // levels: number of pyramid levels
                15,       // winsize: averaging window size
                3,        // iterations: number of iterations at each pyramid level
                5,        // poly_n: size of pixel neighborhood for polynomial expansion
                1.2,      // poly_sigma: standard deviation for Gaussian smoothing
                0         // flags: operation flags
            )
            
            // Compute magnitude of flow vectors: sqrt(flow_x² + flow_y²)
            // Flow Mat has 2 channels: flow_x and flow_y
            val flowChannels = mutableListOf<Mat>()
            Core.split(flowMat!!, flowChannels)
            
            if (flowChannels.size < 2) {
                Log.e(TAG, "computeMotionFarneback: Failed to split flow channels, got ${flowChannels.size} channels")
                flowChannels.forEach { it.release() }
                return 0.0
            }
            
            val flowX = flowChannels[0]
            val flowY = flowChannels[1]
            
            // Compute magnitude: sqrt(x² + y²)
            val magnitudeMat = Mat()
            Core.magnitude(flowX, flowY, magnitudeMat)
            
            // Compute mean magnitude across entire frame
            val meanScalar = Core.mean(magnitudeMat)
            val motionScore = meanScalar.`val`[0]
            
            // Release temporary Mats
            flowChannels.forEach { it.release() }
            magnitudeMat.release()
            
            // Log detailed motion score for debugging
            if (motionScore > 5.0 || motionScore < 0.1) {
                Log.d(TAG, "computeMotionFarneback: motionScore=$motionScore (unusual value)")
            }
            
            return motionScore
        } catch (e: Exception) {
            Log.e(TAG, "Error in computeMotionFarneback: ${e.message}", e)
            return 0.0
        }
    }

    /**
     * Clear previous frame when not scanning.
     */
    private fun clearPreviousFrame() {
        workerHandler?.post {
            prevGrayMat?.release()
            prevGrayMat = null
            smoothedMotionScore = null
            frameCount = 0 // Reset frame counter
            // Reset hysteresis states immediately
            stabilityHysteresis.reset()
            speedHysteresis.reset()
            Log.d(TAG, "Cleared previous frame (not scanning)")
        }
    }

    /**
     * Release all OpenCV Mat objects.
     */
    private fun releaseMats() {
        prevGrayMat?.release()
        currGrayMat?.release()
        resizedMat?.release()
        flowMat?.release()
        
        prevGrayMat = null
        currGrayMat = null
        resizedMat = null
        flowMat = null
    }
}
