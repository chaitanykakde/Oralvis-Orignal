package com.oralvis.annotation.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.oralvis.annotation.model.AnnotationLabel
import com.oralvis.annotation.util.AnnotationLogger

/**
 * Bottom sheet dialog for selecting annotation labels.
 * 
 * Displays the predefined dental condition labels in a scrollable list.
 * Each label is shown with its associated color.
 */
class LabelPickerBottomSheet : BottomSheetDialogFragment() {
    
    /**
     * Callback when a label is selected.
     */
    var onLabelSelected: ((String) -> Unit)? = null
    
    /**
     * Callback when the dialog is dismissed without selection.
     */
    var onDismissed: (() -> Unit)? = null
    
    private var wasLabelSelected = false
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Create the view programmatically to avoid XML dependency
        val rootLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 32, 0, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Add title
        val titleView = TextView(requireContext()).apply {
            text = "Select Label"
            textSize = 20f
            setTextColor(Color.BLACK)
            setPadding(48, 16, 48, 32)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        rootLayout.addView(titleView)
        
        // Add label buttons
        AnnotationLabel.getAllLabels().forEach { label ->
            val labelButton = createLabelButton(label)
            rootLayout.addView(labelButton)
        }
        
        // Add cancel button
        val cancelButton = TextView(requireContext()).apply {
            text = "Cancel"
            textSize = 16f
            setTextColor(Color.parseColor("#757575"))
            setPadding(48, 32, 48, 16)
            gravity = android.view.Gravity.CENTER
            setOnClickListener {
                dismiss()
            }
        }
        rootLayout.addView(cancelButton)
        
        return rootLayout
    }
    
    private fun createLabelButton(label: AnnotationLabel): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 24, 48, 24)
            gravity = android.view.Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            
            // Add ripple effect
            val attrs = intArrayOf(android.R.attr.selectableItemBackground)
            val ta = context.obtainStyledAttributes(attrs)
            foreground = ta.getDrawable(0)
            ta.recycle()
            
            // Color indicator
            val colorIndicator = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(48, 48).apply {
                    marginEnd = 32
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(label.color)
                }
            }
            addView(colorIndicator)
            
            // Label text
            val textView = TextView(context).apply {
                text = label.displayName
                textSize = 18f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            addView(textView)
            
            // Click handler
            setOnClickListener {
                wasLabelSelected = true
                AnnotationLogger.logLabelSelected(label.displayName)
                onLabelSelected?.invoke(label.displayName)
                dismiss()
            }
        }
    }
    
    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (!wasLabelSelected) {
            AnnotationLogger.logLabelSelected(null)
            onDismissed?.invoke()
        }
    }
    
    companion object {
        const val TAG = "LabelPickerBottomSheet"
        
        /**
         * Create a new instance of the label picker.
         */
        fun newInstance(): LabelPickerBottomSheet {
            return LabelPickerBottomSheet()
        }
    }
}
