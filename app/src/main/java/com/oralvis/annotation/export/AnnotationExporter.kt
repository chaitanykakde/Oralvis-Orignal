package com.oralvis.annotation.export

import android.content.Context
import com.oralvis.annotation.model.AnnotationBox
import com.oralvis.annotation.model.AnnotationSession
import com.oralvis.annotation.model.ImageAnnotation
import com.oralvis.annotation.util.AnnotationLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.security.MessageDigest
import java.util.UUID

/**
 * Handles exporting annotation data to JSON format.
 * 
 * JSON format MUST match exactly:
 * [
 *   {
 *     "Filename": "image1.jpg",
 *     "Annotations": [
 *       {
 *         "Class": "Active Caries",
 *         "Bbox": [355, 173, 482, 359]
 *       }
 *     ]
 *   }
 * ]
 * 
 * Files are saved to /Annotations/ directory with format:
 * annotations_<sessionId>.json
 */
object AnnotationExporter {
    
    private const val ANNOTATIONS_DIR = "Annotations"
    
    /**
     * Export annotations for a session to JSON file.
     * 
     * @param context Application context
     * @param session The annotation session to export
     * @return File path of the exported JSON, or null if export failed
     */
    fun exportSession(context: Context, session: AnnotationSession): String? {
        val annotatedImages = session.getAnnotatedImages()
        
        if (annotatedImages.isEmpty()) {
            AnnotationLogger.w("No annotated images to export for session ${session.sessionId}")
            return null
        }
        
        AnnotationLogger.logExportStarted(session.sessionId, annotatedImages.size)
        
        return try {
            // Create JSON array
            val jsonArray = createJsonArray(annotatedImages)
            
            // Get/create output directory
            val outputDir = getAnnotationsDirectory(context)
            if (outputDir == null) {
                AnnotationLogger.logExportFailed("Failed to create annotations directory")
                return null
            }
            
            // Create output file
            val filename = "annotations_${session.sessionId}.json"
            val outputFile = File(outputDir, filename)
            
            // Write JSON to file
            FileWriter(outputFile).use { writer ->
                writer.write(jsonArray.toString(2)) // Pretty print with 2-space indent
            }
            
            val filePath = outputFile.absolutePath
            AnnotationLogger.logExportCompleted(filePath)
            
            filePath
        } catch (e: Exception) {
            AnnotationLogger.logExportFailed("Exception during export", e)
            null
        }
    }
    
    /**
     * Export a single image annotation to JSON string.
     * Useful for debugging or preview purposes.
     */
    fun exportImageToJson(imageAnnotation: ImageAnnotation): String {
        val jsonObj = createImageJson(imageAnnotation)
        return jsonObj.toString(2)
    }
    
    /**
     * Get the annotations directory, creating it if necessary.
     */
    fun getAnnotationsDirectory(context: Context): File? {
        val baseDir = context.getExternalFilesDir(null) ?: return null
        val annotationsDir = File(baseDir, ANNOTATIONS_DIR)
        
        if (!annotationsDir.exists()) {
            if (!annotationsDir.mkdirs()) {
                AnnotationLogger.e("Failed to create annotations directory: ${annotationsDir.absolutePath}")
                return null
            }
        }
        
        return annotationsDir
    }
    
    /**
     * Check if an annotation file exists for a session.
     */
    fun annotationFileExists(context: Context, sessionId: String): Boolean {
        val dir = getAnnotationsDirectory(context) ?: return false
        val file = File(dir, "annotations_$sessionId.json")
        return file.exists()
    }
    
    /**
     * Get the path for an annotation file.
     */
    fun getAnnotationFilePath(context: Context, sessionId: String): String? {
        val dir = getAnnotationsDirectory(context) ?: return null
        return File(dir, "annotations_$sessionId.json").absolutePath
    }
    
    /**
     * Delete an annotation file.
     */
    fun deleteAnnotationFile(context: Context, sessionId: String): Boolean {
        val dir = getAnnotationsDirectory(context) ?: return false
        val file = File(dir, "annotations_$sessionId.json")
        return if (file.exists()) {
            file.delete()
        } else {
            true // Already doesn't exist
        }
    }
    
    /**
     * List all annotation files in the annotations directory.
     */
    fun listAnnotationFiles(context: Context): List<File> {
        val dir = getAnnotationsDirectory(context) ?: return emptyList()
        return dir.listFiles { file -> 
            file.isFile && file.name.startsWith("annotations_") && file.name.endsWith(".json")
        }?.toList() ?: emptyList()
    }

    // ============= Per-image annotation (same image load/save) =============

    private const val PER_IMAGE_PREFIX = "img_"

    /**
     * Get the annotation file used for a specific image path (same path = same file).
     */
    fun getAnnotationFileForImage(context: Context, imagePath: String): File? {
        val dir = getAnnotationsDirectory(context) ?: return null
        val key = hashPathForFilename(imagePath)
        return File(dir, "$PER_IMAGE_PREFIX$key.json")
    }

    /**
     * Save annotations for this image so they load when the same image is opened again.
     * @return Path to the saved JSON file, or null if failed
     */
    fun saveAnnotationsForImage(
        context: Context,
        imagePath: String,
        imageAnnotation: ImageAnnotation
    ): String? {
        if (!imageAnnotation.hasAnnotations()) return null
        val file = getAnnotationFileForImage(context, imagePath) ?: return null
        return try {
            val jsonObj = createImageJson(imageAnnotation)
            FileWriter(file).use { writer ->
                writer.write(jsonObj.toString(2))
            }
            AnnotationLogger.d("Saved per-image annotations: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            AnnotationLogger.logExportFailed("Save annotations for image", e)
            null
        }
    }

    /**
     * Load annotations previously saved for this image path.
     * @param imagePath Absolute path to the image
     * @param filename Filename for the ImageAnnotation (e.g. File(imagePath).name)
     * @param imageWidth Width of the image (for validation)
     * @param imageHeight Height of the image (for validation)
     * @return Loaded ImageAnnotation or null if none or error
     */
    fun loadAnnotationsForImage(
        context: Context,
        imagePath: String,
        filename: String,
        imageWidth: Int,
        imageHeight: Int
    ): ImageAnnotation? {
        val file = getAnnotationFileForImage(context, imagePath) ?: return null
        if (!file.exists()) return null
        return try {
            val content = file.readText()
            val json = JSONObject(content)
            parseImageAnnotationFromJson(json, filename, imageWidth, imageHeight)
        } catch (e: Exception) {
            AnnotationLogger.e("Failed to load annotations for image: $imagePath", e)
            null
        }
    }

    /**
     * Check if annotations exist for this image path.
     */
    fun hasAnnotationsForImage(context: Context, imagePath: String): Boolean {
        val file = getAnnotationFileForImage(context, imagePath) ?: return false
        return file.exists()
    }

    /**
     * Delete saved annotations for this image (e.g. when user chooses Discard).
     */
    fun deleteAnnotationsForImage(context: Context, imagePath: String): Boolean {
        val file = getAnnotationFileForImage(context, imagePath) ?: return false
        return if (file.exists()) {
            file.delete()
        } else {
            true
        }
    }

    private fun hashPathForFilename(imagePath: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(imagePath.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun parseImageAnnotationFromJson(
        json: JSONObject,
        filename: String,
        imageWidth: Int,
        imageHeight: Int
    ): ImageAnnotation {
        val annotationsArray = json.optJSONArray("Annotations") ?: JSONArray()
        val list = mutableListOf<AnnotationBox>()
        for (i in 0 until annotationsArray.length()) {
            val obj = annotationsArray.getJSONObject(i)
            val label = obj.optString("Class", "")
            val bboxArray = obj.optJSONArray("Bbox") ?: continue
            if (bboxArray.length() < 4) continue
            val left = bboxArray.getInt(0).toFloat()
            val top = bboxArray.getInt(1).toFloat()
            val right = bboxArray.getInt(2).toFloat()
            val bottom = bboxArray.getInt(3).toFloat()
            val rect = android.graphics.RectF(left, top, right, bottom)
            list.add(
                AnnotationBox(
                    id = UUID.randomUUID().toString(),
                    label = label,
                    bbox = rect,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        return ImageAnnotation(
            filename = filename,
            annotations = list,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            lastModified = System.currentTimeMillis()
        )
    }
    
    // ============= Private JSON Creation Methods =============
    
    private fun createJsonArray(imageAnnotations: List<ImageAnnotation>): JSONArray {
        val jsonArray = JSONArray()
        
        for (imageAnnotation in imageAnnotations) {
            val imageJson = createImageJson(imageAnnotation)
            jsonArray.put(imageJson)
        }
        
        return jsonArray
    }
    
    private fun createImageJson(imageAnnotation: ImageAnnotation): JSONObject {
        val imageJson = JSONObject()
        
        // "Filename" key (capital F as per spec)
        imageJson.put("Filename", imageAnnotation.filename)
        
        // "Annotations" array
        val annotationsArray = JSONArray()
        for (box in imageAnnotation.annotations) {
            val boxJson = JSONObject()
            
            // "Class" key (capital C as per spec)
            boxJson.put("Class", box.label)
            
            // "Bbox" array [left, top, right, bottom] as integers
            val bboxArray = JSONArray()
            val bbox = box.getBboxArray()
            bboxArray.put(bbox[0])
            bboxArray.put(bbox[1])
            bboxArray.put(bbox[2])
            bboxArray.put(bbox[3])
            boxJson.put("Bbox", bboxArray)
            
            annotationsArray.put(boxJson)
        }
        
        imageJson.put("Annotations", annotationsArray)
        
        return imageJson
    }
}
