package com.oralvis.oralviscamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.oralvis.annotation.AnnotationModule
import com.oralvis.oralviscamera.databinding.ActivityMediaViewerBinding
import java.io.File

class MediaViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaViewerBinding
    private var mediaPath: String = ""
    private var mediaType: String = ""
    private var sessionId: String? = null
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var scaleFactor = 1.0f
    private var matrix: Matrix = Matrix()

    companion object {
        const val EXTRA_MEDIA_PATH = "media_path"
        const val EXTRA_MEDIA_TYPE = "media_type"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_FILENAME = "filename"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        // Get media info from intent
        mediaPath = intent.getStringExtra(EXTRA_MEDIA_PATH) 
            ?: intent.getStringExtra("MEDIA_PATH") ?: ""
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: ""
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        
        // Handle legacy intent extra names
        if (mediaType.isEmpty()) {
            val isVideo = intent.getBooleanExtra("IS_VIDEO", false)
            mediaType = if (isVideo) "video" else "image"
        }
        
        setupUI()
        loadMedia()
    }
    
    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }
        
        // Setup annotate button
        binding.btnAnnotate.setOnClickListener {
            openAnnotationMode()
        }
        
        // Setup scale gesture detector for image zoom
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(0.1f, 10.0f)
                
                matrix.setScale(scaleFactor, scaleFactor)
                binding.imageView.imageMatrix = matrix
                return true
            }
        })
        
        // Hide system UI for full screen experience
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
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
        
        when (mediaType.lowercase()) {
            "image" -> showImage(file)
            "video" -> showVideo(file)
            else -> {
                // Try to determine type from file extension
                val extension = file.extension.lowercase()
                when (extension) {
                    "jpg", "jpeg", "png", "bmp", "gif" -> showImage(file)
                    "mp4", "avi", "mov", "mkv" -> showVideo(file)
                    else -> {
                        Toast.makeText(this, "Unsupported media type", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
        }
    }
    
    private fun showImage(file: File) {
        binding.imageView.visibility = View.VISIBLE
        binding.videoView.visibility = View.GONE
        
        // Show annotate button for images
        binding.btnAnnotate.visibility = View.VISIBLE
        
        try {
            // Load image with better quality
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                binding.imageView.setImageBitmap(bitmap)
                binding.imageView.scaleType = android.widget.ImageView.ScaleType.MATRIX
                
                // Enable zoom and pan for image
                binding.imageView.setOnTouchListener { _, event ->
                    scaleGestureDetector?.onTouchEvent(event) ?: false
                }
                
                binding.imageView.setOnClickListener {
                    toggleSystemUI()
                }
            } else {
                // Fallback to URI loading
                val uri = Uri.fromFile(file)
                binding.imageView.setImageURI(uri)
                binding.imageView.setOnClickListener {
                    toggleSystemUI()
                }
            }
            
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load image: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun showVideo(file: File) {
        binding.imageView.visibility = View.GONE
        binding.videoView.visibility = View.VISIBLE
        
        // Hide annotate button for videos
        binding.btnAnnotate.visibility = View.GONE
        
        try {
            val uri = Uri.fromFile(file)
            binding.videoView.setVideoURI(uri)
            
            // Setup media controller for video controls
            val mediaController = MediaController(this)
            mediaController.setAnchorView(binding.videoView)
            binding.videoView.setMediaController(mediaController)
            
            // Auto-start video playback
            binding.videoView.setOnPreparedListener { mediaPlayer ->
                binding.videoView.start()
            }
            
            // Handle video completion
            binding.videoView.setOnCompletionListener {
                // Video finished playing
            }
            
            // Handle video errors
            binding.videoView.setOnErrorListener { _, what, extra ->
                Toast.makeText(this, "Video playback error", Toast.LENGTH_SHORT).show()
                true
            }
            
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load video: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    /**
     * Open annotation mode for the current image.
     * Launches the AnnotationActivity from the annotation module.
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
        
        // Get filename from intent or extract from path
        val filename = intent.getStringExtra(EXTRA_FILENAME) ?: file.name
        
        // Launch annotation activity using the AnnotationModule API
        AnnotationModule.launchAnnotation(
            context = this,
            imagePath = mediaPath,
            imageFilename = filename,
            sessionId = sessionId
        )
    }
    
    private fun toggleSystemUI() {
        val currentFlags = window.decorView.systemUiVisibility
        val newFlags = if (currentFlags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0) {
            // Show system UI
            View.SYSTEM_UI_FLAG_VISIBLE
        } else {
            // Hide system UI
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        window.decorView.systemUiVisibility = newFlags
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return scaleGestureDetector?.onTouchEvent(event) ?: super.onTouchEvent(event)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        binding.videoView.stopPlayback()
    }
}
