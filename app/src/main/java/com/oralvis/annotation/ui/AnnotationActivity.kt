package com.oralvis.annotation.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.oralvis.annotation.export.AnnotatedImageExporter
import com.oralvis.annotation.export.AnnotationExporter
import com.oralvis.annotation.model.AnnotationBox
import com.oralvis.annotation.model.ImageAnnotation
import com.oralvis.annotation.model.AnnotationSession
import com.oralvis.annotation.overlay.AnnotationOverlayView
import com.oralvis.annotation.util.AnnotationLogger
import com.oralvis.oralviscamera.R
import com.oralvis.oralviscamera.databinding.ActivityAnnotationBinding
import java.io.File
import java.util.UUID

/**
 * Production-grade annotation activity for adding bounding boxes to images.
 * 
 * Features:
 * - VIEW MODE: Tap existing annotations to edit/delete
 * - ANNOTATE MODE: Draw new bounding boxes
 * - Coordinate mapping from screen to image pixels
 * - JSON export AND annotated image export
 */
class AnnotationActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_IMAGE_PATH = "IMAGE_PATH"
        const val EXTRA_IMAGE_FILENAME = "IMAGE_FILENAME"
        const val EXTRA_SESSION_ID = "SESSION_ID"
        
        fun createIntent(
            context: Context,
            imagePath: String,
            imageFilename: String,
            sessionId: String? = null
        ): Intent {
            return Intent(context, AnnotationActivity::class.java).apply {
                putExtra(EXTRA_IMAGE_PATH, imagePath)
                putExtra(EXTRA_IMAGE_FILENAME, imageFilename)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
        }
    }
    
    private lateinit var binding: ActivityAnnotationBinding
    
    // Image data
    private var imagePath: String = ""
    private var imageFilename: String = ""
    private var sessionId: String? = null
    private var bitmap: Bitmap? = null
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    
    // Annotation data
    private lateinit var imageAnnotation: ImageAnnotation
    
    // Mode
    private var currentMode: AnnotationOverlayView.Mode = AnnotationOverlayView.Mode.VIEW
    
    // Pending annotation awaiting label
    private var pendingBoundingBox: RectF? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityAnnotationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Get intent extras
        imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: ""
        imageFilename = intent.getStringExtra(EXTRA_IMAGE_FILENAME) ?: ""
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        
        AnnotationLogger.logActivityCreated("AnnotationActivity", imagePath)
        
        if (imagePath.isEmpty()) {
            Toast.makeText(this, "No image specified", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        setupUI()
        loadImage()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        AnnotationLogger.logActivityDestroyed("AnnotationActivity")
        bitmap?.recycle()
        bitmap = null
    }
    
    private fun setupUI() {
        // Back button
        binding.btnBack.setOnClickListener {
            handleBackPress()
        }
        
        // Mode toggle
        binding.btnModeToggle.setOnClickListener {
            toggleMode()
        }
        
        // Save button
        binding.btnSave.setOnClickListener {
            showSaveOptions()
        }
        
        // Clear button
        binding.btnClear.setOnClickListener {
            clearAllAnnotations()
        }
        
        // Filename display
        binding.txtImageInfo.text = imageFilename
    }
    
    private fun loadImage() {
        val file = File(imagePath)
        if (!file.exists()) {
            Toast.makeText(this, "Image file not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        binding.loadingIndicator.visibility = View.VISIBLE
        
        try {
            // Load bitmap
            bitmap = BitmapFactory.decodeFile(imagePath)
            
            if (bitmap == null) {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            
            imageWidth = bitmap!!.width
            imageHeight = bitmap!!.height
            
            AnnotationLogger.d("Image loaded: ${imageWidth}x${imageHeight}")
            
            // Initialize annotation data
            imageAnnotation = ImageAnnotation.empty(
                filename = imageFilename,
                width = imageWidth,
                height = imageHeight
            )
            
            // Display image with FIT_CENTER
            binding.imageView.setImageBitmap(bitmap)
            binding.imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            
            // Wait for layout then setup overlay
            binding.imageView.post {
                setupOverlay()
                binding.loadingIndicator.visibility = View.GONE
            }
            
        } catch (e: Exception) {
            AnnotationLogger.e("Failed to load image", e)
            Toast.makeText(this, "Failed to load image: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun setupOverlay() {
        // Configure overlay
        binding.overlayView.setImageView(binding.imageView)
        binding.overlayView.setImageDimensions(imageWidth, imageHeight)
        binding.overlayView.setAnnotation(imageAnnotation)
        binding.overlayView.setMode(currentMode)
        
        // Handle new annotation created (after drawing a box)
        binding.overlayView.onAnnotationCreated = { boundingBox ->
            pendingBoundingBox = boundingBox
            showLabelPicker()
        }
        
        // Handle annotation selected (tap in VIEW mode)
        binding.overlayView.onAnnotationSelected = { annotation ->
            if (annotation != null) {
                AnnotationLogger.d("Annotation selected: ${annotation.label}")
                showAnnotationOptions(annotation)
            }
        }
        
        updateModeUI()
    }
    
    // ============= Mode Management =============
    
    private fun toggleMode() {
        currentMode = if (currentMode == AnnotationOverlayView.Mode.VIEW) {
            AnnotationOverlayView.Mode.ANNOTATE
        } else {
            AnnotationOverlayView.Mode.VIEW
        }
        
        binding.overlayView.setMode(currentMode)
        binding.overlayView.clearSelection()
        updateModeUI()
    }
    
    private fun updateModeUI() {
        when (currentMode) {
            AnnotationOverlayView.Mode.VIEW -> {
                binding.btnModeToggle.text = "Draw"
                binding.btnModeToggle.setIconResource(R.drawable.ic_edit)
                binding.txtModeIndicator.text = "VIEW MODE - Tap annotations to edit"
                binding.txtModeIndicator.setBackgroundColor(0xFF2196F3.toInt())
            }
            AnnotationOverlayView.Mode.ANNOTATE -> {
                binding.btnModeToggle.text = "View"
                binding.btnModeToggle.setIconResource(R.drawable.ic_visibility)
                binding.txtModeIndicator.text = "DRAW MODE - Drag to draw boxes"
                binding.txtModeIndicator.setBackgroundColor(0xFFFF5722.toInt())
            }
        }
        
        // Update clear button visibility
        binding.btnClear.isVisible = imageAnnotation.hasAnnotations()
        
        // Update count
        val count = imageAnnotation.annotationCount()
        binding.txtAnnotationCount.text = when {
            count == 0 -> "No annotations"
            count == 1 -> "1 annotation"
            else -> "$count annotations"
        }
    }
    
    // ============= Annotation Operations =============
    
    private fun showLabelPicker() {
        val picker = LabelPickerBottomSheet.newInstance()
        
        picker.onLabelSelected = { label ->
            createAnnotation(label)
        }
        
        picker.onDismissed = {
            // User dismissed without selecting - discard the pending box
            if (pendingBoundingBox != null) {
                AnnotationLogger.logAnnotationDiscarded("No label selected")
                pendingBoundingBox = null
            }
        }
        
        picker.show(supportFragmentManager, LabelPickerBottomSheet.TAG)
    }
    
    private fun createAnnotation(label: String) {
        val bbox = pendingBoundingBox ?: return
        pendingBoundingBox = null
        
        val annotation = AnnotationBox(
            id = UUID.randomUUID().toString(),
            label = label,
            bbox = bbox
        )
        
        imageAnnotation.addAnnotation(annotation)
        
        AnnotationLogger.logAnnotationAdded(
            annotation.id,
            annotation.label,
            "[${bbox.left.toInt()}, ${bbox.top.toInt()}, ${bbox.right.toInt()}, ${bbox.bottom.toInt()}]"
        )
        
        // Refresh the overlay
        binding.overlayView.setAnnotation(imageAnnotation)
        updateModeUI()
        
        Toast.makeText(this, "Added: $label", Toast.LENGTH_SHORT).show()
    }
    
    private fun showAnnotationOptions(annotation: AnnotationBox) {
        val options = arrayOf(
            "Change Label (${annotation.label})",
            "Delete"
        )
        
        AlertDialog.Builder(this)
            .setTitle("Edit Annotation")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showLabelPickerForEdit(annotation)
                    1 -> confirmDeleteAnnotation(annotation)
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                binding.overlayView.clearSelection()
                dialog.dismiss()
            }
            .setOnCancelListener {
                binding.overlayView.clearSelection()
            }
            .show()
    }
    
    private fun showLabelPickerForEdit(annotation: AnnotationBox) {
        val picker = LabelPickerBottomSheet.newInstance()
        
        picker.onLabelSelected = { newLabel ->
            if (imageAnnotation.updateAnnotationLabel(annotation.id, newLabel)) {
                AnnotationLogger.d("Updated annotation ${annotation.id} label to: $newLabel")
                binding.overlayView.setAnnotation(imageAnnotation)
                binding.overlayView.clearSelection()
                Toast.makeText(this, "Label changed to: $newLabel", Toast.LENGTH_SHORT).show()
            }
        }
        
        picker.onDismissed = {
            binding.overlayView.clearSelection()
        }
        
        picker.show(supportFragmentManager, LabelPickerBottomSheet.TAG)
    }
    
    private fun confirmDeleteAnnotation(annotation: AnnotationBox) {
        AlertDialog.Builder(this)
            .setTitle("Delete Annotation")
            .setMessage("Delete this ${annotation.label} annotation?")
            .setPositiveButton("Delete") { _, _ ->
                deleteAnnotation(annotation)
            }
            .setNegativeButton("Cancel") { _, _ ->
                binding.overlayView.clearSelection()
            }
            .show()
    }
    
    private fun deleteAnnotation(annotation: AnnotationBox) {
        if (imageAnnotation.removeAnnotation(annotation.id)) {
            AnnotationLogger.logAnnotationDeleted(annotation.id)
            binding.overlayView.setAnnotation(imageAnnotation)
            binding.overlayView.clearSelection()
            updateModeUI()
            Toast.makeText(this, "Annotation deleted", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun clearAllAnnotations() {
        if (!imageAnnotation.hasAnnotations()) return
        
        AlertDialog.Builder(this)
            .setTitle("Clear All")
            .setMessage("Delete all ${imageAnnotation.annotationCount()} annotations?")
            .setPositiveButton("Clear All") { _, _ ->
                imageAnnotation.clearAnnotations()
                binding.overlayView.setAnnotation(imageAnnotation)
                binding.overlayView.clearSelection()
                updateModeUI()
                AnnotationLogger.d("All annotations cleared")
                Toast.makeText(this, "All annotations cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    // ============= Save/Export =============
    
    private fun showSaveOptions() {
        if (!imageAnnotation.hasAnnotations()) {
            Toast.makeText(this, "No annotations to save", Toast.LENGTH_SHORT).show()
            return
        }
        
        val options = arrayOf(
            "Save JSON Only (for data export)",
            "Save Annotated Image (with boxes drawn)",
            "Save Both"
        )
        
        AlertDialog.Builder(this)
            .setTitle("Save Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveJsonOnly()
                    1 -> saveAnnotatedImageOnly()
                    2 -> saveBoth()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun saveJsonOnly() {
        val jsonPath = exportAnnotationsToJson()
        if (jsonPath != null) {
            Toast.makeText(this, "JSON saved successfully", Toast.LENGTH_LONG).show()
            setResultAndFinishIfNeeded(jsonPath, null)
        } else {
            Toast.makeText(this, "Failed to save JSON", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun saveAnnotatedImageOnly() {
        val imagePath = exportAnnotatedImage()
        if (imagePath != null) {
            Toast.makeText(this, "Annotated image saved:\n$imagePath", Toast.LENGTH_LONG).show()
            setResultAndFinishIfNeeded(null, imagePath)
        } else {
            Toast.makeText(this, "Failed to save annotated image", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun saveBoth() {
        val jsonPath = exportAnnotationsToJson()
        val annotatedImagePath = exportAnnotatedImage()
        
        val messages = mutableListOf<String>()
        if (jsonPath != null) messages.add("JSON saved")
        if (annotatedImagePath != null) messages.add("Annotated image saved")
        
        if (messages.isNotEmpty()) {
            Toast.makeText(this, messages.joinToString("\n"), Toast.LENGTH_LONG).show()
            setResultAndFinishIfNeeded(jsonPath, annotatedImagePath)
        } else {
            Toast.makeText(this, "Failed to save", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun exportAnnotationsToJson(): String? {
        val sId = sessionId ?: "session_${System.currentTimeMillis()}"
        
        // Create session with this image's annotations
        val session = AnnotationSession.create(sId)
        session.setAnnotation(imageAnnotation)
        
        // Export to JSON
        val filePath = AnnotationExporter.exportSession(this, session)
        
        if (filePath != null) {
            AnnotationLogger.logSessionSaved(sId)
        }
        
        return filePath
    }
    
    private fun exportAnnotatedImage(): String? {
        val bmp = bitmap ?: return null
        
        // Get output directory (same as original image directory, or Annotations folder)
        val originalFile = File(imagePath)
        val outputDir = originalFile.parentFile ?: getExternalFilesDir("AnnotatedImages") ?: return null
        
        return AnnotatedImageExporter.exportAnnotatedImage(
            originalBitmap = bmp,
            annotation = imageAnnotation,
            outputDir = outputDir,
            originalFilename = imageFilename
        )
    }
    
    private fun setResultAndFinishIfNeeded(jsonPath: String?, annotatedImagePath: String?) {
        setResult(RESULT_OK, Intent().apply {
            jsonPath?.let { putExtra("ANNOTATION_JSON_PATH", it) }
            annotatedImagePath?.let { putExtra("ANNOTATED_IMAGE_PATH", it) }
            putExtra("SESSION_ID", sessionId ?: "")
            putExtra("ANNOTATION_COUNT", imageAnnotation.annotationCount())
        })
    }
    
    // ============= Back Handling =============
    
    private fun handleBackPress() {
        if (imageAnnotation.hasAnnotations()) {
            AlertDialog.Builder(this)
                .setTitle("Unsaved Changes")
                .setMessage("You have ${imageAnnotation.annotationCount()} annotation(s). Save before leaving?")
                .setPositiveButton("Save & Exit") { _, _ ->
                    saveBoth()
                    finish()
                }
                .setNegativeButton("Discard") { _, _ ->
                    finish()
                }
                .setNeutralButton("Cancel", null)
                .show()
        } else {
            finish()
        }
    }
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackPress()
    }
}
