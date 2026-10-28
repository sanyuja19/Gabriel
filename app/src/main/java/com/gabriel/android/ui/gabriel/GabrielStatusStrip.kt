package com.gabriel.android.ui.gabriel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.gabriel.android.location.GabrielLocationManager
import com.gabriel.android.location.GpsFixQuality

import androidx.compose.ui.res.painterResource
import com.gabriel.android.R

@Composable
fun GabrielStatusStrip(
    activePeerCount: Int,
    isBluetoothEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val locationManager = remember(context) { GabrielLocationManager.getInstance(context) }
    val locationState by locationManager.state.collectAsState()

    val (gpsIcon, gpsText, gpsColor) = when (locationState.fixQuality) {
        GpsFixQuality.GOOD -> {
            val acc = locationState.accuracyM?.let { "±${it.toInt()}m" } ?: ""
            Triple(Icons.Default.GpsFixed, "GPS $acc", Color(0xFF2E7D32))
        }
        GpsFixQuality.INACCURATE -> {
            val acc = locationState.accuracyM?.let { "±${it.toInt()}m" } ?: ""
            Triple(Icons.Default.GpsFixed, "GPS $acc", Color(0xFFE0A100))
        }
        GpsFixQuality.STALE -> {
            val mins = (locationState.fixAgeSeconds / 60).coerceAtLeast(1)
            Triple(Icons.Default.GpsFixed, "GPS ${mins}m old", Color(0xFFE0A100))
        }
        GpsFixQuality.NO_FIX -> Triple(Icons.Default.GpsNotFixed, "Waiting for GPS fix", Color(0xFFE0A100))
        GpsFixQuality.GPS_DISABLED -> Triple(Icons.Default.GpsOff, "Location off", MaterialTheme.colorScheme.error)
        GpsFixQuality.NO_PERMISSION -> Triple(Icons.Default.GpsOff, "No permission", MaterialTheme.colorScheme.error)
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // GABRIEL Brand Logo & Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_gabriel_emblem),
                        contentDescription = "GABRIEL",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "GABRIEL",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF0F3E22))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(Icons.Default.SignalCellularAlt, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(10.dp))
                            Text(
                                text = "OFFLINE",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
                }

                // Right side stats: Peers & GPS (Explicit icons + labels, no color-only state)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Mesh peers
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = if (isBluetoothEnabled) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                            contentDescription = if (isBluetoothEnabled) "Bluetooth Active" else "Bluetooth Off",
                            tint = if (isBluetoothEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = if (isBluetoothEnabled) "$activePeerCount peers" else "BT Off",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // GPS Quality Pill with explicit icon and label
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = gpsIcon,
                            contentDescription = gpsText,
                            tint = gpsColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = gpsText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
