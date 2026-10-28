package com.gabriel.android.ui.gabriel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabriel.android.mesh.BluetoothMeshService
import com.gabriel.android.mesh.MeshService
import com.gabriel.android.sos.SosManager
import com.gabriel.android.sos.WireStatus
import com.gabriel.android.ui.ChatScreen
import com.gabriel.android.ui.ChatViewModel

@Composable
fun GabrielMainShell(
    chatViewModel: ChatViewModel,
    meshService: BluetoothMeshService,
    unifiedMeshService: MeshService,
    isBluetoothEnabled: Boolean,
    onBackPressed: () -> Boolean,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(GabrielTab.CHAT) }
    var targetMapLat by remember { mutableStateOf<Double?>(null) }
    var targetMapLon by remember { mutableStateOf<Double?>(null) }

    val ownSos by SosManager.ownSos.collectAsState()
    val receivedAlerts by SosManager.received.collectAsState()
    val hasActiveReceived = receivedAlerts.any { it.payload.status == WireStatus.ACTIVE }
    val hasActiveOwn = ownSos?.status?.let { it.name == "CREATED" || it.name == "QUEUED" || it.name == "RELAYED" || it.name == "ACKNOWLEDGED" } ?: false
    val hasEmergency = hasActiveOwn || hasActiveReceived

    var peerCountTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            peerCountTick++
        }
    }
    val activePeerCount = remember(peerCountTick) {
        try {
            unifiedMeshService.getActivePeerCount()
        } catch (_: Exception) {
            0
        }
    }

    BackHandler(enabled = true) {
        if (selectedTab != GabrielTab.CHAT) {
            selectedTab = GabrielTab.CHAT
        } else {
            val handled = onBackPressed()
            if (!handled) {
                // Let system handle
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            GabrielStatusStrip(
                activePeerCount = activePeerCount,
                isBluetoothEnabled = isBluetoothEnabled
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                GabrielTab.values().forEach { tab ->
                    val isSelected = selectedTab == tab
                    val isSosTab = tab == GabrielTab.SOS
                    val isNearbyTab = tab == GabrielTab.NEARBY

                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedTab = tab },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (isSosTab && hasEmergency) {
                                        Badge(containerColor = Color(0xFFFF453A)) {
                                            Text("!", color = Color.White)
                                        }
                                    } else if (isNearbyTab && activePeerCount > 0) {
                                        Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                            Text("$activePeerCount")
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    tint = if (isSosTab && (hasEmergency || isSelected)) Color(0xFFFF453A)
                                           else if (isSelected) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        label = {
                            Text(
                                text = tab.label,
                                fontSize = 11.sp,
                                color = if (isSosTab && (hasEmergency || isSelected)) Color(0xFFFF453A)
                                        else if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = if (isSosTab) Color(0xFF3B1818) else MaterialTheme.colorScheme.secondaryContainer
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                GabrielTab.CHAT -> {
                    ChatScreen(viewModel = chatViewModel)
                }
                GabrielTab.SOS -> {
                    SosScreen(
                        onNavigateToMap = { lat, lon ->
                            targetMapLat = lat
                            targetMapLon = lon
                            selectedTab = GabrielTab.MAP
                        }
                    )
                }
                GabrielTab.MAP -> {
                    MapScreen(
                        targetLat = targetMapLat,
                        targetLon = targetMapLon,
                        onNavigateToSos = { selectedTab = GabrielTab.SOS }
                    )
                }
                GabrielTab.NEARBY -> {
                    NearbyScreen(
                        meshService = meshService,
                        unifiedMeshService = unifiedMeshService
                    )
                }
            }
        }
    }
}
