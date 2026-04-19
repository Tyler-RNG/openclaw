package ai.openclaw.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import ai.openclaw.wear.protocol.WearAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Watches Wearable DataClient for avatar + TTS assets published by the phone
 * relay. Assets are published under `/openclaw/avatars/<agentId>` and
 * `/openclaw/tts/<assetId>`. The raw bytes are cached in-memory and exposed
 * as StateFlows keyed by the id portion of the path.
 *
 * Because avatars are small and stable, we hold them in RAM for the session.
 * TTS bytes are evicted after playback to keep memory bounded.
 */
class WearAssetStore(private val context: Context) {

    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _avatars = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val avatars: StateFlow<Map<String, ByteArray>> = _avatars.asStateFlow()

    // Bumped on every avatar byte update so downstream image loaders can bust
    // their memory cache — same DataClient path reuses the same cache key.
    private val _avatarVersion = MutableStateFlow(0)
    val avatarVersion: StateFlow<Int> = _avatarVersion.asStateFlow()

    private val _tts = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val tts: StateFlow<Map<String, ByteArray>> = _tts.asStateFlow()

    private val listener = DataClient.OnDataChangedListener { events ->
        for (event in events) {
            val path = event.dataItem.uri.path ?: continue
            when (event.type) {
                DataEvent.TYPE_CHANGED -> handleChanged(event.dataItem, path)
                DataEvent.TYPE_DELETED -> handleDeleted(path)
            }
        }
    }

    fun start() {
        dataClient.addListener(listener)
        // Pull any items that already exist on the Data Layer when we start up.
        scope.launch {
            try {
                val avatarUri = Uri.parse("wear://*${WearAsset.DATA_AVATAR_PATH}/")
                dataClient.getDataItems(avatarUri, DataClient.FILTER_PREFIX).await()
                    .forEach { item ->
                        val p = item.uri.path ?: return@forEach
                        handleChanged(item, p)
                    }
            } catch (e: Throwable) {
                Log.w(TAG, "initial avatar sweep failed", e)
            }
        }
    }

    fun stop() {
        dataClient.removeListener(listener)
    }

    /**
     * Wait for the TTS asset with [assetId] to land. Returns null if it
     * doesn't arrive within [timeoutMs]. Removes the cached bytes once
     * returned so the same asset isn't replayed on future events.
     */
    suspend fun awaitTts(assetId: String, timeoutMs: Long = 20_000): ByteArray? {
        val existing = _tts.value[assetId]
        if (existing != null) {
            _tts.update { it - assetId }
            return existing
        }
        val bytes = withTimeoutOrNull(timeoutMs) {
            tts.first { it.containsKey(assetId) }[assetId]
        }
        if (bytes != null) _tts.update { it - assetId }
        return bytes
    }

    private fun handleChanged(item: DataItem, path: String) {
        val dm = runCatching { DataMapItem.fromDataItem(item).dataMap }.getOrNull() ?: return
        val asset = dm.getAsset("data") ?: return
        scope.launch {
            try {
                val fd = dataClient.getFdForAsset(asset).await()
                val bytes = fd.inputStream.use { it.readBytes() }
                when {
                    path.startsWith("${WearAsset.DATA_AVATAR_PATH}/") -> {
                        val id = path.removePrefix("${WearAsset.DATA_AVATAR_PATH}/")
                        _avatars.update { it + (id to bytes) }
                        _avatarVersion.update { it + 1 }
                        Log.d(TAG, "avatar $id loaded (${bytes.size}B)")
                    }
                    path.startsWith("${WearAsset.DATA_TTS_PATH}/") -> {
                        val id = path.removePrefix("${WearAsset.DATA_TTS_PATH}/")
                        _tts.update { it + (id to bytes) }
                        Log.d(TAG, "tts $id loaded (${bytes.size}B)")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "asset load failed for $path", e)
            }
        }
    }

    private fun handleDeleted(path: String) {
        when {
            path.startsWith("${WearAsset.DATA_AVATAR_PATH}/") -> {
                val id = path.removePrefix("${WearAsset.DATA_AVATAR_PATH}/")
                _avatars.update { it - id }
            }
            path.startsWith("${WearAsset.DATA_TTS_PATH}/") -> {
                val id = path.removePrefix("${WearAsset.DATA_TTS_PATH}/")
                _tts.update { it - id }
            }
        }
    }

    companion object {
        private const val TAG = "WearAssetStore"
    }
}
