package ai.openclaw.wear.ui

import ai.openclaw.spritecore.client.CharacterManifestEnvelope
import ai.openclaw.spritecore.client.compose.CharacterAvatar as SpriteCharacterAvatar
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Watch-flavored avatar wrapper. The SpriteCore Compose module owns playback
 * and frame decoding; the watch contributes the top-half-square crop so a
 * full-body sprite frames as a headshot on the dial.
 *
 * Sprites are authored square with the character spanning the full frame, so
 * the biggest top square is still the whole bitmap. Splitting the frame in
 * half and taking the centered square of the top half yields a 128² slice at
 * (64, 0) for a 256² source — head and shoulders.
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
    SpriteCharacterAvatar(
        agentId = agentId,
        envelope = envelope,
        assetBytes = assetBytes,
        currentState = currentState,
        contentDescription = contentDescription,
        modifier = modifier,
        bitmapTransform = ::centeredTopHalfSquareCrop,
    )
}

private fun centeredTopHalfSquareCrop(src: Bitmap): Bitmap {
    val topHalfHeight = src.height / 2
    val side = minOf(src.width, topHalfHeight)
    if (side <= 0) return src
    val x = (src.width - side) / 2
    return Bitmap.createBitmap(src, x, 0, side, side)
}
