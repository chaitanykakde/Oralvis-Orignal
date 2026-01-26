package com.oralvis.annotation.ui

import android.app.AlertDialog
import android.content.Context
import com.oralvis.annotation.model.AnnotationBox

/**
 * Dialog for annotation options (change label, delete) in VIEW mode.
 * 
 * Shown when user taps on an existing annotation.
 */
object AnnotationOptionsDialog {
    
    /**
     * Show options dialog for a selected annotation.
     * 
     * @param context Context for dialog creation
     * @param annotation The selected annotation
     * @param onChangeLabel Callback when "Change Label" is selected
     * @param onDelete Callback when "Delete" is selected
     */
    fun show(
        context: Context,
        annotation: AnnotationBox,
        onChangeLabel: () -> Unit,
        onDelete: () -> Unit
    ) {
        val options = arrayOf(
            "Change Label (${annotation.label})",
            "Delete Annotation"
        )
        
        AlertDialog.Builder(context)
            .setTitle("Annotation Options")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> onChangeLabel()
                    1 -> onDelete()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    
    /**
     * Show confirmation dialog for deleting an annotation.
     * 
     * @param context Context for dialog creation
     * @param annotation The annotation to delete
     * @param onConfirm Callback when deletion is confirmed
     */
    fun showDeleteConfirmation(
        context: Context,
        annotation: AnnotationBox,
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle("Delete Annotation")
            .setMessage("Are you sure you want to delete this ${annotation.label} annotation?")
            .setPositiveButton("Delete") { dialog, _ ->
                onConfirm()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
