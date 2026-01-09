package com.oralvis.oralviscamera.guidedcapture

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.oralvis.oralviscamera.R

/**
 * Lightweight audio manager for guided capture spoken prompts.
 *
 * Mirrors the Python AutoCapture audio sequence:
 *  - READY_TO_SCAN_LOWER  -> Ins1
 *  - SCANNING_LOWER       -> Ins2
 *  - READY_TO_SCAN_UPPER  -> Ins3
 *  - SCANNING_UPPER       -> Ins4
 *  - COMPLETE             -> Ins5
 *
 * This implementation uses SoundPool for low-latency playback and is designed
 * to play at most one prompt at a time. Audio is purely advisory; no logic
 * depends on it.
 */
class GuidedAudioManager(context: Context) {

    private val soundPool: SoundPool
    private val soundMap: MutableMap<ScanningState, Int> = mutableMapOf()

    // Track loading state so we don't try to play before sounds are ready.
    private val loadedSoundIds = mutableSetOf<Int>()
    private var expectedSoundCount: Int = 0
    private var allLoaded: Boolean = false
    private var pendingStateToPlay: ScanningState? = null

    private var lastPlayedState: ScanningState? = null

    init {
        android.util.Log.d("GuidedAudio", "Initializing GuidedAudioManager")

        val attrs = AudioAttributes.Builder()
            // Use media usage so prompts play through the main media stream
            // and respect the device media volume setting.
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(attrs)
            .build()

        // IMPORTANT: register the load-complete listener BEFORE calling load(),
        // otherwise we can miss callbacks on fast devices and never mark sounds
        // as loaded (which would block all playback).
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) {
                android.util.Log.e("GuidedAudio", "Failed to load soundId=$sampleId, status=$status - AUDIO WILL NOT WORK")
                return@setOnLoadCompleteListener
            }

            loadedSoundIds.add(sampleId)
            android.util.Log.d(
                "GuidedAudio",
                "Loaded soundId=$sampleId (${loadedSoundIds.size}/${expectedSoundCount})"
            )

            // Once all expected sounds are loaded, mark the pool as ready and
            // play any deferred state prompt.
            if (!allLoaded && expectedSoundCount > 0 && loadedSoundIds.size == expectedSoundCount) {
                allLoaded = true
                android.util.Log.d("GuidedAudio", "All guided audio prompts loaded - READY TO PLAY")
                pendingStateToPlay?.let {
                    android.util.Log.d("GuidedAudio", "Playing deferred state: $it")
                    safePlay(it)
                }
                pendingStateToPlay = null
            }
        }

        // These raw resources are small placeholder clips; they can be replaced
        // with proper voice prompts without changing code.
        val ins1 = soundPool.load(context, R.raw.ins1_place_lower, 1)
        val ins2 = soundPool.load(context, R.raw.ins2_move_lower, 1)
        val ins3 = soundPool.load(context, R.raw.ins3_place_upper, 1)
        val ins4 = soundPool.load(context, R.raw.ins4_move_upper, 1)
        val ins5 = soundPool.load(context, R.raw.ins5_complete, 1)

        expectedSoundCount = 5

        soundMap[ScanningState.READY_TO_SCAN_LOWER] = ins1
        soundMap[ScanningState.SCANNING_LOWER] = ins2
        soundMap[ScanningState.READY_TO_SCAN_UPPER] = ins3
        soundMap[ScanningState.SCANNING_UPPER] = ins4
        soundMap[ScanningState.COMPLETE] = ins5

        android.util.Log.d(
            "GuidedAudio",
            "Queued guided audio loads: " +
                "READY_TO_SCAN_LOWER=$ins1, " +
                "SCANNING_LOWER=$ins2, " +
                "READY_TO_SCAN_UPPER=$ins3, " +
                "SCANNING_UPPER=$ins4, " +
                "COMPLETE=$ins5"
        )
    }

    /**
     * Called whenever the guided state machine transitions. Plays the
     * corresponding prompt exactly once per transition.
     */
    fun onStateChanged(state: ScanningState) {
        // Avoid replaying the same prompt if notifyUi is called redundantly.
        if (state == lastPlayedState) return
        lastPlayedState = state

        if (!allLoaded) {
            // Remember the latest requested state; once all sounds are ready,
            // we'll play the most relevant prompt.
            android.util.Log.d("GuidedAudio", "Deferring audio for state=$state until sounds are fully loaded")
            pendingStateToPlay = state
            return
        }

        safePlay(state)
    }

    private fun safePlay(state: ScanningState) {
        val soundId = soundMap[state] ?: run {
            android.util.Log.w("GuidedAudio", "No soundId mapped for state=$state")
            return
        }
        if (!loadedSoundIds.contains(soundId)) {
            android.util.Log.w("GuidedAudio", "Requested play for state=$state but soundId=$soundId not fully loaded yet")
            return
        }

        android.util.Log.d("GuidedAudio", "Playing audio for state=$state, soundId=$soundId")
        val playResult = soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
        if (playResult == 0) {
            android.util.Log.e("GuidedAudio", "Failed to play sound for state=$state, soundId=$soundId")
        } else {
            android.util.Log.d("GuidedAudio", "Successfully started playing sound, streamId=$playResult")
        }
    }

    fun release() {
        android.util.Log.d("GuidedAudio", "Releasing SoundPool")
        soundPool.release()
    }
}


