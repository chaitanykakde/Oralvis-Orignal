package com.oralvis.oralviscamera.gallery

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.oralvis.oralviscamera.R
import com.oralvis.oralviscamera.database.MediaRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MediaPreviewDialog(private val context: Context) {
    
    private var dialog: AlertDialog? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoHandler: Handler? = null
    private var videoRunnable: Runnable? = null
    private var isVideoPlaying = false
    
    fun show(mediaRecord: MediaRecord) {
        android.util.Log.d("MediaPreviewDialog", "Showing preview for: ${mediaRecord.fileName}")
        
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_media_preview, null)
        
        // Initialize views
        val imagePreview = dialogView.findViewById<ImageView>(R.id.imagePreview)
        val videoPreview = dialogView.findViewById<SurfaceView>(R.id.videoPreview)
        val videoControls = dialogView.findViewById<View>(R.id.videoControls)
        val btnClose = dialogView.findViewById<ImageView>(R.id.btnClose)
        val titleText = dialogView.findViewById<TextView>(R.id.titleText)
        val mediaInfoText = dialogView.findViewById<TextView>(R.id.mediaInfoText)
        val loadingIndicator = dialogView.findViewById<View>(R.id.loadingIndicator)
        val errorText = dialogView.findViewById<TextView>(R.id.errorText)
        val btnPlayPause = dialogView.findViewById<ImageView>(R.id.btnPlayPause)
        val videoSeekBar = dialogView.findViewById<SeekBar>(R.id.videoSeekBar)
        val videoTimeText = dialogView.findViewById<TextView>(R.id.videoTimeText)
        
        // Set title and info
        titleText.text = mediaRecord.fileName
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        mediaInfoText.text = "${mediaRecord.mode} • ${dateFormat.format(mediaRecord.captureTime)}"
        
        // Close button
        btnClose.setOnClickListener {
            dismiss()
        }
        
        // Check if file exists
        val file = File(mediaRecord.filePath)
        if (!file.exists()) {
            android.util.Log.e("MediaPreviewDialog", "File does not exist: ${mediaRecord.filePath}")
            showError(loadingIndicator, errorText, "File not found")
            return
        }
        
        // Show appropriate preview based on media type
        when (mediaRecord.mediaType) {
            "Image" -> {
                android.util.Log.d("MediaPreviewDialog", "Loading image preview")
                loadImagePreview(imagePreview, videoPreview, videoControls, loadingIndicator, errorText, mediaRecord.filePath)
            }
            "Video" -> {
                android.util.Log.d("MediaPreviewDialog", "Loading video preview")
                loadVideoPreview(imagePreview, videoPreview, videoControls, loadingIndicator, errorText, 
                    btnPlayPause, videoSeekBar, videoTimeText, mediaRecord.filePath)
            }
            else -> {
                android.util.Log.e("MediaPreviewDialog", "Unknown media type: ${mediaRecord.mediaType}")
                showError(loadingIndicator, errorText, "Unknown media type")
            }
        }
        
        // Create and show dialog
        dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        // Make dialog full-screen
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        dialog?.show()
    }
    
    private fun loadImagePreview(
        imagePreview: ImageView,
        videoPreview: SurfaceView,
        videoControls: View,
        loadingIndicator: View,
        errorText: TextView,
        filePath: String
    ) {
        try {
            android.util.Log.d("MediaPreviewDialog", "Loading image from: $filePath")
            
            // Hide video elements
            videoPreview.visibility = View.GONE
            videoControls.visibility = View.GONE
            
            // Load image
            val bitmap = BitmapFactory.decodeFile(filePath)
            if (bitmap != null) {
                android.util.Log.d("MediaPreviewDialog", "Image loaded successfully: ${bitmap.width}x${bitmap.height}")
                imagePreview.setImageBitmap(bitmap)
                imagePreview.visibility = View.VISIBLE
                loadingIndicator.visibility = View.GONE
            } else {
                android.util.Log.e("MediaPreviewDialog", "Failed to decode image")
                showError(loadingIndicator, errorText, "Failed to load image")
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaPreviewDialog", "Error loading image: ${e.message}")
            showError(loadingIndicator, errorText, "Error loading image: ${e.message}")
        }
    }
    
    private fun loadVideoPreview(
        imagePreview: ImageView,
        videoPreview: SurfaceView,
        videoControls: View,
        loadingIndicator: View,
        errorText: TextView,
        btnPlayPause: ImageView,
        videoSeekBar: SeekBar,
        videoTimeText: TextView,
        filePath: String
    ) {
        try {
            android.util.Log.d("MediaPreviewDialog", "Loading video from: $filePath")
            
            // Hide image elements
            imagePreview.visibility = View.GONE
            
            // Show video elements
            videoPreview.visibility = View.VISIBLE
            videoControls.visibility = View.VISIBLE
            
            // Setup video surface
            videoPreview.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    android.util.Log.d("MediaPreviewDialog", "Surface created")
                    setupVideoPlayer(filePath, holder, btnPlayPause, videoSeekBar, videoTimeText)
                }
                
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    android.util.Log.d("MediaPreviewDialog", "Surface changed: ${width}x${height}")
                }
                
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    android.util.Log.d("MediaPreviewDialog", "Surface destroyed")
                    mediaPlayer?.release()
                    mediaPlayer = null
                }
            })
            
            // Hide loading indicator
            loadingIndicator.visibility = View.GONE
            
            android.util.Log.d("MediaPreviewDialog", "Video preview setup complete")
            
        } catch (e: Exception) {
            android.util.Log.e("MediaPreviewDialog", "Error loading video: ${e.message}")
            showError(loadingIndicator, errorText, "Error loading video: ${e.message}")
        }
    }
    
    private fun setupVideoPlayer(
        filePath: String,
        surfaceHolder: SurfaceHolder,
        btnPlayPause: ImageView,
        videoSeekBar: SeekBar,
        videoTimeText: TextView
    ) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                setDisplay(surfaceHolder)
                setOnPreparedListener { player ->
                    android.util.Log.d("MediaPreviewDialog", "Video prepared, duration: ${player.duration}")
                    videoSeekBar.max = player.duration
                    updateVideoTime(videoTimeText, 0, player.duration)
                }
                setOnCompletionListener {
                    android.util.Log.d("MediaPreviewDialog", "Video completed")
                    isVideoPlaying = false
                    btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
                    videoSeekBar.progress = 0
                    updateVideoTime(videoTimeText, 0, mediaPlayer?.duration ?: 0)
                }
                prepareAsync()
            }
            
            // Setup video controls
            setupVideoControls(btnPlayPause, videoSeekBar, videoTimeText)
            
        } catch (e: Exception) {
            android.util.Log.e("MediaPreviewDialog", "Error setting up video player: ${e.message}")
        }
    }
    
    private fun setupVideoControls(
        btnPlayPause: ImageView,
        videoSeekBar: SeekBar,
        videoTimeText: TextView
    ) {
        // Play/Pause button
        btnPlayPause.setOnClickListener {
            if (isVideoPlaying) {
                pauseVideo(btnPlayPause)
            } else {
                playVideo(btnPlayPause)
            }
        }
        
        // Seek bar listener
        videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer?.seekTo(progress)
                    updateVideoTime(videoTimeText, progress, mediaPlayer?.duration ?: 0)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Start video update handler
        startVideoUpdateHandler(videoSeekBar, videoTimeText)
    }
    
    private fun playVideo(btnPlayPause: ImageView) {
        try {
            android.util.Log.d("MediaPreviewDialog", "Starting video playback")
            mediaPlayer?.start()
            isVideoPlaying = true
            btnPlayPause.setImageResource(R.drawable.ic_pause)
        } catch (e: Exception) {
            android.util.Log.e("MediaPreviewDialog", "Error starting video: ${e.message}")
        }
    }
    
    private fun pauseVideo(btnPlayPause: ImageView) {
        try {
            android.util.Log.d("MediaPreviewDialog", "Pausing video playback")
            mediaPlayer?.pause()
            isVideoPlaying = false
            btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
        } catch (e: Exception) {
            android.util.Log.e("MediaPreviewDialog", "Error pausing video: ${e.message}")
        }
    }
    
    private fun startVideoUpdateHandler(videoSeekBar: SeekBar, videoTimeText: TextView) {
        videoHandler = Handler(Looper.getMainLooper())
        videoRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let { player ->
                    if (isVideoPlaying) {
                        val currentPosition = player.currentPosition
                        videoSeekBar.progress = currentPosition
                        updateVideoTime(videoTimeText, currentPosition, player.duration)
                    }
                }
                videoHandler?.postDelayed(this, 1000) // Update every second
            }
        }
        videoHandler?.post(videoRunnable!!)
    }
    
    private fun updateVideoTime(videoTimeText: TextView, currentPosition: Int, duration: Int) {
        val currentTime = formatTime(currentPosition)
        val totalTime = formatTime(duration)
        videoTimeText.text = "$currentTime / $totalTime"
    }
    
    private fun formatTime(milliseconds: Int): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%02d:%02d", minutes, remainingSeconds)
    }
    
    private fun showError(loadingIndicator: View, errorText: TextView, message: String) {
        loadingIndicator.visibility = View.GONE
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }
    
    fun dismiss() {
        android.util.Log.d("MediaPreviewDialog", "Dismissing preview dialog")
        
        // Stop video playback
        if (isVideoPlaying) {
            mediaPlayer?.stop()
            isVideoPlaying = false
        }
        
        // Clean up video handler
        videoHandler?.removeCallbacks(videoRunnable!!)
        videoHandler = null
        videoRunnable = null
        
        // Release media player
        mediaPlayer?.release()
        mediaPlayer = null
        
        // Dismiss dialog
        dialog?.dismiss()
        dialog = null
    }
}
