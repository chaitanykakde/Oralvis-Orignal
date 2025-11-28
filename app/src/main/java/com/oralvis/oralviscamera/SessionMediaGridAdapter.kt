package com.oralvis.oralviscamera

import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.oralvis.oralviscamera.database.MediaRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SessionMediaGridAdapter(
    private val mediaList: List<MediaRecord>,
    private val onItemClick: (MediaRecord) -> Unit
) : RecyclerView.Adapter<SessionMediaGridAdapter.MediaViewHolder>() {
    
    inner class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgThumbnail: ImageView = view.findViewById(R.id.imgThumbnail)
        val imgPlayIcon: ImageView = view.findViewById(R.id.imgPlayIcon)
        val txtMediaType: TextView = view.findViewById(R.id.txtMediaType)
        
        init {
            view.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(mediaList[position])
                }
            }
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session_media_grid, parent, false)
        return MediaViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val media = mediaList[position]
        val file = File(media.filePath)
        
        if (file.exists()) {
            if (media.mediaType == "Video") {
                // Load video thumbnail
                val thumbnail = ThumbnailUtils.createVideoThumbnail(
                    media.filePath,
                    MediaStore.Images.Thumbnails.MINI_KIND
                )
                holder.imgThumbnail.setImageBitmap(thumbnail)
                holder.imgPlayIcon.visibility = View.VISIBLE
                holder.txtMediaType.text = "Video"
            } else {
                // Load image
                val bitmap = BitmapFactory.decodeFile(media.filePath)
                holder.imgThumbnail.setImageBitmap(bitmap)
                holder.imgPlayIcon.visibility = View.GONE
                holder.txtMediaType.text = media.mode
            }
        } else {
            holder.imgThumbnail.setImageResource(R.drawable.ic_mode)
            holder.imgPlayIcon.visibility = View.GONE
            holder.txtMediaType.text = "Not found"
        }
    }
    
    override fun getItemCount(): Int = mediaList.size
}

