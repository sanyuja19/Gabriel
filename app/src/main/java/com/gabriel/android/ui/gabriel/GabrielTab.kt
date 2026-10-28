package com.gabriel.android.ui.gabriel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Chat
import androidx.compose.ui.graphics.vector.ImageVector

enum class GabrielTab(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.Default.Chat),
    SOS("SOS", Icons.Default.Warning),
    MAP("Map", Icons.Default.LocationOn),
    NEARBY("Nearby", Icons.Default.NearMe)
}
