package com.gabriel.watch

import android.app.Application
import com.gabriel.android.mesh.PowerManager
import com.gabriel.watch.notification.WearNotificationCoordinator
import com.gabriel.watch.ui.WearPeerIdentityState

class GabrielWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PowerManager.getInstance(applicationContext)
        WearNotificationCoordinator.getInstance(applicationContext)
        WearPeerIdentityState.initialize(applicationContext)
    }
}
