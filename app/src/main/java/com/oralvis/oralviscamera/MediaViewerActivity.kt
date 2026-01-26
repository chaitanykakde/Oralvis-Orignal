package com.oralvis.oralviscamera

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.oralvis.annotation.AnnotationModule
import com.oralvis.oralviscamera.databinding.ActivityMediaViewerBinding
import java.io.File

/**
 * Full-screen media viewer for images and videos.
 * 
 * Features:
 * - Full-screen immersive display
 * - Auto-hide controls after 3 seconds
 * - Annotate button for images (launches AnnotationActivity)
 */
class MediaViewerActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMediaViewerBinding
    private var mediaPath: String = ""
    private var mediaType: String = ""
    private var sessionId: String? = null
    private var filename: String = ""
    
    // For auto-hide controls
    private val hideHandler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private val hideRunnable = Runnable { hideControls() }
    
    companion object {
        const val EXTRA_MEDIA_PATH = "media_path"
        const val EXTRA_MEDIA_TYPE = "media_type"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_FILENAME = "filename"
        
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make full screen
        setupFullScreen()
        
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Get media info from intent - support multiple extra names for compatibility
        mediaPath = intent.getStringExtra(EXTRA_MEDIA_PATH) 
            ?: intent.getStringExtra("MEDIA_PATH") 
            ?: intent.getStringExtra("media_path") ?: ""
            
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) 
            ?: intent.getStringExtra("media_type") ?: ""
            
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
            ?: intent.getStringExtra("session_id")
        
        filename = intent.getStringExtra(EXTRA_FILENAME)
            ?: intent.getStringExtra("filename") ?: ""
        
        // Handle legacy intent extra names
        if (mediaType.isEmpty()) {
            val isVideo = intent.getBooleanExtra("IS_VIDEO", false) 
                || intent.getBooleanExtra("is_video", false)
            mediaType = if (isVideo) "video" else "image"
        }
        
        setupUI()
        loadMedia()
    }
    
    private fun setupFullScreen() {
        // Use modern WindowInsetsController for immersive mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    
    private fun hideSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = 
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }
    
    private fun showSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
    }
    
    private fun setupUI() {
        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }
        
        // Annotate button
        binding.btnAnnotate.setOnClickListener {
            openAnnotationMode()
        }
        
        // Tap anywhere to toggle controls
        binding.main.setOnClickListener {
            toggleControls()
        }
        
        binding.imageView.setOnClickListener {
            toggleControls()
        }
        
        // Schedule auto-hide
        scheduleAutoHide()
    }
    
    private fun loadMedia() {
        if (mediaPath.isEmpty()) {
            Toast.makeText(this, "No media file specified", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        val file = File(mediaPath)
        if (!file.exists()) {
            Toast.makeText(this, "Media file not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Set filename if not provided
        if (filename.isEmpty()) {
            filename = file.name
        }
        
        // Display filename
        binding.txtFilename.text = filename
        binding.txtFilename.visibility = View.VISIBLE
        
        // Determine media type from extension if not specified
        val effectiveMediaType = if (mediaType.isNotEmpty()) {
            mediaType.lowercase()
        } else {
            when (file.extension.lowercase()) {
                "jpg", "jpeg", "png", "bmp", "gif", "webp" -> "image"
                "mp4", "avi", "mov", "mkv", "3gp" -> "video"
                else -> "image" // Default to image
            }
        }
        
        when (effectiveMediaType) {
            "image" -> showImage(file)
            "video" -> showVideo(file)
            else -> showImage(file)
        }
    }
    
    private fun showImage(file: File) {
        binding.imageView.visibility = View.VISIBLE
        binding.videoView.visibility = View.GONE
        
        // Show annotate button for images
        binding.btnAnnotate.visibility = View.VISIBLE
        
        try {
            // Load image efficiently
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            
            // Calculate sample size for large images
            val maxSize = 2048
            var sampleSize = 1
            if (options.outWidth > maxSize || options.outHeight > maxSize) {
                sampleSize = maxOf(
                    options.outWidth / maxSize,
                    options.outHeight / maxSize
                )
            }
            
            // Load the bitmap with appropriate sample size
            val loadOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, loadOptions)
            
            if (bitmap != null) {
                binding.imageView.setImageBitmap(bitmap)
                binding.imageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            } else {
                // Fallback to URI loading
                val uri = Uri.fromFile(file)
                binding.imageView.setImageURI(uri)
            }
            
        } catch (e: Exception) {
            android.util.Log.e("MediaViewer", "Failed to load image: ${e.message}", e)
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun showVideo(file: File) {
        binding.imageView.visibility = View.GONE
        binding.videoView.visibility = View.VISIBLE
        
        // Hide annotate button for videos (annotation is only for images)
        binding.btnAnnotate.visibility = View.GONE
        
        try {
            val uri = Uri.fromFile(file)
            binding.videoView.setVideoURI(uri)
            
            // Setup media controller
            val mediaController = MediaController(this)
            mediaController.setAnchorView(binding.videoView)
            binding.videoView.setMediaController(mediaController)
            
            binding.videoView.setOnPreparedListener {
                binding.videoView.start()
            }
            
            binding.videoView.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "Video playback error", Toast.LENGTH_SHORT).show()
                true
            }
            
        } catch (e: Exception) {
            android.util.Log.e("MediaViewer", "Failed to load video: ${e.message}", e)
            Toast.makeText(this, "Failed to load video", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    /**
     * Launch the annotation activity for the current image.
     */
    private fun openAnnotationMode() {
        if (mediaPath.isEmpty()) {
            Toast.makeText(this, "No image to annotate", Toast.LENGTH_SHORT).show()
            return
        }
        
        val file = File(mediaPath)
        if (!file.exists()) {
            Toast.makeText(this, "Image file not found", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Cancel auto-hide when opening annotation
        hideHandler.removeCallbacks(hideRunnable)
        
        // Launch annotation activity
        AnnotationModule.launchAnnotation(
            context = this,
            imagePath = mediaPath,
            imageFilename = filename.ifEmpty { file.name },
            sessionId = sessionId
        )
    }
    
    // ============= Controls Visibility =============
    
    private fun toggleControls() {
        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }
    
    private fun showControls() {
        controlsVisible = true
        binding.controlsOverlay.animate()
            .alpha(1f)
            .setDuration(200)
            .withStartAction {
                binding.controlsOverlay.visibility = View.VISIBLE
            }
            .start()
        
        showSystemBars()
        scheduleAutoHide()
    }
    
    private fun hideControls() {
        controlsVisible = false
        binding.controlsOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                binding.controlsOverlay.visibility = View.INVISIBLE
            }
            .start()
        
        hideSystemBars()
    }
    
    private fun scheduleAutoHide() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, AUTO_HIDE_DELAY_MILLIS)
    }
    
    override fun onResume() {
        super.onResume()
        showControls()
    }
    
    override fun onPause() {
        super.onPause()
        hideHandler.removeCallbacks(hideRunnable)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacks(hideRunnable)
        try {
            binding.videoView.stopPlayback()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
