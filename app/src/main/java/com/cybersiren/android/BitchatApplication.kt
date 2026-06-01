package com.cybersiren.android

import android.app.Application
import android.content.Context
import com.cybersiren.android.nostr.RelayDirectory
import com.cybersiren.android.ui.theme.ThemePreferenceManager
import com.cybersiren.android.net.ArtiTorManager
import com.cybersiren.android.v2v.ui.V2VLocalePrefs

class BitchatApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(runCatching { V2VLocalePrefs.wrap(base) }.getOrDefault(base))
    }

    override fun onCreate() {
        super.onCreate()

        try { V2VLocalePrefs.applySavedLocale(this) } catch (_: Exception) { }

        try {
            val torProvider = ArtiTorManager.getInstance()
            torProvider.init(this)
        } catch (_: Exception){}

        RelayDirectory.initialize(this)

        try { com.cybersiren.android.nostr.LocationNotesInitializer.initialize(this) } catch (_: Exception) { }

        try {
            com.cybersiren.android.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        try {
            com.cybersiren.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        ThemePreferenceManager.init(this)

        try { com.cybersiren.android.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        try {
            com.cybersiren.android.nostr.GeohashAliasRegistry.initialize(this)
            com.cybersiren.android.nostr.GeohashConversationRegistry.initialize(this)
        } catch (_: Exception) { }

        try { com.cybersiren.android.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        try { com.cybersiren.android.service.MeshForegroundService.start(this) } catch (_: Exception) { }

    }
}
