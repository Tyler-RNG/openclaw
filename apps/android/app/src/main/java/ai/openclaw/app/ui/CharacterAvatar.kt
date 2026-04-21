package ai.openclaw.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import ai.openclaw.app.avatar.BitmapFrameSource
import ai.openclaw.app.avatar.CharacterManifestJson
import ai.openclaw.displaykit.AnimationGraph
import ai.openclaw.displaykit.CharacterManifestEnvelope
import ai.openclaw.displaykit.SpriteAnimationPlayer

/**
 * Avatar composable driven by the gateway's CharacterManifest contract and
 * DisplayKit's playback engine. Shared composable used by the phone's
 * AgentDialScreen; a structurally-identical copy lives in
 * apps/wearable/.../ui/CharacterAvatar.kt — a shared Android UI module would
 * deduplicate them.
 */
@Composable
fun CharacterAvatar(
    agentId: String,
    envelope: CharacterManifestEnvelope,
    assetBytes: Map<String, ByteArray>,
    currentState: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val mode = remember(envelope.revision, agentId) {
        CharacterManifestJson.pickMode(envelope.manifest)
    }
    if (mode == null) {
        return
    }
    val graph = remember(envelope.revision, agentId, mode) {
        runCatching { AnimationGraph.fromManifest(envelope.manifest, mode) }.getOrNull()
    } ?: return

    val frameSource = remember(envelope.revision, agentId, assetBytes) {
        BitmapFrameSource(assetBytes)
    }
    val player = remember(envelope.revision, agentId, mode) {
        SpriteAnimationPlayer(graph)
    }
    DisposableEffect(player) { onDispose { player.dispose() } }

    LaunchedEffect(player, currentState) {
        currentState?.takeIf { it.isNotBlank() }?.let { stateName ->
            val resolved = envelope.manifest.stateMap[stateName] ?: stateName
            if (envelope.manifest.content[mode]?.animations?.containsKey(resolved) == true) {
                player.requestState(resolved)
            }
        }
    }

    val ref by player.currentRef.collectAsState()
    val bitmap: Bitmap? = ref?.let { frameSource.frame(it) }
    if (bitmap == null) return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
    )
}
