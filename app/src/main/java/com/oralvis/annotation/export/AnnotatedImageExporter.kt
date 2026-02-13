package com.oralvis.annotation.export

import android.graphics.*
import com.oralvis.annotation.model.AnnotationLabel
import com.oralvis.annotation.model.ImageAnnotation
import com.oralvis.annotation.util.AnnotationLogger
import java.io.File
import java.io.FileOutputStream

/**
 * Exports annotated images with bounding boxes drawn directly on the image.
 * 
 * This creates a new image file with all annotations visually rendered,
 * preserving the original image file.
 */
object AnnotatedImageExporter {
    
    private const val ANNOTATED_SUFFIX = "_annotated"
    
    /**
     * Export an annotated image with bounding boxes drawn on it.
     * 
     * @param originalBitmap The original image bitmap
     * @param annotation The annotations to draw
     * @param outputDir Directory to save the annotated image
     * @param originalFilename Original filename (will be modified with suffix)
     * @return Path to the saved annotated image, or null if failed
     */
    fun exportAnnotatedImage(
        originalBitmap: Bitmap,
        annotation: ImageAnnotation,
        outputDir: File,
        originalFilename: String
    ): String? {
        if (!annotation.hasAnnotations()) {
            AnnotationLogger.w("No annotations to export for image: $originalFilename")
            return null
        }
        
        try {
            // Create a mutable copy of the bitmap
            val annotatedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(annotatedBitmap)
            
            // Draw all annotations on the bitmap
            drawAnnotationsOnCanvas(canvas, annotation)
            
            // Generate output filename
            val outputFilename = generateAnnotatedFilename(originalFilename)
            
            // Ensure output directory exists
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            
            // Save the annotated image
            val outputFile = File(outputDir, outputFilename)
            FileOutputStream(outputFile).use { out ->
                // Use JPEG for photos, PNG for others
                val format = if (originalFilename.lowercase().endsWith(".png")) {
                    Bitmap.CompressFormat.PNG
                } else {
                    Bitmap.CompressFormat.JPEG
                }
                val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 95
                annotatedBitmap.compress(format, quality, out)
            }
            
            // Clean up
            annotatedBitmap.recycle()
            
            AnnotationLogger.logExportCompleted(outputFile.absolutePath)
            return outputFile.absolutePath
            
        } catch (e: Exception) {
            AnnotationLogger.logExportFailed("Failed to export annotated image", e)
            return null
        }
    }
    
    /**
     * Export annotated image from file path.
     */
    fun exportAnnotatedImage(
        originalImagePath: String,
        annotation: ImageAnnotation,
        outputDir: File
    ): String? {
        val originalFile = File(originalImagePath)
        if (!originalFile.exists()) {
            AnnotationLogger.e("Original image not found: $originalImagePath")
            return null
        }
        
        // Load the original bitmap
        val originalBitmap = BitmapFactory.decodeFile(originalImagePath)
        if (originalBitmap == null) {
            AnnotationLogger.e("Failed to decode image: $originalImagePath")
            return null
        }
        
        val result = exportAnnotatedImage(
            originalBitmap = originalBitmap,
            annotation = annotation,
            outputDir = outputDir,
            originalFilename = originalFile.name
        )
        
        // Clean up
        originalBitmap.recycle()
        
        return result
    }
    
    /**
     * Draw all annotations on a canvas (for the image bitmap).
     */
    private fun drawAnnotationsOnCanvas(canvas: Canvas, annotation: ImageAnnotation) {
        // Calculate stroke width based on image size
        val imageWidth = canvas.width
        val strokeWidth = maxOf(4f, imageWidth / 300f)
        val textSize = maxOf(24f, imageWidth / 40f)
        
        val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            isAntiAlias = true
        }
        
        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        val labelPaint = Paint().apply {
            color = Color.WHITE
            this.textSize = textSize
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
        
        val labelBgPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        // Draw each annotation
        for (box in annotation.annotations) {
            val labelColor = AnnotationLabel.getColorForLabel(box.label)
            val rect = box.bbox
            
            // Draw semi-transparent fill
            fillPaint.color = Color.argb(40, Color.red(labelColor), Color.green(labelColor), Color.blue(labelColor))
            canvas.drawRect(rect, fillPaint)
            
            // Draw border
            boxPaint.color = labelColor
            canvas.drawRect(rect, boxPaint)
            
            // Draw label background and text
            drawLabelOnCanvas(canvas, box.label, rect, labelColor, labelPaint, labelBgPaint, textSize)
        }
    }
    
    /**
     * Draw a label on the canvas.
     */
    private fun drawLabelOnCanvas(
        canvas: Canvas,
        label: String,
        boxRect: RectF,
        color: Int,
        labelPaint: Paint,
        labelBgPaint: Paint,
        textSize: Float
    ) {
        val textBounds = Rect()
        labelPaint.getTextBounds(label, 0, label.length, textBounds)
        
        val padding = textSize / 4
        val labelWidth = textBounds.width() + padding * 2
        val labelHeight = textBounds.height() + padding * 2
        
        // Position above box, or below if no room
        val labelLeft = boxRect.left
        val labelTop = if (boxRect.top - labelHeight >= 0) {
            boxRect.top - labelHeight
        } else {
            boxRect.bottom
        }
        
        // Background
        labelBgPaint.color = color
        canvas.drawRect(labelLeft, labelTop, labelLeft + labelWidth, labelTop + labelHeight, labelBgPaint)
        
        // Text
        canvas.drawText(label, labelLeft + padding, labelTop + labelHeight - padding, labelPaint)
    }
    
    /**
     * Generate filename for annotated image.
     */
    private fun generateAnnotatedFilename(originalFilename: String): String {
        val dotIndex = originalFilename.lastIndexOf('.')
        return if (dotIndex > 0) {
            val name = originalFilename.substring(0, dotIndex)
            val ext = originalFilename.substring(dotIndex)
            "${name}${ANNOTATED_SUFFIX}${ext}"
        } else {
            "${originalFilename}${ANNOTATED_SUFFIX}.jpg"
        }
    }
    
    /**
     * Get the annotated filename for a given original filename.
     */
    fun getAnnotatedFilename(originalFilename: String): String {
        return generateAnnotatedFilename(originalFilename)
    }

    /**
     * Save the annotated image to the same path (overwrite original).
     * Use this so the same image file contains the drawn annotations.
     * @param originalImagePath Full path to the original image (will be overwritten)
     * @param originalBitmap Bitmap of the image
     * @param annotation Annotations to draw
     * @return true if saved successfully
     */
    fun exportAnnotatedImageToSamePath(
        originalImagePath: String,
        originalBitmap: Bitmap,
        annotation: ImageAnnotation
    ): Boolean {
        if (!annotation.hasAnnotations()) return false
        val outputFile = File(originalImagePath)
        if (outputFile.parentFile?.exists() != true) return false
        return try {
            val annotatedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(annotatedBitmap)
            drawAnnotationsOnCanvas(canvas, annotation)
            FileOutputStream(outputFile).use { out ->
                val name = outputFile.name.lowercase()
                val format = if (name.endsWith(".png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 95
                annotatedBitmap.compress(format, quality, out)
            }
            annotatedBitmap.recycle()
            true
        } catch (e: Exception) {
            AnnotationLogger.logExportFailed("Export to same path", e)
            false
        }
    }
}
