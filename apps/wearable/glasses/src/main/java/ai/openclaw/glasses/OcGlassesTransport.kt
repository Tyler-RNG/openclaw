package ai.openclaw.glasses

import ai.openclaw.spritecore.client.glasses.GlassesProtocol
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Replacement for sprite-core's GlassesBleTransport with three fixes:
 *  - Picks WRITE_TYPE_NO_RESPONSE if the TX characteristic only supports it
 *    (which is how Brilliant's Frame firmware exposes it). The SDK forces
 *    WRITE_TYPE_DEFAULT and gets writeCharacteristic = false every time.
 *  - Retries with a short backoff when the OS rejects a write (queue full).
 *  - Exposes a [disconnectHandler] so the ViewModel can react to drops.
 *
 * Also logs every meaningful step via [log] so we can SEE failures.
 */
@SuppressLint("MissingPermission")
class OcGlassesTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
    private val disconnectHandler: (Int) -> Unit,
) {
    private val gattRef = AtomicReference<BluetoothGatt?>(null)
    private val txRef = AtomicReference<BluetoothGattCharacteristic?>(null)

    private val writeMutex = Mutex()
    private var pendingWrite: CompletableDeferred<Unit>? = null
    private var pendingMtu: CompletableDeferred<Int>? = null
    private var pendingConnect: CompletableDeferred<Unit>? = null

    @Volatile
    private var writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    @Volatile
    private var requiresWriteAck: Boolean = false
    @Volatile
    private var lastWriteAtMs: Long = 0L

    private val _incoming = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    @Volatile
    var maxWriteLength: Int = 20
        private set

    @Volatile
    var connected: Boolean = false
        private set

    suspend fun connect() {
        log("transport.connect → ${device.address}")
        val deferred = CompletableDeferred<Unit>()
        pendingConnect = deferred
        val gatt = device.connectGatt(
            context,
            /* autoConnect = */ false,
            callback,
            BluetoothDevice.TRANSPORT_LE,
        ) ?: error("connectGatt returned null")
        gattRef.set(gatt)
        val done = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { deferred.await() }
        if (done == null) {
            log("transport.connect TIMEOUT after ${CONNECT_TIMEOUT_MS}ms")
            error("connect timeout")
        }
        connected = true
        log("transport.connect OK; writeType=${writeTypeName(writeType)} ack=$requiresWriteAck")
    }

    suspend fun negotiateMtu(requested: Int = 251): Int {
        val gatt = gattRef.get() ?: error("not connected")
        val deferred = CompletableDeferred<Int>()
        pendingMtu = deferred
        log("transport.requestMtu($requested)")
        check(gatt.requestMtu(requested)) { "requestMtu rejected" }
        val granted = withTimeoutOrNull(MTU_TIMEOUT_MS) { deferred.await() } ?: 23
        maxWriteLength = (granted - GlassesProtocol.RAW_DATA_OVERHEAD).coerceAtLeast(20)
        log("transport.mtu granted=$granted → maxWriteLength=$maxWriteLength")
        return granted
    }

    /**
     * Sends [payload] on the TX characteristic. For NO_RESPONSE writes we
     * fire-and-forget (no peer ack); for DEFAULT we await onCharacteristicWrite.
     * Retries up to 5 times on writeCharacteristic=false (Android queue full).
     */
    /**
     * Sends [payload]. NO_RESPONSE writes have no ack from the peer, so the
     * only way to avoid saturating the OS queue is to pace them by the BLE
     * connection interval (15ms is a safe floor on Android). DEFAULT writes
     * await onCharacteristicWrite, which provides natural flow control.
     *
     * Retries with progressive backoff if writeCharacteristic returns false
     * (queue still full despite pacing).
     */
    suspend fun write(payload: ByteArray) {
        if (payload.size > maxWriteLength) {
            error("write size ${payload.size} exceeds MTU payload $maxWriteLength — chunk before calling write")
        }
        writeMutex.withLock {
            val gatt = gattRef.get() ?: error("not connected")
            val tx = txRef.get() ?: error("TX characteristic not discovered")
            tx.writeType = writeType
            tx.value = payload

            // Pace NO_RESPONSE writes to respect the BLE connection interval.
            if (!requiresWriteAck) {
                val gap = System.currentTimeMillis() - lastWriteAtMs
                if (gap < WRITE_PACING_MS) delay(WRITE_PACING_MS - gap)
            }

            val ack = if (requiresWriteAck) CompletableDeferred<Unit>().also { pendingWrite = it } else null

            var attempt = 0
            while (true) {
                val accepted = gatt.writeCharacteristic(tx)
                if (accepted) break
                attempt++
                if (attempt >= WRITE_RETRY_LIMIT) {
                    log("write REJECTED after $attempt tries (size=${payload.size})")
                    error("writeCharacteristic rejected after $attempt retries")
                }
                // Progressive backoff: 30, 60, 120, 240, 480, 960, … capped at 1500.
                val backoff = (WRITE_RETRY_BASE_MS * (1 shl (attempt - 1).coerceAtMost(6)))
                    .coerceAtMost(1500L)
                delay(backoff)
            }
            lastWriteAtMs = System.currentTimeMillis()
            ack?.let {
                val done = withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) { it.await() }
                if (done == null) {
                    log("write ACK timeout (size=${payload.size})")
                    error("write ack timeout")
                }
            }
        }
    }

    fun close() {
        log("transport.close")
        connected = false
        gattRef.getAndSet(null)?.let { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        txRef.set(null)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            log("onConnectionStateChange status=$status newState=${connStateName(newState)}")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Some firmware/stacks need a brief settle before discovery.
                    val ok = gatt.discoverServices()
                    log("discoverServices accepted=$ok")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = connected
                    connected = false
                    pendingConnect?.completeExceptionally(
                        IllegalStateException("disconnected: $status"),
                    )
                    if (wasConnected) disconnectHandler(status)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            log("onServicesDiscovered status=$status")
            val service = gatt.getService(GlassesProtocol.SERVICE_UUID)
            val tx = service?.getCharacteristic(GlassesProtocol.TX_CHARACTERISTIC)
            val rx = service?.getCharacteristic(GlassesProtocol.RX_CHARACTERISTIC)
            if (tx == null || rx == null) {
                log("service=${service != null} tx=${tx != null} rx=${rx != null}")
                pendingConnect?.completeExceptionally(
                    IllegalStateException("Brilliant service/characteristics not found"),
                )
                return
            }
            val props = tx.properties
            val supportsNoResp = (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            val supportsAck = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            // Prefer ack-based writes when supported — they give natural flow
            // control via onCharacteristicWrite, which prevents OS queue saturation.
            writeType = when {
                supportsAck -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                supportsNoResp -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else -> {
                    pendingConnect?.completeExceptionally(
                        IllegalStateException("TX characteristic has no write property (props=0x${props.toString(16)})"),
                    )
                    return
                }
            }
            requiresWriteAck = (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            log("tx props=0x${props.toString(16)} → writeType=${writeTypeName(writeType)}")
            txRef.set(tx)
            val notifOk = gatt.setCharacteristicNotification(rx, true)
            log("setCharacteristicNotification rx=$notifOk")
            val cccd = rx.getDescriptor(GlassesProtocol.CCCD)
            cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (cccd != null) {
                val descOk = gatt.writeDescriptor(cccd)
                log("writeDescriptor CCCD accepted=$descOk")
                if (!descOk) {
                    // Notifications won't work but we can still write. Proceed.
                    pendingConnect?.complete(Unit)
                }
            } else {
                log("CCCD missing — notifications may not work")
                pendingConnect?.complete(Unit)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            log("onDescriptorWrite uuid=${descriptor.uuid} status=$status")
            if (descriptor.uuid == GlassesProtocol.CCCD) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    pendingConnect?.complete(Unit)
                } else {
                    pendingConnect?.completeExceptionally(
                        IllegalStateException("CCCD write failed: $status"),
                    )
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("onMtuChanged mtu=$mtu status=$status")
            pendingMtu?.complete(mtu)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid == GlassesProtocol.TX_CHARACTERISTIC) {
                pendingWrite?.complete(Unit)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == GlassesProtocol.RX_CHARACTERISTIC) {
                _incoming.tryEmit(characteristic.value.copyOf())
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val MTU_TIMEOUT_MS = 5_000L
        const val WRITE_RETRY_LIMIT = 12
        const val WRITE_RETRY_BASE_MS = 30L
        const val WRITE_ACK_TIMEOUT_MS = 8_000L
        const val WRITE_PACING_MS = 15L

        fun connStateName(s: Int): String = when (s) {
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> "STATE_$s"
        }

        fun writeTypeName(t: Int): String = when (t) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE -> "NO_RESP"
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT -> "DEFAULT"
            BluetoothGattCharacteristic.WRITE_TYPE_SIGNED -> "SIGNED"
            else -> "WT_$t"
        }
    }
}
