package com.cybersiren.android.ui.events

object FileShareDispatcher {
    @Volatile private var handler: ((String?, String?, String) -> Unit)? = null

    fun setHandler(h: ((String?, String?, String) -> Unit)?) {
        handler = h
    }

    fun dispatch(peerIdOrNull: String?, channelOrNull: String?, path: String) {
        handler?.invoke(peerIdOrNull, channelOrNull, path)
    }
}
