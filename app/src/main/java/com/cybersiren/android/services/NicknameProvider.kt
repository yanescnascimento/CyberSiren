package com.cybersiren.android.services

import android.content.Context
import com.cybersiren.android.ui.DataManager

object NicknameProvider {
    fun getNickname(context: Context, myPeerID: String): String {
        return try {
            val dm = DataManager(context.applicationContext)
            val nick = dm.loadNickname()
            if (nick.isNullOrBlank()) myPeerID else nick
        } catch (_: Exception) {
            myPeerID
        }
    }
}
