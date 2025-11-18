package com.oralvis.oralviscamera.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import com.oralvis.oralviscamera.database.MediaDatabase
import com.oralvis.oralviscamera.database.MediaDao

class GalleryViewModel(private val _mediaDao: MediaDao) : ViewModel() {
    
    // Expose mediaDao for debugging
    val mediaDao: MediaDao = _mediaDao
    
    fun getNormalMedia(sessionId: String) = 
        _mediaDao.getMediaBySessionAndMode(sessionId, "Normal").asLiveData()
    
    fun getFluorescenceMedia(sessionId: String) = 
        _mediaDao.getMediaBySessionAndMode(sessionId, "Fluorescence").asLiveData()
    
    suspend fun deleteMedia(mediaRecord: com.oralvis.oralviscamera.database.MediaRecord) {
        _mediaDao.deleteMedia(mediaRecord)
    }
    
    // Debug method to get all media
    fun getAllMedia() = _mediaDao.getAllMedia().asLiveData()
    
    // Get all media for a specific session (without mode filtering)
    fun getMediaBySession(sessionId: String) = _mediaDao.getMediaBySession(sessionId).asLiveData()
    
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
