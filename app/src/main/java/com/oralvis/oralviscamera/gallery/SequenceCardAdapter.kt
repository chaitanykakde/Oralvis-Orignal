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
import java.io.File

class SequenceCardAdapter(
    private var sequenceCards: List<SequenceCard>,
    private val onRgbImageClick: (SequenceCard) -> Unit,
    private val onFluorescenceImageClick: (SequenceCard) -> Unit,
    private val onDiscardClick: (SequenceCard) -> Unit
) : RecyclerView.Adapter<SequenceCardAdapter.SequenceViewHolder>() {
    
    fun updateSequenceCards(newCards: List<SequenceCard>) {
        sequenceCards = newCards
        notifyDataSetChanged()
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
        
        holder.sequenceTitle.text = card.getTitle()
        
        // Load RGB image
        if (card.rgbImage != null) {
            loadImage(holder.rgbImage, card.rgbImage.filePath)
            holder.rgbLabel.visibility = View.VISIBLE
            holder.rgbImage.setOnClickListener { onRgbImageClick(card) }
        } else {
            holder.rgbImage.setImageResource(R.drawable.ic_media_placeholder)
            holder.rgbLabel.visibility = View.GONE
            holder.rgbImage.setOnClickListener(null)
        }
        
        // Load Fluorescence image
        if (card.fluorescenceImage != null) {
            loadImage(holder.fluorescenceImage, card.fluorescenceImage.filePath)
            holder.fluorescenceLabel.visibility = View.VISIBLE
            holder.waitingForCapture.visibility = View.GONE
            holder.fluorescenceImage.setOnClickListener { onFluorescenceImageClick(card) }
        } else {
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
        try {
            val file = File(filePath)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(filePath)
                imageView.setImageBitmap(bitmap)
            } else {
                imageView.setImageResource(R.drawable.ic_media_placeholder)
            }
        } catch (e: Exception) {
            imageView.setImageResource(R.drawable.ic_media_placeholder)
        }
    }
}

