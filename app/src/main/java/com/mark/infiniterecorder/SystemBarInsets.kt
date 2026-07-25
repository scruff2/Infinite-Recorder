package com.mark.infiniterecorder

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Android 15+ enforces edge-to-edge content for modern target SDKs. Keep the
 * app below status-bar icons, display cutouts, and the navigation area while
 * retaining edge-to-edge compatibility on older supported releases.
 */
fun applySystemBarInsets(root: View) {
    val initialLeft = root.paddingLeft
    val initialTop = root.paddingTop
    val initialRight = root.paddingRight
    val initialBottom = root.paddingBottom

    root.setOnApplyWindowInsetsListener { view, insets ->
        val top: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(
                WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars() or
                    WindowInsets.Type.displayCutout(),
            )
            top = bars.top
            bottom = bars.bottom
        } else {
            @Suppress("DEPRECATION")
            top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            bottom = insets.systemWindowInsetBottom
        }
        view.setPadding(
            initialLeft,
            initialTop + top,
            initialRight,
            initialBottom + bottom,
        )
        insets
    }
    root.requestApplyInsets()
}
