package com.oralvis.annotation.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.oralvis.annotation.export.AnnotationExporter
import com.oralvis.annotation.model.AnnotationBox
import com.oralvis.annotation.model.ImageAnnotation
import com.oralvis.annotation.model.AnnotationSession
import com.oralvis.annotation.overlay.AnnotationOverlayView
import com.oralvis.annotation.util.AnnotationLogger
import com.oralvis.annotation.util.CoordinateMapper
import com.oralvis.oralviscamera.R
import com.oralvis.oralviscamera.databinding.ActivityAnnotationBinding
import java.io.File
import java.util.UUID

/**
 * Activity for annotating images with bounding boxes.
 * 
 * Features:
 * - Image display with zoom and pan support
 * - Drawing bounding boxes in ANNOTATE mode
 * - Selecting/editing existing boxes in VIEW mode
 * - Label picker for assigning labels to boxes
 * - Export annotations to JSON format
 * 
 * Launch with:
 * - IMAGE_PATH: Absolute path to the image file
 * - IMAGE_FILENAME: Filename of the image
 * - SESSION_ID: Optional session identifier for batch export
 */
class AnnotationActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_IMAGE_PATH = "IMAGE_PATH"
        const val EXTRA_IMAGE_FILENAME = "IMAGE_FILENAME"
        const val EXTRA_SESSION_ID = "SESSION_ID"
        
        /**
         * Create an intent to launch the annotation activity.
         */
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
    
    // ============= View Binding =============
    private lateinit var binding: ActivityAnnotationBinding
    
    // ============= Image & Annotation State =============
    private var imagePath: String = ""
    private var imageFilename: String = ""
    private var sessionId: String? = null
    private var bitmap: Bitmap? = null
    private lateinit var imageAnnotation: ImageAnnotation
    
    // ============= Zoom & Pan State =============
    private val imageMatrix = Matrix()
    private val savedMatrix = Matrix()
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f
    
    // For panning
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    
    // Limits
    private val minScale = 0.5f
    private val maxScale = 5.0f
    
    // ============= Mode State =============
    private var currentMode: AnnotationOverlayView.Mode = AnnotationOverlayView.Mode.VIEW
    
    // ============= Pending annotation (awaiting label) =============
    private var pendingBoundingBox: RectF? = null
    
    // ============= Lifecycle =============
    
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
        
        // Initialize image annotation
        imageAnnotation = ImageAnnotation.empty(imageFilename)
        
        setupUI()
        loadImage()
        setupOverlay()
        setupGestures()
        updateModeUI()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        AnnotationLogger.logActivityDestroyed("AnnotationActivity")
        bitmap?.recycle()
    }
    
    // ============= Setup Methods =============
    
    private fun setupUI() {
        // Back button
        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        
        // Mode toggle button
        binding.btnModeToggle.setOnClickListener {
            toggleMode()
        }
        
        // Save/Export button
        binding.btnSave.setOnClickListener {
            saveAnnotations()
        }
        
        // Clear button (visible only when there are annotations)
        binding.btnClear.setOnClickListener {
            clearAllAnnotations()
        }
        
        // Info display
        binding.txtImageInfo.text = imageFilename
    }
    
    private fun loadImage() {
        val file = File(imagePath)
        if (!file.exists()) {
            Toast.makeText(this, "Image file not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        try {
            bitmap = BitmapFactory.decodeFile(imagePath)
            if (bitmap == null) {
                Toast.makeText(this, "Failed to decode image", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            
            // Set image dimensions for annotation
            imageAnnotation = ImageAnnotation.empty(
                filename = imageFilename,
                width = bitmap!!.width,
                height = bitmap!!.height
            )
            
            // Display image
            binding.imageView.setImageBitmap(bitmap)
            binding.imageView.scaleType = ImageView.ScaleType.MATRIX
            
            // Center and fit image initially
            binding.imageView.post {
                centerAndFitImage()
            }
            
            AnnotationLogger.d("Image loaded: ${bitmap!!.width}x${bitmap!!.height}")
            
        } catch (e: Exception) {
            AnnotationLogger.e("Failed to load image", e)
            Toast.makeText(this, "Failed to load image: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun setupOverlay() {
        binding.overlayView.setImageView(binding.imageView)
        binding.overlayView.setMode(currentMode)
        
        // Set image dimensions when bitmap is loaded
        bitmap?.let {
            binding.overlayView.setImageDimensions(it.width, it.height)
        }
        
        // Handle new annotation created
        binding.overlayView.onAnnotationCreated = { boundingBox ->
            handleNewBoundingBox(boundingBox)
        }
        
        // Handle annotation selected
        binding.overlayView.onAnnotationSelected = { annotation ->
            handleAnnotationSelected(annotation)
        }
        
        binding.overlayView.setAnnotation(imageAnnotation)
    }
    
    private fun setupGestures() {
        // Scale gesture detector for pinch zoom
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (currentMode == AnnotationOverlayView.Mode.ANNOTATE) {
                    // Don't zoom while in annotation mode
                    return false
                }
                
                val oldScale = scaleFactor
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(minScale, maxScale)
                
                // Scale around the focal point
                val focusX = detector.focusX
                val focusY = detector.focusY
                
                imageMatrix.postScale(
                    scaleFactor / oldScale,
                    scaleFactor / oldScale,
                    focusX,
                    focusY
                )
                
                binding.imageView.imageMatrix = imageMatrix
                binding.overlayView.invalidate()
                
                return true
            }
        })
        
        // Touch listener for pan and delegation to overlay
        binding.rootLayout.setOnTouchListener { _, event ->
            handleTouch(event)
        }
    }
    
    private fun handleTouch(event: MotionEvent): Boolean {
        // Always pass to scale detector
        scaleGestureDetector?.onTouchEvent(event)
        
        // In ANNOTATE mode, let the overlay handle drawing
        if (currentMode == AnnotationOverlayView.Mode.ANNOTATE) {
            return binding.overlayView.onTouchEvent(event)
        }
        
        // In VIEW mode, handle pan and pass through taps to overlay
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(imageMatrix)
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
            }
            
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                
                // Start dragging if moved enough
                if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                    isDragging = true
                }
                
                if (isDragging && event.pointerCount == 1) {
                    imageMatrix.set(savedMatrix)
                    imageMatrix.postTranslate(dx, dy)
                    binding.imageView.imageMatrix = imageMatrix
                    binding.overlayView.invalidate()
                }
            }
            
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // This was a tap, pass to overlay for selection
                    binding.overlayView.onTouchEvent(event)
                }
                isDragging = false
            }
        }
        
        return true
    }
    
    // ============= Mode Handling =============
    
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
                binding.btnModeToggle.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_edit, 0, 0, 0
                )
                binding.txtModeIndicator.text = "VIEW MODE - Tap to select"
                binding.txtModeIndicator.setBackgroundColor(0xFF2196F3.toInt())
            }
            AnnotationOverlayView.Mode.ANNOTATE -> {
                binding.btnModeToggle.text = "View"
                binding.btnModeToggle.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_visibility, 0, 0, 0
                )
                binding.txtModeIndicator.text = "ANNOTATE MODE - Draw boxes"
                binding.txtModeIndicator.setBackgroundColor(0xFFFF5722.toInt())
            }
        }
        
        // Show/hide clear button based on annotation count
        binding.btnClear.isVisible = imageAnnotation.hasAnnotations()
        
        // Update annotation count
        val count = imageAnnotation.annotationCount()
        binding.txtAnnotationCount.text = if (count == 0) {
            "No annotations"
        } else {
            "$count annotation${if (count > 1) "s" else ""}"
        }
    }
    
    // ============= Annotation Handling =============
    
    private fun handleNewBoundingBox(boundingBox: RectF) {
        // Store pending box and show label picker
        pendingBoundingBox = boundingBox
        showLabelPicker()
    }
    
    private fun showLabelPicker() {
        val picker = LabelPickerBottomSheet.newInstance()
        
        picker.onLabelSelected = { label ->
            // Create and add the annotation with the selected label
            pendingBoundingBox?.let { bbox ->
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
                
                binding.overlayView.setAnnotation(imageAnnotation)
                updateModeUI()
            }
            pendingBoundingBox = null
        }
        
        picker.onDismissed = {
            // Discard the pending box if no label was selected
            if (pendingBoundingBox != null) {
                AnnotationLogger.logAnnotationDiscarded("No label selected")
                pendingBoundingBox = null
            }
        }
        
        picker.show(supportFragmentManager, LabelPickerBottomSheet.TAG)
    }
    
    private fun handleAnnotationSelected(annotation: AnnotationBox?) {
        if (annotation == null) {
            // Selection cleared
            return
        }
        
        // Show options dialog
        AnnotationOptionsDialog.show(
            context = this,
            annotation = annotation,
            onChangeLabel = {
                showLabelPickerForEdit(annotation)
            },
            onDelete = {
                deleteAnnotation(annotation)
            }
        )
    }
    
    private fun showLabelPickerForEdit(annotation: AnnotationBox) {
        val picker = LabelPickerBottomSheet.newInstance()
        
        picker.onLabelSelected = { newLabel ->
            if (imageAnnotation.updateAnnotationLabel(annotation.id, newLabel)) {
                AnnotationLogger.d("Updated annotation ${annotation.id} label to $newLabel")
                binding.overlayView.setAnnotation(imageAnnotation)
                binding.overlayView.clearSelection()
            }
        }
        
        picker.show(supportFragmentManager, LabelPickerBottomSheet.TAG)
    }
    
    private fun deleteAnnotation(annotation: AnnotationBox) {
        AnnotationOptionsDialog.showDeleteConfirmation(
            context = this,
            annotation = annotation,
            onConfirm = {
                if (imageAnnotation.removeAnnotation(annotation.id)) {
                    AnnotationLogger.logAnnotationDeleted(annotation.id)
                    binding.overlayView.setAnnotation(imageAnnotation)
                    binding.overlayView.clearSelection()
                    updateModeUI()
                }
            }
        )
    }
    
    private fun clearAllAnnotations() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Clear All Annotations")
            .setMessage("Are you sure you want to delete all annotations from this image?")
            .setPositiveButton("Clear All") { _, _ ->
                imageAnnotation.clearAnnotations()
                binding.overlayView.setAnnotation(imageAnnotation)
                binding.overlayView.clearSelection()
                updateModeUI()
                AnnotationLogger.d("All annotations cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    // ============= Save/Export =============
    
    private fun saveAnnotations() {
        if (!imageAnnotation.hasAnnotations()) {
            Toast.makeText(this, "No annotations to save", Toast.LENGTH_SHORT).show()
            return
        }
        
        val sId = sessionId ?: "manual_${System.currentTimeMillis()}"
        
        // Create a session with this image's annotations
        val session = AnnotationSession.create(sId)
        session.setAnnotation(imageAnnotation)
        
        // Export to JSON
        val filePath = AnnotationExporter.exportSession(this, session)
        
        if (filePath != null) {
            Toast.makeText(this, "Annotations saved", Toast.LENGTH_SHORT).show()
            AnnotationLogger.logSessionSaved(sId)
            
            // Return result
            setResult(RESULT_OK, Intent().apply {
                putExtra("ANNOTATION_FILE_PATH", filePath)
                putExtra("SESSION_ID", sId)
            })
        } else {
            Toast.makeText(this, "Failed to save annotations", Toast.LENGTH_SHORT).show()
        }
    }
    
    // ============= Image Transform Helpers =============
    
    private fun centerAndFitImage() {
        val drawable = binding.imageView.drawable ?: return
        
        val viewWidth = binding.imageView.width.toFloat()
        val viewHeight = binding.imageView.height.toFloat()
        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()
        
        // Calculate scale to fit
        val scaleX = viewWidth / imageWidth
        val scaleY = viewHeight / imageHeight
        scaleFactor = minOf(scaleX, scaleY)
        
        // Calculate centering translation
        val scaledImageWidth = imageWidth * scaleFactor
        val scaledImageHeight = imageHeight * scaleFactor
        translateX = (viewWidth - scaledImageWidth) / 2
        translateY = (viewHeight - scaledImageHeight) / 2
        
        // Apply transform
        imageMatrix.reset()
        imageMatrix.setScale(scaleFactor, scaleFactor)
        imageMatrix.postTranslate(translateX, translateY)
        
        binding.imageView.imageMatrix = imageMatrix
        binding.overlayView.invalidate()
        
        CoordinateMapper.logImageMatrix(binding.imageView)
    }
    
    // ============= Back Press Handling =============
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (imageAnnotation.hasAnnotations()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Unsaved Annotations")
                .setMessage("You have unsaved annotations. Do you want to save before leaving?")
                .setPositiveButton("Save") { _, _ ->
                    saveAnnotations()
                    super.onBackPressed()
                }
                .setNegativeButton("Discard") { _, _ ->
                    super.onBackPressed()
                }
                .setNeutralButton("Cancel", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }
}
