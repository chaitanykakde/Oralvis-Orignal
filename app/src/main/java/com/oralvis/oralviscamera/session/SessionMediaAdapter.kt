package com.oralvis.oralviscamera.session

import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.oralvis.oralviscamera.databinding.ItemSessionThumbnailBinding
import java.io.File

data class SessionMedia(
    val id: Long,
    val filePath: String,
    val isVideo: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class SessionMediaAdapter(
    private val onMediaClick: (SessionMedia) -> Unit,
    private val onRemoveClick: (SessionMedia) -> Unit
) : ListAdapter<SessionMedia, SessionMediaAdapter.MediaViewHolder>(MediaDiffCallback()) {

    class MediaDiffCallback : DiffUtil.ItemCallback<SessionMedia>() {
        override fun areItemsTheSame(oldItem: SessionMedia, newItem: SessionMedia): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SessionMedia, newItem: SessionMedia): Boolean {
            return oldItem == newItem
        }
    }

    inner class MediaViewHolder(
        private val binding: ItemSessionThumbnailBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(media: SessionMedia) {
            // Load thumbnail
            loadThumbnail(media)

            // Show video indicator if it's a video
            binding.videoIndicator.visibility = if (media.isVideo) View.VISIBLE else View.GONE

            // Click on thumbnail to preview
            binding.thumbnailCard.setOnClickListener {
                onMediaClick(media)
            }

            // Remove button
            binding.btnRemove.setOnClickListener {
                onRemoveClick(media)
            }
        }

        private fun loadThumbnail(media: SessionMedia) {
            try {
                val file = File(media.filePath)
                if (!file.exists()) {
                    binding.thumbnailImage.setImageResource(com.oralvis.oralviscamera.R.drawable.ic_media_placeholder)
                    return
                }

                if (media.isVideo) {
                    // Load video thumbnail
                    val thumbnail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ThumbnailUtils.createVideoThumbnail(file, Size(200, 200), null)
                    } else {
                        @Suppress("DEPRECATION")
                        ThumbnailUtils.createVideoThumbnail(
                            media.filePath,
                            MediaStore.Video.Thumbnails.MINI_KIND
                        )
                    }
                    if (thumbnail != null) {
                        binding.thumbnailImage.setImageBitmap(thumbnail)
                    } else {
                        binding.thumbnailImage.setImageResource(com.oralvis.oralviscamera.R.drawable.ic_media_placeholder)
                    }
                } else {
                    // Load image thumbnail
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 4 // Scale down for thumbnail
                    }
                    val bitmap = BitmapFactory.decodeFile(media.filePath, options)
                    if (bitmap != null) {
                        binding.thumbnailImage.setImageBitmap(bitmap)
                    } else {
                        binding.thumbnailImage.setImageResource(com.oralvis.oralviscamera.R.drawable.ic_media_placeholder)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                binding.thumbnailImage.setImageResource(com.oralvis.oralviscamera.R.drawable.ic_media_placeholder)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding = ItemSessionThumbnailBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

