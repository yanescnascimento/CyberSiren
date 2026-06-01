package com.cybersiren.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {

        try { MeshServicePreferences.init(context.applicationContext) } catch (_: Exception) { }

        if (MeshServicePreferences.isAutoStartEnabled(true)) {
            MeshForegroundService.start(context.applicationContext)
        }
    }
}
