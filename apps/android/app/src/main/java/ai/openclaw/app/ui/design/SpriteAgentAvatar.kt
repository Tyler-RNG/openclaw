package ai.openclaw.app.ui.design

import ai.openclaw.app.sprite.SpriteAvatarStore
import ai.openclaw.spritecore.client.compose.CharacterAvatar as SpriteCharacterAvatar
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

/**
 * Phone-flavored wrapper over the SpriteCore Compose renderer.
 *
 * The SDK owns the animation graph, playback clock, and frame decoding, so this
 * is deliberately thin — the same shape the wearable's watch wrapper has. All
 * the phone contributes is sizing and the avatar clip.
 */
@Composable
internal fun SpriteAgentAvatar(
  agentId: String,
  bundle: SpriteAvatarStore.Bundle,
  currentState: String?,
  size: Dp,
  shape: Shape,
  contentDescription: String?,
) {
  SpriteCharacterAvatar(
    agentId = agentId,
    envelope = bundle.envelope,
    assetBytes = bundle.assetBytes,
    currentState = currentState,
    contentDescription = contentDescription,
    modifier = Modifier.size(size).clip(shape),
  )
}
