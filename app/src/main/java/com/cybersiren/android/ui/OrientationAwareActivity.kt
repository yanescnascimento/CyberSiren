package com.cybersiren.android.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.cybersiren.android.utils.DeviceUtils
import com.cybersiren.android.v2v.ui.V2VLocalePrefs

abstract class OrientationAwareActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(V2VLocalePrefs.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setOrientationBasedOnDeviceType()
    }

    private fun setOrientationBasedOnDeviceType() {
        requestedOrientation = if (DeviceUtils.isTablet(this)) {

            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {

            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}
