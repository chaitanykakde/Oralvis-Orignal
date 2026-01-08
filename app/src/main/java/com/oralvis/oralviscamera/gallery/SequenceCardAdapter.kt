package com.oralvis.oralviscamera.gallery

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.oralvis.oralviscamera.R
import kotlinx.coroutines.*
import java.io.File

class SequenceCardAdapter(
    private var sequenceCards: List<SequenceCard>,
    private val onRgbImageClick: (SequenceCard) -> Unit,
    private val onFluorescenceImageClick: (SequenceCard) -> Unit,
    private val onDiscardClick: (SequenceCard) -> Unit
) : RecyclerView.Adapter<SequenceCardAdapter.SequenceViewHolder>() {
    
    fun updateSequenceCards(newCards: List<SequenceCard>) {
        android.util.Log.d("SequenceCardAdapter", "updateSequenceCards called with ${newCards.size} cards")
        android.util.Log.d("SequenceCardAdapter", "Old adapter had ${sequenceCards.size} cards")
        newCards.forEachIndexed { index, card ->
            android.util.Log.d("SequenceCardAdapter", "Card[$index]: ${card.getTitle()}, rgb=${card.rgbImage?.fileName}, fluo=${card.fluorescenceImage?.fileName}")
        }
        sequenceCards = newCards
        notifyDataSetChanged()
        android.util.Log.d("SequenceCardAdapter", "Adapter updated, now has ${sequenceCards.size} cards")
    }

    inner class SequenceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val sequenceTitle: TextView = view.findViewById(R.id.sequenceTitle)
        val rgbImage: ImageView = view.findViewById(R.id.rgbImage)
        val fluorescenceImage: ImageView = view.findViewById(R.id.fluorescenceImage)
        val rgbLabel: TextView = view.findViewById(R.id.rgbLabel)
        val fluorescenceLabel: TextView = view.findViewById(R.id.fluorescenceLabel)
        val waitingForCapture: TextView = view.findViewById(R.id.waitingForCapture)
        val discardButton: MaterialButton = view.findViewById(R.id.btnDiscardPair)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SequenceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sequence_card, parent, false)
        return SequenceViewHolder(view)
    }

    override fun onBindViewHolder(holder: SequenceViewHolder, position: Int) {
        val card = sequenceCards[position]

        android.util.Log.d("SequenceCardAdapter", "Binding card $position: ${card.getTitle()}")
        android.util.Log.d("SequenceCardAdapter", "RGB image: ${card.rgbImage?.fileName} at ${card.rgbImage?.filePath}")
        android.util.Log.d("SequenceCardAdapter", "Fluorescence image: ${card.fluorescenceImage?.fileName} at ${card.fluorescenceImage?.filePath}")

        holder.sequenceTitle.text = card.getTitle()

        // Load RGB image
        if (card.rgbImage != null && card.rgbImage.filePath != null) {
            android.util.Log.d("SequenceCardAdapter", "Loading RGB image: ${card.rgbImage.filePath}")
            loadImage(holder.rgbImage, card.rgbImage.filePath)
            holder.rgbLabel.visibility = View.VISIBLE
            holder.rgbImage.setOnClickListener { onRgbImageClick(card) }
        } else {
            android.util.Log.d("SequenceCardAdapter", "No RGB image or file path missing, setting placeholder")
            holder.rgbImage.setImageResource(R.drawable.ic_media_placeholder)
            holder.rgbLabel.visibility = View.GONE
            holder.rgbImage.setOnClickListener(null)
        }

        // Load Fluorescence image
        if (card.fluorescenceImage != null && card.fluorescenceImage.filePath != null) {
            android.util.Log.d("SequenceCardAdapter", "Loading Fluorescence image: ${card.fluorescenceImage.filePath}")
            loadImage(holder.fluorescenceImage, card.fluorescenceImage.filePath)
            holder.fluorescenceLabel.visibility = View.VISIBLE
            holder.waitingForCapture.visibility = View.GONE
            holder.fluorescenceImage.setOnClickListener { onFluorescenceImageClick(card) }
        } else {
            android.util.Log.d("SequenceCardAdapter", "No Fluorescence image or file path missing, setting placeholder")
            holder.fluorescenceImage.setImageResource(R.drawable.ic_media_placeholder)
            holder.fluorescenceLabel.visibility = View.GONE
            holder.waitingForCapture.visibility = View.VISIBLE
            holder.fluorescenceImage.setOnClickListener(null)
        }
        
        // Discard button
        holder.discardButton.setOnClickListener { onDiscardClick(card) }
    }

    override fun getItemCount(): Int = sequenceCards.size

    private fun loadImage(imageView: ImageView, filePath: String) {
        // Set placeholder immediately to prevent UI blocking
        imageView.setImageResource(R.drawable.ic_media_placeholder)

        // Load image asynchronously
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val file = File(filePath)
                android.util.Log.d("SequenceCardAdapter", "Loading image: $filePath, exists: ${file.exists()}")

                if (file.exists() && file.length() > 0) {
                    // Decode bitmap on background thread
                    val bitmap = BitmapFactory.decodeFile(filePath)
                    android.util.Log.d("SequenceCardAdapter", "Bitmap decoded: ${bitmap?.let { "${it.width}x${it.height}" } ?: "null"}")

                    if (bitmap != null) {
                        // Switch to main thread to update UI
                        withContext(Dispatchers.Main) {
                            imageView.setImageBitmap(bitmap)
                            android.util.Log.d("SequenceCardAdapter", "Image set successfully")
                        }
                    } else {
                        android.util.Log.w("SequenceCardAdapter", "Bitmap decode failed")
                    }
                } else {
                    android.util.Log.w("SequenceCardAdapter", "File does not exist or is empty: $filePath")
                }
            } catch (e: Exception) {
                android.util.Log.e("SequenceCardAdapter", "Exception loading image: ${e.message}")
            }
        }
    }
}

