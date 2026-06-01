package com.cybersiren.android.ui

fun truncateNickname(name: String, maxLen: Int = com.cybersiren.android.util.AppConstants.UI.MAX_NICKNAME_LENGTH): String {
    return if (name.length <= maxLen) name else name.take(maxLen)
}
