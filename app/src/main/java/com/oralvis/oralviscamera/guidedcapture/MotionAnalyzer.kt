package com.oralvis.oralviscamera.guidedcapture

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Placeholder MotionAnalyzer for Android.
 *
 * This class is wired like the Python GuidanceSystem "_motion_worker" but does
 * NOT yet perform heavy optical-flow work. Instead it:
 *  - Accepts frame "ticks" (we don't care about pixels yet)
 *  - Emits a neutral MotionState (mu=0, sigma=0, no warnings)
 *  - Provides the correct threading and callback structure so that
 *    AutoCaptureController and GuidedSessionController can be fully integrated.
 *
 * Once ready, this class will be extended to perform real optical-flow analysis
 * on incoming frames, mirroring the AutoCapture/main.py behavior.
 */
class MotionAnalyzer {

    /**
     * Represents a logical "frame" tick. For now we do not store the pixel data;
     * when the optical-flow pipeline is implemented, this will carry the ROI /
     * grayscale buffer.
     */
    class FrameTick

    var onMotionStateUpdated: ((MotionState) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.Default)
    private var workerJob: Job? = null

    // Single-element channel to hold the latest frame tick (drop older ones)
    private val frameChannel = Channel<FrameTick>(capacity = Channel.CONFLATED)

    fun start() {
        if (workerJob != null) return
        workerJob = scope.launch {
            // In the future, initialize optical-flow state here.
            while (true) {
                val tick = frameChannel.receive()
                // For now, emit a neutral MotionState to drive the rest of the pipeline.
                onMotionStateUpdated?.invoke(
                    MotionState(
                        mu = 0.0,
                        sigma = 0.0,
                        speedWarning = false,
                        stabilityWarning = false
                    )
                )
                yield()
            }
        }
    }

    fun stop() {
        workerJob?.cancel()
        workerJob = null
        frameChannel.trySend(FrameTick()) // unblock if needed
    }

    /**
     * Enqueue a frame tick from the camera pipeline.
     * Pixel-level handling will be added when optical-flow is implemented.
     */
    fun onFrame() {
        frameChannel.trySend(FrameTick())
    }
}


