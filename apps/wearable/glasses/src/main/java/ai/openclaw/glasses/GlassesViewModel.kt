package ai.openclaw.glasses

import ai.openclaw.spritecore.client.glasses.GlassesBleTransport
import ai.openclaw.spritecore.client.glasses.GlassesClient
import ai.openclaw.spritecore.client.glasses.GlassesInputEvent
import ai.openclaw.spritecore.client.glasses.GlassesInputSource
import ai.openclaw.spritecore.client.glasses.GlassesMicSource
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Phone-side coordinator for the Brilliant Frame integration. Owns the
 * [GlassesClient] lifecycle and surfaces a single [Screen] state for the UI.
 *
 * Gateway bridge (mic → STT, TTS → audio, avatar frames → display) is wired
 * by the openclaw android relay in a follow-up — this scaffold proves the
 * BLE + Lua boot path end-to-end first.
 */
class GlassesViewModel(app: Application) : AndroidViewModel(app) {
    private val _screen = MutableStateFlow<Screen>(Screen.Idle)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private var client: GlassesClient? = null

    sealed interface Screen {
        data object Idle : Screen
        data object Scanning : Screen
        data class Connecting(val name: String?) : Screen
        data class Connected(val name: String?) : Screen
        data class Error(val message: String) : Screen
    }

    fun connectTo(device: BluetoothDevice) {
        val name = runCatching { device.name }.getOrNull()
        _screen.value = Screen.Connecting(name)
        viewModelScope.launch {
            val transport = GlassesBleTransport(getApplication(), device)
            val c = runCatching { GlassesClient.connect(transport, viewModelScope) }
                .getOrElse { e ->
                    _screen.value = Screen.Error(e.message ?: "connect failed")
                    return@launch
                }
            client = c
            runCatching {
                c.installApp(getApplication<Application>().assets)
                c.requireApp()
            }.onFailure { e ->
                _screen.value = Screen.Error(e.message ?: "lua install failed")
                return@launch
            }
            val mic = GlassesMicSource(c)
            val input = GlassesInputSource(c)
            viewModelScope.launch {
                input.events.collect { onInputEvent(it) }
            }
            viewModelScope.launch {
                mic.pcm8kMono8bit.collect { /* TODO: forward to openclaw STT route */ }
            }
            mic.start()
            _screen.value = Screen.Connected(name)
        }
    }

    fun startScan() {
        _screen.value = Screen.Scanning
    }

    private fun onInputEvent(event: GlassesInputEvent) {
        // TODO: forward tap/heading into the openclaw gateway as a wearable input.
    }

    fun bluetoothManager(): BluetoothManager? =
        getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
}
