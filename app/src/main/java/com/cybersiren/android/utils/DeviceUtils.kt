package com.cybersiren.android.utils

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.getSystemService
import kotlin.math.sqrt

object DeviceUtils {

    fun isTablet(context: Context): Boolean {
        val windowManager = context.getSystemService<WindowManager>()
        val displayMetrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getMetrics(displayMetrics)

        val widthInches = displayMetrics.widthPixels / displayMetrics.xdpi
        val heightInches = displayMetrics.heightPixels / displayMetrics.ydpi
        val diagonalInches = sqrt((widthInches * widthInches) + (heightInches * heightInches))

        val configuration = context.resources.configuration
        val isLargeScreen = (configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE
        val isXLargeScreen = (configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) == Configuration.SCREENLAYOUT_SIZE_XLARGE

        val smallestWidthDp = context.resources.configuration.smallestScreenWidthDp

        return diagonalInches >= 7.0 || isLargeScreen || isXLargeScreen || smallestWidthDp >= 600
    }
}
