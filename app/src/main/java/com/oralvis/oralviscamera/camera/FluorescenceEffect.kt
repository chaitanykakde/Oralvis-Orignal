package com.oralvis.oralviscamera.camera

import android.content.Context
import com.oralvis.ausbc.R
import com.jiangdg.ausbc.render.effect.AbstractEffect
import com.jiangdg.ausbc.render.effect.bean.CameraEffect

/**
 * Fluorescence effect that applies a blue tint to the camera preview.
 * This effect boosts the blue channel and reduces red/green channels
 * to create the characteristic fluorescence appearance.
 *
 * @author Created for OralVis Camera
 */
class FluorescenceEffect(ctx: Context) : AbstractEffect(ctx) {

    override fun getId(): Int = ID

    override fun getClassifyId(): Int = CameraEffect.CLASSIFY_ID_FILTER

    override fun getVertexSourceId(): Int = R.raw.base_vertex

    override fun getFragmentSourceId(): Int = R.raw.effect_fluorescence_fragment

    companion object {
        const val ID = 200  // Unique ID for fluorescence effect
    }
}

