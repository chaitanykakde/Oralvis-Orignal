package com.oralvis.annotation.export

import android.content.Context
import com.oralvis.annotation.model.AnnotationSession
import com.oralvis.annotation.model.ImageAnnotation
import com.oralvis.annotation.util.AnnotationLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

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
