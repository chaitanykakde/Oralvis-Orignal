package com.oralvis.oralviscamera.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.MediaDao

class GalleryViewModel(private val mediaDao: MediaDao) : ViewModel() {
    
    fun getNormalMedia(sessionId: String) = 
        mediaDao.getMediaBySessionAndMode(sessionId, "Normal").asLiveData()
    
    fun getFluorescenceMedia(sessionId: String) = 
        mediaDao.getMediaBySessionAndMode(sessionId, "Fluorescence").asLiveData()
    
    suspend fun deleteMedia(mediaRecord: com.oralvis.oralviscamera.database.MediaRecord) {
        mediaDao.deleteMedia(mediaRecord)
    }
    
    companion object {
        fun createFactory(context: android.content.Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val database = MediaDatabase.getDatabase(context)
                    return GalleryViewModel(database.mediaDao()) as T
                }
            }
        }
    }
}
