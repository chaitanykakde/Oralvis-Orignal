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
import com.oralvis.oralviscamera.databinding.ActivityMediaViewerBinding
import java.io.File

class MediaViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaViewerBinding
    private var mediaPath: String = ""
    private var mediaType: String = ""
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var scaleFactor = 1.0f
    private var matrix: Matrix = Matrix()

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
        mediaPath = intent.getStringExtra("media_path") ?: ""
        mediaType = intent.getStringExtra("media_type") ?: ""
        
        setupUI()
        loadMedia()
    }
    
    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
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
