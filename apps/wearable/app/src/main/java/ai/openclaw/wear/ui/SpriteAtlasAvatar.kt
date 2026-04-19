package ai.openclaw.wear.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import ai.openclaw.wear.AnimationDefinitions
import ai.openclaw.wear.AtlasFrameSource
import ai.openclaw.wear.AvatarRuntime
import ai.openclaw.wear.FrameSource
import ai.openclaw.wear.SpriteFrameSource
import ai.openclaw.wear.parseAtlasDefinitions
import ai.openclaw.wear.parseSpritesDefinitions
import org.json.JSONObject

/**
 * Renders an agent that uses `kind: "sprites"` — bytes per frame are fed
 * into a SpriteFrameSource, the runtime ticks at each state's declared fps.
 *
 * Memoizes the runtime on (agentId, framesKeysSignature, descriptorJson) so
 * the animation isn't restarted on every parent recomposition — only when
 * new frames arrive or the descriptor itself changes.
 */
@Composable
fun SpriteAvatar(
    agentId: String,
    descriptorJson: String,
    framesByKey: Map<String, ByteArray>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val framesSignature = remember(framesByKey) { framesByKey.keys.sorted().hashCode() }
    val runtime = remember(agentId, descriptorJson, framesSignature) {
        val defs = parseSpritesDefinitions(JSONObject(descriptorJson))
        val source = SpriteFrameSource().apply {
            for ((key, bytes) in framesByKey) put(key, bytes)
        }
        AvatarRuntime(agentId, source, defs)
    }
    DisposableEffect(runtime) { onDispose { runtime.dispose() } }
    val bitmap by runtime.currentBitmap.collectAsState()
    BitmapFrame(bitmap, contentDescription, modifier)
}

/**
 * Renders an agent that uses `kind: "atlas"` — one atlas image + a manifest
 * JSON. The AtlasFrameSource slices the decoded bitmap per frame key at
 * tick time; the runtime drives state timing and transitions.
 */
@Composable
fun AtlasAvatar(
    agentId: String,
    manifestJson: String,
    atlasImageBytes: ByteArray,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val runtime = remember(agentId, manifestJson, atlasImageBytes.contentHashCode()) {
        val manifest = JSONObject(manifestJson)
        val defaultState = manifest.optString("default", manifest.optJSONObject("animations")?.keys()?.let {
            if (it.hasNext()) it.next() else "neutral"
        } ?: "neutral")
        val rects = parseAtlasRects(manifest)
        val atlas = BitmapFactory.decodeByteArray(atlasImageBytes, 0, atlasImageBytes.size)
            ?: return@remember null
        val source: FrameSource = AtlasFrameSource(atlas, rects)
        val defs = parseAtlasDefinitions(manifest, defaultState)
        AvatarRuntime(agentId, source, defs)
    }
    DisposableEffect(runtime) { onDispose { runtime?.dispose() } }
    val bitmap by (runtime?.currentBitmap ?: return).collectAsState()
    BitmapFrame(bitmap, contentDescription, modifier)
}

/**
 * Shared render for the runtime's `currentBitmap`. Falls through to a
 * transparent slot when the runtime hasn't emitted its first frame yet
 * (< 1 tick interval on startup) so the parent's fallback chain can paint.
 */
@Composable
private fun BitmapFrame(
    bitmap: Bitmap?,
    contentDescription: String?,
    modifier: Modifier,
) {
    if (bitmap == null) return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * Extract the frame rect map from an atlas manifest — shape per
 * docs/avatars/formats.md: `frames: { "<key>": { x, y, w, h }, ... }`.
 */
private fun parseAtlasRects(manifest: JSONObject): Map<String, Rect> {
    val framesObj = manifest.optJSONObject("frames") ?: return emptyMap()
    val out = mutableMapOf<String, Rect>()
    val keys = framesObj.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val r = framesObj.optJSONObject(k) ?: continue
        val x = r.optInt("x", 0)
        val y = r.optInt("y", 0)
        val w = r.optInt("w", 0)
        val h = r.optInt("h", 0)
        if (w > 0 && h > 0) out[k] = Rect(x, y, x + w, y + h)
    }
    return out
}
