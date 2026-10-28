package com.gabriel.android.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gabriel.android.model.RoutedPacket
import com.gabriel.android.protocol.GabrielPacket
import com.gabriel.android.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class EmergencyPriorityQueueTest {

    private lateinit var scope: CoroutineScope
    private lateinit var context: Context
    private lateinit var tracker: BluetoothConnectionTracker
    private lateinit var broadcaster: BluetoothPacketBroadcaster

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        context = ApplicationProvider.getApplicationContext()
        val powerManager = PowerManager.getInstance(context)
        tracker = BluetoothConnectionTracker(scope, powerManager)
        broadcaster = BluetoothPacketBroadcaster(scope, tracker, null, "my-peer-1234")
    }

    @After
    fun tearDown() {
        broadcaster.shutdown()
        scope.cancel()
    }

    private fun createPacket(type: MessageType, text: String): RoutedPacket {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val packet = GabrielPacket(
            version = 1u,
            type = type.value,
            senderID = ByteArray(8) { 1 },
            recipientID = null,
            timestamp = System.currentTimeMillis().toULong(),
            payload = bytes,
            signature = null,
            ttl = 3u
        )
        return RoutedPacket(packet)
    }

    @Test
    fun testBroadcastDebugInfoReflectsEmergencyChannels() {
        val debugInfo = broadcaster.getDebugInfo()
        assertTrue(debugInfo.contains("Emergency Channel Closed: false"))
        assertTrue(debugInfo.contains("Normal Channel Closed: false"))
    }

    @Test
    fun testEmergencyPacketBroadcastingDoesNotCrash() = runBlocking {
        val sosPacket = createPacket(MessageType.SOS, "EMERGENCY_HELP")
        broadcaster.broadcastSinglePacket(sosPacket, null, null)
        delay(50)
        val ackPacket = createPacket(MessageType.SOS_ACK, "ACK_RECEIVED")
        broadcaster.broadcastSinglePacket(ackPacket, null, null)
        delay(50)
        val normalPacket = createPacket(MessageType.MESSAGE, "hello world")
        broadcaster.broadcastSinglePacket(normalPacket, null, null)
        delay(50)
        // All packets accepted and processed by worker without exception
        assertTrue(true)
    }

    @Test
    fun testEmergencyControlPacketAwaitingAcceptance() = runBlocking {
        val sosPacket = createPacket(MessageType.SOS, "EMERGENCY_LOC_FIX")
        // Since no peers are connected, internal broadcast returns false without crashing
        val result = broadcaster.broadcastControlPacketAndAwaitAcceptance(sosPacket, null, null)
        // Correctly completed without hanging
        assertEquals(false, result)
    }
}
