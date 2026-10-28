package com.gabriel.android

import android.app.Application
import com.gabriel.android.nostr.RelayDirectory
import com.gabriel.android.ui.theme.ThemePreferenceManager
import com.gabriel.android.net.ArtiTorManager

/**
 * Main application class for gabriel Android
 */
class GabrielApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Start the single process-wide power policy before transport components are constructed.
        com.gabriel.android.mesh.PowerManager.getInstance(this).start()

        // Initialize GABRIEL Offline GPS platform LocationManager and PeerLocationManager
        com.gabriel.android.location.GabrielLocationManager.init(this)
        com.gabriel.android.location.PeerLocationManager.init(this)

        // Initialize MapLibre Native SDK early with application context
        try {
            org.maplibre.android.MapLibre.getInstance(this)
        } catch (e: Throwable) {
            android.util.Log.e("GabrielApplication", "Failed to initialize MapLibre", e)
        }

        // Tor and Nostr remote internet relays disabled for 100% offline GABRIEL operation

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            com.gabriel.android.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Restore private conversations before background transports can deliver new messages.
        // AppStateStore merges any in-flight arrivals by message ID, so startup cannot replace
        // newer transport state with an older database snapshot.
        try {
            com.gabriel.android.services.AppStateStore.initializeConversationPersistence(this)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            com.gabriel.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize chat UI mode (matrix transcript vs bubbles)
        com.gabriel.android.ui.theme.ChatUiModeManager.init(this)

        // Initialize debug preference manager (persists debug toggles)
        try { com.gabriel.android.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize Wi‑Fi Aware controller with persisted default
        try {
            val enabled = com.gabriel.android.ui.debug.DebugPreferenceManager.getWifiAwareEnabled(false)
            com.gabriel.android.wifiaware.WifiAwareController.initialize(this, enabled)
        } catch (_: Exception) { }

        // Initialize Geohash Registries for persistence
        try {
            com.gabriel.android.nostr.GeohashAliasRegistry.initialize(this)
            com.gabriel.android.nostr.GeohashConversationRegistry.initialize(this)
        } catch (_: Exception) { }

        // Nostr background internet runtime disabled for 100% offline GABRIEL operation

        // Initialize mesh service preferences
        try { com.gabriel.android.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        try { com.gabriel.android.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // TorManager already initialized above
    }
}
