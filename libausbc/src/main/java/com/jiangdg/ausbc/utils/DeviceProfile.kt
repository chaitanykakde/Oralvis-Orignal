package com.jiangdg.ausbc.utils

import android.content.Context

/**
 * Centralised device profile helpers.
 *
 * Used to apply tablet‑specific performance adaptations without
 * changing behaviour on phones.
 */
object DeviceProfile {

    /**
     * Very simple heuristic: treat devices with smallest width >= 600dp
     * as tablets. This matches common Android guidance and keeps the
     * logic consistent across the app and libraries.
     */
    fun isTablet(context: Context): Boolean {
        return context.resources.configuration.smallestScreenWidthDp >= 600
    }
}


