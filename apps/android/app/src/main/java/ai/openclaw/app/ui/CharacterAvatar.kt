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
import ai.openclaw.spritecore.client.AgentAvatarSource
import ai.openclaw.spritecore.client.AnimationGraph
import ai.openclaw.spritecore.client.CharacterManifestEnvelope
import ai.openclaw.spritecore.client.CharacterManifestJson
import ai.openclaw.spritecore.client.SpriteAnimationPlayer
import ai.openclaw.spritecore.client.android.BitmapFrameSource

/**
 * Avatar composable driven by the gateway's CharacterManifest contract and
 * the SpriteCore Kotlin SDK's playback engine. A structurally-identical copy
 * lives in apps/wearable/.../ui/CharacterAvatar.kt.
 */
@Composable
fun CharacterAvatar(
    agentId: String,
    envelope: CharacterManifestEnvelope,
    assetBytes: Map<String, ByteArray>,
    currentState: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    /**
     * Versioned signal from the client-parsed `<<<state-N>>>` marker.
     * When present, takes precedence over [currentState] and drives the
     * player with the embedded count so "play once and hold" / "play N
     * times" semantics are honoured. Keyed by [AgentAvatarSource.AvatarMarkerSignal.version]
     * so the LaunchedEffect re-triggers even on identical back-to-back
     * marker dispatches.
     */
    markerSignal: AgentAvatarSource.AvatarMarkerSignal? = null,
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

    // Prefer the versioned marker signal (carries count + a monotonic
    // version so repeat markers re-fire); fall back to the plain state
    // name for code paths that haven't been migrated yet.
    if (markerSignal != null) {
        LaunchedEffect(player, markerSignal.version) {
            val resolved = envelope.manifest.stateMap[markerSignal.state] ?: markerSignal.state
            if (envelope.manifest.content[mode]?.animations?.containsKey(resolved) == true) {
                player.requestState(resolved, playCount = markerSignal.count)
            }
        }
    } else {
        LaunchedEffect(player, currentState) {
            currentState?.takeIf { it.isNotBlank() }?.let { stateName ->
                val resolved = envelope.manifest.stateMap[stateName] ?: stateName
                if (envelope.manifest.content[mode]?.animations?.containsKey(resolved) == true) {
                    player.requestState(resolved)
                }
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
