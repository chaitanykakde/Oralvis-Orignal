package com.oralvis.oralviscamera.gallery

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.oralvis.oralviscamera.R
import com.oralvis.oralviscamera.database.MediaRecordV2
import java.io.File

/**
 * Adapter for the Gallery "Other" tab: one item per image, no pairing container.
 * Shows a single image and a Discard button per row.
 */
class OtherMediaAdapter(
    private var items: List<MediaRecordV2>,
    private val onImageClick: (MediaRecordV2) -> Unit,
    private val onDiscardClick: (MediaRecordV2) -> Unit
) : RecyclerView.Adapter<OtherMediaAdapter.OtherMediaViewHolder>() {

    fun updateList(newItems: List<MediaRecordV2>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OtherMediaViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_other_media, parent, false)
        return OtherMediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: OtherMediaViewHolder, position: Int) {
        val media = items[position]
        val path = media.filePath
        if (!path.isNullOrBlank()) {
            loadImage(holder.imageView, path)
            holder.imageView.setOnClickListener { onImageClick(media) }
        } else {
            holder.imageView.setImageResource(R.drawable.ic_media_placeholder)
            holder.imageView.setOnClickListener(null)
        }
        holder.discardButton.setOnClickListener { onDiscardClick(media) }
    }

    override fun getItemCount(): Int = items.size

    private fun loadImage(imageView: ImageView, filePath: String) {
        try {
            val file = File(filePath)
            if (file.exists() && file.length() > 0) {
                val bitmap = BitmapFactory.decodeFile(filePath)
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                } else {
                    imageView.setImageResource(R.drawable.ic_media_placeholder)
                }
            } else {
                imageView.setImageResource(R.drawable.ic_media_placeholder)
            }
        } catch (e: Exception) {
            imageView.setImageResource(R.drawable.ic_media_placeholder)
        }
    }

    class OtherMediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.otherMediaImage)
        val discardButton: MaterialButton = view.findViewById(R.id.btnDiscard)
    }
}
