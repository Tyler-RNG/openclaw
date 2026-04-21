package ai.openclaw.wear.ui

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
import ai.openclaw.displaykit.AnimationGraph
import ai.openclaw.displaykit.CharacterManifestEnvelope
import ai.openclaw.displaykit.SpriteAnimationPlayer
import ai.openclaw.wear.BitmapFrameSource
import ai.openclaw.wear.CharacterManifestJson

/**
 * Unified avatar composable driven by the gateway's CharacterManifest
 * contract and DisplayKit's playback engine. Replaces per-kind
 * [SpriteAvatar]/[AtlasAvatar] with a single code path — the manifest's
 * `content[mode]` table, atlas atlas-ref with crop rects, or flat
 * whole-image refs all flow through [AnimationGraph.fromManifest] and
 * [SpriteAnimationPlayer].
 *
 * The `envelope` and `assetBytes` arguments are published by the phone
 * relay from `node.getCharacterManifest`; the composable decodes bitmaps
 * on demand via [BitmapFrameSource] and ticks frames through the player's
 * `currentRef` flow.
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
        // Manifest has no modes the watch can render (e.g. fullbody-only when
        // we advertise headshot caps). Drop out; caller's fallback paints.
        return
    }
    val graph = remember(envelope.revision, agentId, mode) {
        runCatching { AnimationGraph.fromManifest(envelope.manifest, mode) }.getOrNull()
    } ?: return

    // Keep the FrameSource keyed by revision so byte changes (new manifest
    // version) force re-decode of bitmaps; within a revision the sliceCache
    // + decode cache stay warm across recompositions.
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
