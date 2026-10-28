package com.gabriel.android.ui.gabriel

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabriel.android.location.GabrielLocationManager
import com.gabriel.android.location.GpsFixQuality
import com.gabriel.android.sos.*
import java.text.SimpleDateFormat
import java.util.*

private fun getBatteryPercentage(context: Context): Int? {
    return try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) (level * 100) / scale else null
    } catch (_: Exception) {
        null
    }
}

@Composable
fun SosScreen(
    onNavigateToMap: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSection by remember { mutableStateOf(0) } // 0 = Broadcast, 1 = Received
    val receivedAlerts by SosManager.received.collectAsState()
    val activeCount = receivedAlerts.count { it.payload.status == WireStatus.ACTIVE }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedSection,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(
                selected = selectedSection == 0,
                onClick = { selectedSection = 0 },
                text = { Text("Emergency SOS", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF453A)) }
            )
            Tab(
                selected = selectedSection == 1,
                onClick = { selectedSection = 1 },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Received Alerts", fontWeight = FontWeight.Bold)
                        if (activeCount > 0) {
                            Badge(containerColor = Color(0xFFFF453A)) {
                                Text("$activeCount", color = Color.White)
                            }
                        }
                    }
                },
                icon = { Icon(Icons.Default.Notifications, contentDescription = null) }
            )
        }

        if (selectedSection == 0) {
            SosSenderView()
        } else {
            SosReceiverView(
                receivedAlerts = receivedAlerts,
                onNavigateToMap = onNavigateToMap
            )
        }
    }
}

@Composable
fun SosSenderView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ownSos by SosManager.ownSos.collectAsState()
    val locationManager = remember(context) { GabrielLocationManager.getInstance(context) }
    val locationState by locationManager.state.collectAsState()

    var selectedType by remember { mutableStateOf(EmergencyType.LOST) }
    var messageText by remember { mutableStateOf("") }
    var showWarningDialog by remember { mutableStateOf(false) }

    val batteryPct = remember { getBatteryPercentage(context) }
    val messageBytes = remember(messageText) { messageText.toByteArray(Charsets.UTF_8).size }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (ownSos != null && ownSos?.status != SosStatus.CANCELLED && ownSos?.status != SosStatus.RESOLVED) {
            item {
                ActiveSosCard(ownSos = ownSos!!)
            }
        } else {
            item {
                Text(
                    text = "Broadcast Emergency Beacon",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Broadcasts a signed emergency signal over offline Bluetooth mesh. No internet required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Emergency Type Picker
            item {
                Text(
                    text = "Select Emergency Type",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val types = listOf(
                        Pair(EmergencyType.LOST, "Lost / Stranded"),
                        Pair(EmergencyType.MEDICAL, "Medical Emergency"),
                        Pair(EmergencyType.INJURY, "Accident / Injury"),
                        Pair(EmergencyType.DANGER, "Immediate Danger / Threat"),
                        Pair(EmergencyType.OTHER, "Other Urgent Need")
                    )
                    types.forEach { (type, label) ->
                        val isSelected = selectedType == type
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedType = type },
                            color = if (isSelected) Color(0xFF3B1818) else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFFF453A)) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedType = type },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF453A))
                                )
                                Text(
                                    text = label,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color(0xFFFF6961) else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // Attached GPS Preview
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Attached GPS Fix", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            val (badgeColor, badgeText) = when (locationState.fixQuality) {
                                GpsFixQuality.GOOD -> Pair(Color(0xFF34C759), "GOOD")
                                GpsFixQuality.INACCURATE -> Pair(Color(0xFFFFD60A), "INACCURATE")
                                GpsFixQuality.STALE -> Pair(Color(0xFFFF9F0A), "STALE")
                                GpsFixQuality.NO_FIX -> Pair(Color(0xFF8E8E93), "NO FIX")
                                else -> Pair(Color(0xFFFF453A), "DISABLED")
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = badgeColor.copy(alpha = 0.2f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor)
                            ) {
                                Text(
                                    text = badgeText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.SansSerif,
                                    color = badgeColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (locationState.latitude != null && locationState.longitude != null) {
                            Text(
                                text = "Coordinates: ${"%.5f".format(locationState.latitude)}, ${"%.5f".format(locationState.longitude)}",
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 12.sp
                            )
                            val acc = locationState.accuracyM?.let { "±${it.toInt()}m" } ?: "unknown"
                            val age = "${locationState.fixAgeSeconds}s ago"
                            Text(
                                text = "Horizontal Accuracy: $acc | Age: $age",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "No live GPS fix available. Alert will be sent without coordinates or with last known fix.",
                                fontSize = 11.sp,
                                color = Color(0xFFFF9F0A)
                            )
                        }

                        if (batteryPct != null) {
                            Text(
                                text = "Battery: $batteryPct%",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Message Input
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= 140) messageText = it },
                        label = { Text("Emergency Note (Optional, max 140 bytes)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        supportingText = {
                            Text(
                                text = "$messageBytes / 140 bytes",
                                fontFamily = FontFamily.SansSerif,
                                modifier = Modifier.fillMaxWidth(),
                                color = if (messageBytes > 130) Color(0xFFFF453A) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }

            // Broadcast Action Button
            item {
                Button(
                    onClick = { showWarningDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "BROADCAST EMERGENCY SOS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }
            }

            // Public Warning text
            item {
                Text(
                    text = "SOS is a PUBLIC broadcast signed by your peer identity. Anyone with a compatible Bluetooth device in range can read your location and message. GABRIEL provides no guarantee of rescue.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp
                )
            }
        }
    }

    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF453A))
                    Text("Confirm Public SOS Broadcast", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. PUBLIC BEACON: Your GPS coordinates, emergency type, message, and battery status will be broadcast openly without encryption to all nearby devices in BLE range.")
                    Text("2. NO GUARANTEE: GABRIEL is an offline peer mesh tool. It does NOT automatically alert police, search & rescue, or 911/112 unless a nearby person receives your alert and reports it.")
                    Text("3. TRUTHFUL CONFIRMATION: Delivery is only confirmed if a peer device sends a signed receipt back to you.")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWarningDialog = false
                        val fix = locationManager.getCurrentSosFix()
                        SosManager.startSos(
                            type = selectedType,
                            message = messageText,
                            fix = fix,
                            batteryPct = batteryPct
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A))
                ) {
                    Text("YES, BROADCAST SOS", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarningDialog = false }) {
                    Text("CANCEL")
                }
            }
        )
    }
}

@Composable
fun ActiveSosCard(ownSos: OwnSosView, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B1010)),
        border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFF453A))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF453A))
                    )
                    Text(
                        text = "SOS BEACON ACTIVE",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF6961),
                        fontSize = 16.sp
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFFFF453A).copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF453A))
                ) {
                    Text(
                        text = ownSos.status.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        color = Color(0xFFFF453A),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "Type: ${ownSos.payload.type.name.replace('_', ' ')}",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Color.White
            )

            if (ownSos.payload.message.isNotBlank()) {
                Text(
                    text = "\"${ownSos.payload.message}\"",
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }

            if (ownSos.payload.hasLocation) {
                val lat = ownSos.payload.latitude ?: 0.0
                val lon = ownSos.payload.longitude ?: 0.0
                Text(
                    text = "Coordinates: ${"%.5f".format(lat)}, ${"%.5f".format(lon)}",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }

            Text(
                text = "Transmissions: ${ownSos.transmissions} (rebroadcasting every 30s)",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.7f)
            )

            if (ownSos.ackedBy != null) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF1E3A2F),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF34C759)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF34C759), modifier = Modifier.size(16.dp))
                        Text(
                            text = "ACKNOWLEDGED by peer ${ownSos.ackedBy?.take(8)}...",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF34C759)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { SosManager.endSos(resolved = true) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("I'M SAFE / RESOLVE", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = { SosManager.endSos(resolved = false) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("CANCEL SOS", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun SosReceiverView(
    receivedAlerts: List<ReceivedSosView>,
    onNavigateToMap: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    if (receivedAlerts.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color(0xFF34C759))
                Text("No Emergency Alerts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "No active SOS beacons received in range. Bounded memory stores up to 50 events for 24 hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF0A84FF), modifier = Modifier.size(16.dp))
                        Text(
                            text = "Acknowledgements are signed by your local device. They confirm reception but do not guarantee official rescue services.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(receivedAlerts) { alert ->
                ReceivedSosCard(alert = alert, onNavigateToMap = onNavigateToMap)
            }
        }
    }
}

@Composable
fun ReceivedSosCard(
    alert: ReceivedSosView,
    onNavigateToMap: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = alert.payload.status == WireStatus.ACTIVE
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFF261212) else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isActive) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFFF453A)) else null
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isActive) Color(0xFFFF453A) else Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = alert.payload.type.name.replace('_', ' '),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (isActive) Color(0xFFFF6961) else MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = (if (isActive) Color(0xFFFF453A) else Color.Gray).copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isActive) Color(0xFFFF453A) else Color.Gray)
                ) {
                    Text(
                        text = alert.payload.status.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        color = if (isActive) Color(0xFFFF453A) else Color.Gray,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "From Peer: ${alert.originPeerID.take(12)}... | Received: ${timeFormat.format(Date(alert.receivedAtMs))}",
                fontSize = 11.sp,
                fontFamily = FontFamily.SansSerif,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (alert.payload.message.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = alert.payload.message,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            if (alert.payload.hasLocation) {
                val lat = alert.payload.latitude ?: 0.0
                val lon = alert.payload.longitude ?: 0.0
                val acc = alert.payload.accuracyM?.let { "±${it}m" } ?: "unknown"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "GPS: ${"%.5f".format(lat)}, ${"%.5f".format(lon)} ($acc)",
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { onNavigateToMap(lat, lon) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("View on Map", fontSize = 11.sp)
                    }
                }
            }

            if (alert.payload.batteryPct != null) {
                Text(
                    text = "Sender Battery: ${alert.payload.batteryPct}%",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Acknowledge Action
            if (isActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (alert.acknowledgedByMe) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF1E3A2F),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF34C759))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF34C759), modifier = Modifier.size(14.dp))
                                Text("ACKNOWLEDGED", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF34C759))
                            }
                        }
                    } else {
                        Button(
                            onClick = { SosManager.acknowledge(alert.key) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ACKNOWLEDGE", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
