/*
 * Copyright 2017-2022 Jiangdg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jiangdg.ausbc.encode.muxer

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateUtils
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.utils.Logger
import com.jiangdg.ausbc.utils.MediaUtils
import com.jiangdg.ausbc.utils.Utils
import java.io.File
import java.lang.Exception
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

/**
 * MediaMuxer for Mp4
 *
 * @property path mp4 saving path
 * @property durationInSec mp4 file auto divided in seconds
 *
 * @constructor
 * @param context context
 * @param callBack mp4 capture status, see [ICaptureCallBack]
 *
 * @author Created by jiangdg on 2022/2/10
 */
class Mp4Muxer(
    context: Context?,
    callBack: ICaptureCallBack,
    private var path: String? = null,
    private val durationInSec: Long = 0,
    private val isVideoOnly: Boolean = false
) {
    private var mContext: Context? = null
    private var mMediaMuxer: MediaMuxer? = null
    private var mFileSubIndex: Int = 0
    private var mVideoTrackerIndex = -1
    private var mAudioTrackerIndex = -1
    private var mVideoFormat: MediaFormat? = null
    private var mAudioFormat: MediaFormat? = null
    private var mBeginMillis: Long = 0
    private var mCaptureCallBack: ICaptureCallBack? = null
    private var mMainHandler: Handler = Handler(Looper.getMainLooper())
    private var mOriginalPath: String? = null
    private var mVideoPts: Long = 0L
    private var mAudioPts: Long = 0L
    private val mDateFormat by lazy {
        SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.getDefault())
    }
    private val mCameraDir by lazy {
        "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/Camera"
    }

    init {
        this.mCaptureCallBack = callBack
        this.mContext= context
        try {
            if (path.isNullOrEmpty()) {
                val date = mDateFormat.format(System.currentTimeMillis())
                path = "$mCameraDir/VID_JJCamera_$date"
            }
            mOriginalPath = path
            path = "${path}.mp4"
            mMediaMuxer = MediaMuxer(path!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            mCaptureCallBack?.onError(e.localizedMessage)
            Logger.e(TAG, "init media muxer failed, err = ${e.localizedMessage}", e)
        }
    }

    /**
     * Add tracker
     *
     * @param mediaFormat media format, see [MediaFormat]
     * @param isVideo media type, audio or video
     */
    @Synchronized
    fun addTracker(mediaFormat: MediaFormat?, isVideo: Boolean) {
        if (isMuxerStarter() || mediaFormat == null) {
            return
        }
        try {
            mMediaMuxer?.apply {
                val tracker = addTrack(mediaFormat)
                if (isVideo) {
                    mVideoFormat = mediaFormat
                    mVideoTrackerIndex = tracker
                    if (mAudioTrackerIndex != -1 || isVideoOnly) {
                        start()
                        mMainHandler.post {
                            mCaptureCallBack?.onBegin()
                        }
                        mBeginMillis = System.currentTimeMillis()
                        if (Utils.debugCamera) {
                            Logger.i(TAG, "start media muxer")
                        }
                    }
                } else {
                    mAudioFormat = mediaFormat
                    mAudioTrackerIndex = tracker
                    if (mVideoTrackerIndex != -1) {
                        start()
                        mMainHandler.post {
                            mCaptureCallBack?.onBegin()
                        }
                        mBeginMillis = System.currentTimeMillis()
                        if (Utils.debugCamera) {
                            Logger.i(TAG, "start media muxer")
                        }
                    }
                }
                if (Utils.debugCamera) {
                    Logger.i(TAG, "addTracker index = $tracker isVideo = $isVideo")
                }
            }
        } catch (e: Exception) {
            release()
            mMainHandler.post {
                mCaptureCallBack?.onError(e.localizedMessage)
            }
            Logger.e(TAG, "addTracker failed, err = ${e.localizedMessage}", e)
        }
    }

    /**
     * write audio(aac) or video(h264) data to media muxer
     *
     * @param outputBuffer encode output buffer, see [MediaCodec]
     * @param bufferInfo encode output buffer info, see [MediaCodec.BufferInfo]
     * @param isVideo media data type, audio or video
     */
    @Synchronized
    fun pumpStream(outputBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo, isVideo: Boolean) {
        try {
            Logger.d(TAG, "pumpStream called - isVideo: $isVideo, size: ${bufferInfo.size}, offset: ${bufferInfo.offset}")
            
            if (!isMuxerStarter()) {
                Logger.w(TAG, "Muxer not started, skipping pumpStream")
                return
            }
            if (bufferInfo.size <= 0) {
                Logger.w(TAG, "Buffer size is 0 or negative, skipping pumpStream")
                return
            }
            
            // Create a copy of bufferInfo to avoid modifying the original
            val adjustedBufferInfo = MediaCodec.BufferInfo()
            adjustedBufferInfo.set(bufferInfo.offset, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
            
            val index = if (isVideo) {
                if (mVideoPts == 0L) {
                    mVideoPts = bufferInfo.presentationTimeUs
                    Logger.d(TAG, "Set initial video PTS: $mVideoPts")
                }
                adjustedBufferInfo.presentationTimeUs = bufferInfo.presentationTimeUs - mVideoPts
                Logger.d(TAG, "Video PTS adjusted: ${bufferInfo.presentationTimeUs} -> ${adjustedBufferInfo.presentationTimeUs}")
                mVideoTrackerIndex
            } else {
                if (mAudioPts == 0L) {
                    mAudioPts = bufferInfo.presentationTimeUs
                    Logger.d(TAG, "Set initial audio PTS: $mAudioPts")
                }
                adjustedBufferInfo.presentationTimeUs = bufferInfo.presentationTimeUs - mAudioPts
                Logger.d(TAG, "Audio PTS adjusted: ${bufferInfo.presentationTimeUs} -> ${adjustedBufferInfo.presentationTimeUs}")
                mAudioTrackerIndex
            }
            
            Logger.d(TAG, "About to call writeSampleData - index: $index, buffer capacity: ${outputBuffer.capacity()}, position: ${outputBuffer.position()}, limit: ${outputBuffer.limit()}")
            
            // ByteBuffer is already positioned correctly from AbstractProcessor
            mMediaMuxer?.writeSampleData(index, outputBuffer, adjustedBufferInfo)
            Logger.d(TAG, "writeSampleData completed successfully")
            
            saveNewFileIfNeed()
        } catch (e: Exception) {
            Logger.e(TAG, "pumpStream failed, err = ${e.localizedMessage}", e)
            Logger.e(TAG, "Exception details: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
        }
    }

    private fun saveNewFileIfNeed() {
        try {
            val endMillis = System.currentTimeMillis()
            if (durationInSec == 0L) {
                return
            }
            if (endMillis - mBeginMillis <= durationInSec * 1000) {
                return
            }

            mMediaMuxer?.stop()
            mMediaMuxer?.release()
            mMediaMuxer = null
            mAudioTrackerIndex = -1
            mVideoTrackerIndex = -1
            mAudioPts = 0L
            mVideoPts = 0L
            insertDCIM(mContext, path)

            path = "${mOriginalPath}_${++mFileSubIndex}.mp4"
            mMediaMuxer = MediaMuxer(path!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            addTracker(mVideoFormat, true)
            addTracker(mAudioFormat, false)
        } catch (e: Exception) {
            mMainHandler.post {
                mCaptureCallBack?.onError(e.localizedMessage)
            }
            Logger.e(TAG, "release media muxer failed, err = ${e.localizedMessage}", e)
        }
    }

    /**
     * Release mp4 muxer resource
     */
    @Synchronized
    fun release() {
        try {
            mMediaMuxer?.stop()
            mMediaMuxer?.release()
            insertDCIM(mContext, path, true)
        } catch (e: Exception) {
            mMainHandler.post {
                mCaptureCallBack?.onError(e.localizedMessage)
            }
            Logger.e(TAG, "release media muxer failed, err = ${e.localizedMessage}", e)
        } finally {
            mMediaMuxer = null
            mAudioTrackerIndex = -1
            mVideoTrackerIndex = -1
            mAudioPts = 0L
            mVideoPts = 0L
        }
    }

    fun getSavePath() = path

    private fun insertDCIM(context: Context?, videoPath: String?, notifyOut: Boolean = false) {
        context?.let { ctx ->
            if (videoPath.isNullOrEmpty()) {
                Logger.w(TAG, "insertDCIM: videoPath is null or empty")
                return
            }
            
            val file = File(videoPath)
            if (!file.exists()) {
                Logger.w(TAG, "insertDCIM: video file does not exist: $videoPath")
                return
            }
            
            if (file.length() == 0L) {
                Logger.w(TAG, "insertDCIM: video file is empty: $videoPath")
                return
            }
            
            Logger.d(TAG, "insertDCIM: Attempting to insert video file: $videoPath, size: ${file.length()}")
            
            ctx.contentResolver.let { content ->
                try {
                    val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    val contentValues = getVideoContentValues(videoPath)
                    Logger.d(TAG, "insertDCIM: ContentValues created successfully")
                    
                    // For Android Q+, we need to handle the file differently
                    if (MediaUtils.isAboveQ()) {
                        Logger.d(TAG, "insertDCIM: Using Android Q+ approach")
                        // Insert the media entry first
                        val insertedUri = content.insert(uri, contentValues)
                        Logger.d(TAG, "insertDCIM: Video entry created with URI: $insertedUri")
                        
                        if (insertedUri != null) {
                            // Copy the file to the MediaStore location
                            try {
                                val outputStream = content.openOutputStream(insertedUri)
                                val inputStream = file.inputStream()
                                outputStream?.use { out ->
                                    inputStream.use { input ->
                                        input.copyTo(out)
                                    }
                                }
                                Logger.d(TAG, "insertDCIM: File copied to MediaStore location successfully")
                            } catch (e: Exception) {
                                Logger.e(TAG, "insertDCIM: Failed to copy file to MediaStore location: ${e.message}", e)
                                // Delete the media entry if file copy failed
                                try {
                                    content.delete(insertedUri, null, null)
                                } catch (deleteException: Exception) {
                                    Logger.e(TAG, "insertDCIM: Failed to delete media entry: ${deleteException.message}")
                                }
                                throw e
                            }
                        }
                    } else {
                        // For Android P and below, use the traditional approach
                        Logger.d(TAG, "insertDCIM: Using pre-Android Q approach")
                        val insertedUri = content.insert(uri, contentValues)
                        Logger.d(TAG, "insertDCIM: Video inserted successfully with URI: $insertedUri")
                    }
                    
                    mMainHandler.post {
                        mCaptureCallBack?.onComplete(this.path)
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "insertDCIM failed: ${e.message}", e)
                    mMainHandler.post {
                        mCaptureCallBack?.onError("Failed to save video: ${e.message}")
                    }
                }
            }
        }
    }

    private fun getVideoContentValues(path: String): ContentValues {
        val file = File(path)
        val values = ContentValues()
        
        Logger.d(TAG, "getVideoContentValues: Creating ContentValues for path: $path")
        Logger.d(TAG, "getVideoContentValues: File exists: ${file.exists()}, size: ${file.length()}")
        
        // For Android Q+ (API 29+), we should not use DATA field with direct file paths
        // Instead, we use RELATIVE_PATH and let the system handle the file
        if (MediaUtils.isAboveQ()) {
            val relativePath =  "${Environment.DIRECTORY_DCIM}${File.separator}Camera"
            val dateExpires = (System.currentTimeMillis() + DateUtils.DAY_IN_MILLIS) / 1000
            Logger.d(TAG, "getVideoContentValues: Android Q+ - relativePath: $relativePath, dateExpires: $dateExpires")
            values.put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            values.put(MediaStore.Video.Media.DATE_EXPIRES, dateExpires)
        } else {
            // For Android P and below, we can still use DATA field
            values.put(MediaStore.Video.Media.DATA, path)
        }
        
        values.put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        values.put(MediaStore.Video.Media.SIZE, file.length())
        
        val duration = getLocalVideoDuration(file.path)
        Logger.d(TAG, "getVideoContentValues: Video duration: $duration")
        values.put(MediaStore.Video.Media.DURATION, duration)
        
        Logger.d(TAG, "getVideoContentValues: ContentValues created with ${values.size()} entries")
        return values
    }


    fun isMuxerStarter() = mVideoTrackerIndex != -1 && (mAudioTrackerIndex != -1 || isVideoOnly)

    private fun getLocalVideoDuration(filePath: String?): Long {
        return try {
            if (filePath.isNullOrEmpty()) {
                Logger.w(TAG, "getLocalVideoDuration: filePath is null or empty")
                return 0L
            }
            
            val file = File(filePath)
            if (!file.exists()) {
                Logger.w(TAG, "getLocalVideoDuration: file does not exist: $filePath")
                return 0L
            }
            
            Logger.d(TAG, "getLocalVideoDuration: Getting duration for: $filePath")
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(filePath)
            val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            Logger.d(TAG, "getLocalVideoDuration: Duration extracted: $duration")
            mmr.release()
            duration
        } catch (e: Exception) {
            Logger.e(TAG, "getLocalVideoDuration failed: ${e.message}", e)
            0L
        }
    }

    companion object {
        private const val TAG = "Mp4Muxer"
    }
}