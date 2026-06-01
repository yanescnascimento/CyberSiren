package com.cybersiren.android.v2v.auto

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.cybersiren.android.v2v.ui.V2VLocalePrefs

class V2VCarAppService : CarAppService() {

    override fun attachBaseContext(newBase: Context) {

        super.attachBaseContext(V2VLocalePrefs.wrap(newBase))
    }

    override fun createHostValidator(): HostValidator {

        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        }
    }

    override fun onCreateSession(): Session {
        return V2VCarSession()
    }
}

class V2VCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        Log.i(TAG, "onCreateScreen action=${intent.action} data=${intent.data}")
        if (isV2VAlertIntent(intent)) {
            return V2VReceiverScreen(carContext)
        }
        val mode = V2VCarServiceHolder.getService()?.getAlertMode()
        return if (mode == com.cybersiren.android.v2v.model.AlertMode.RECEIVER) {
            V2VReceiverScreen(carContext)
        } else {
            V2VHomeScreen(carContext)
        }
    }

    override fun onNewIntent(intent: Intent) {
        Log.i(TAG, "onNewIntent action=${intent.action} data=${intent.data}")
        if (isV2VAlertIntent(intent)) {
            val sm = carContext.getCarService(ScreenManager::class.java)
            sm.popToRoot()
            sm.push(V2VReceiverScreen(carContext))
        }
    }

    private fun isV2VAlertIntent(intent: Intent): Boolean {
        if (intent.action == V2VCarNotifier.ACTION_OPEN_RECEIVER) return true
        if (intent.action == CarContext.ACTION_NAVIGATE) {
            val data = intent.data

            if (data?.getQueryParameter("source") == "v2v_alert") return true
            if (data?.getQueryParameter("q") == "v2v_alert") return true
        }
        return false
    }

    companion object {
        private const val TAG = "V2VCarSession"
    }
}
