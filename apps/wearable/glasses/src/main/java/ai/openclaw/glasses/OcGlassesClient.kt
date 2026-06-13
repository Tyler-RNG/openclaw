package ai.openclaw.glasses

import ai.openclaw.spritecore.client.glasses.GlassesProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

/**
 * Replacement for sprite-core's GlassesClient. Same protocol layout:
 *  - UTF-8 string TX → Lua REPL
 *  - 0x01 + sub-channel TX → raw data
 *  - RX with 0x01 prefix → demux to [data] flow; otherwise → [luaResponses]
 *
 * Owns [OcGlassesTransport] so we control write type and disconnect signal.
 */
class OcGlassesClient(
    val transport: OcGlassesTransport,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {
    private val _luaResponses = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val luaResponses: SharedFlow<String> = _luaResponses.asSharedFlow()

    private val _data = MutableSharedFlow<DataPacket>(extraBufferCapacity = 512)
    val data: SharedFlow<DataPacket> = _data.asSharedFlow()

    private var pumpJob: Job? = null

    data class DataPacket(val channel: Byte, val bytes: ByteArray)

    val maxAppPayload: Int
        get() = (transport.maxWriteLength - 1).coerceAtLeast(1)

    fun start() {
        pumpJob = scope.launch {
            transport.incoming.collect(::dispatch)
        }
    }

    fun stop() {
        pumpJob?.cancel(); pumpJob = null
    }

    /**
     * Like [eval] but never chunks. Use this from callers that already chunk
     * the source themselves (e.g. EmbeddedLuaApp.install, which wraps each
     * piece in `f:write([=[…]=])` and would be broken by a `;`-split).
     */
    suspend fun evalRaw(lua: String) {
        transport.write(lua.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Sends a UTF-8 Lua statement. If [lua] exceeds the MTU payload, it's
     * split into multiple writes on `;` boundaries — each chunk is a valid
     * Lua program in its own right that the REPL evaluates sequentially.
     * A single statement that exceeds the MTU on its own will throw.
     */
    suspend fun eval(lua: String) {
        val bytes = lua.toByteArray(StandardCharsets.UTF_8)
        val limit = transport.maxWriteLength
        if (bytes.size <= limit) {
            transport.write(bytes)
            return
        }
        val statements = lua.split(';').filter { it.isNotBlank() }
        val buffer = StringBuilder()
        for (st in statements) {
            val trimmed = st.trim()
            val withSep =
                if (buffer.isEmpty()) trimmed else buffer.toString() + ";" + trimmed
            val withSepBytes = withSep.toByteArray(StandardCharsets.UTF_8).size
            if (withSepBytes > limit) {
                if (buffer.isNotEmpty()) {
                    transport.write((buffer.toString() + ";").toByteArray(StandardCharsets.UTF_8))
                    buffer.clear()
                }
                val singleBytes = trimmed.toByteArray(StandardCharsets.UTF_8).size
                if (singleBytes > limit) {
                    error("eval statement too big (${singleBytes}B > ${limit}B): ${trimmed.take(60)}…")
                }
                buffer.append(trimmed)
            } else {
                if (buffer.isNotEmpty()) buffer.append(";")
                buffer.append(trimmed)
            }
        }
        if (buffer.isNotEmpty()) {
            transport.write(buffer.toString().toByteArray(StandardCharsets.UTF_8))
        }
    }

    /**
     * Frame BLE control signals. A single-byte write of [0x03] interrupts any
     * running Lua script and returns the device to REPL idle; a single-byte
     * [0x04] additionally clears variables and re-runs main.lua. Without 0x03,
     * Frame silently drops every Lua-text eval while a script's main loop is
     * running — which is the failure mode you see when an install does nothing
     * on a device with main.lua already on flash.
     */
    suspend fun sendBreak() {
        transport.write(byteArrayOf(0x03))
    }

    suspend fun sendReset() {
        transport.write(byteArrayOf(0x04))
    }

    /** Sends one application packet on a sub-channel: [0x01][channel][bytes]. */
    suspend fun sendData(channel: Byte, bytes: ByteArray) {
        require(bytes.size <= maxAppPayload) {
            "payload ${bytes.size} exceeds maxAppPayload=$maxAppPayload"
        }
        val framed = ByteArray(bytes.size + 2)
        framed[0] = GlassesProtocol.RAW_DATA_PREFIX
        framed[1] = channel
        System.arraycopy(bytes, 0, framed, 2, bytes.size)
        transport.write(framed)
    }

    private fun dispatch(raw: ByteArray) {
        if (raw.isEmpty()) return
        if (raw[0] == GlassesProtocol.RAW_DATA_PREFIX && raw.size >= 2) {
            val channel = raw[1]
            val payload = raw.copyOfRange(2, raw.size)
            _data.tryEmit(DataPacket(channel, payload))
        } else {
            _luaResponses.tryEmit(raw.toString(StandardCharsets.UTF_8))
        }
    }

    companion object {
        suspend fun connect(
            transport: OcGlassesTransport,
            scope: CoroutineScope,
            log: (String) -> Unit,
        ): OcGlassesClient {
            transport.connect()
            transport.negotiateMtu()
            val client = OcGlassesClient(transport, scope, log)
            client.start()
            return client
        }
    }
}
