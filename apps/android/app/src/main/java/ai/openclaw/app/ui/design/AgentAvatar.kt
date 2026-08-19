package ai.openclaw.app.ui.design

import ai.openclaw.app.GatewayAgentSummary
import ai.openclaw.app.sprite.SpriteAvatarStore
import ai.openclaw.app.ui.image.RemoteImageResult
import ai.openclaw.app.ui.image.decodeRemoteImageBitmap
import ai.openclaw.app.ui.image.safeRemoteImageStore
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private const val AGENT_AVATAR_MAX_DIMENSION = 256
private const val AGENT_AVATAR_MAX_BYTES = 2 * 1024 * 1024
private const val AGENT_AVATAR_MAX_DATA_URL_PREFIX_CHARS = 26

// Keep Android decoding inside the Gateway's shared avatar payload boundary.
private const val AGENT_AVATAR_MAX_DATA_URL_CHARS =
  ((AGENT_AVATAR_MAX_BYTES + 2) / 3) * 4 + AGENT_AVATAR_MAX_DATA_URL_PREFIX_CHARS
private val dataImageBase64Prefix =
  Regex("^data:(image/[a-z0-9.+-]+);base64,", RegexOption.IGNORE_CASE)
private val remoteImagePrefix = Regex("^https?://", RegexOption.IGNORE_CASE)

internal sealed interface AgentAvatarSource {
  data class Data(
    val mimeType: String,
    val base64: String,
  ) : AgentAvatarSource

  data class Remote(
    val url: String,
  ) : AgentAvatarSource

  /**
   * Animated multi-state avatar served by the SpriteCore gateway plugin.
   * Takes precedence over the static forms when a complete bundle has loaded;
   * see [rememberAgentAvatarSource].
   */
  data class Sprite(
    val agentId: String,
    val bundle: SpriteAvatarStore.Bundle,
    val currentState: String?,
  ) : AgentAvatarSource
}

/**
 * Sprite avatar source for the current screen, or null where SpriteCore is
 * unavailable (no gateway runtime, plugin not installed, previews).
 * Provided once near the app root; see [rememberAgentAvatarSource].
 */
internal val LocalSpriteAvatarStore = staticCompositionLocalOf<SpriteAvatarStore?> { null }

/**
 * Resolves the avatar to render for [agent], preferring an animated SpriteCore
 * avatar when a complete bundle has loaded and falling back to the static
 * source otherwise. Inert when no store is provided, so previews and any
 * surface outside the runtime tree keep the plain static behaviour.
 */
@Composable
internal fun rememberAgentAvatarSource(agent: GatewayAgentSummary): AgentAvatarSource? {
  val spriteStore = LocalSpriteAvatarStore.current ?: return agentAvatarSource(agent)
  LaunchedEffect(spriteStore, agent.id) { spriteStore.ensureLoaded(agent.id) }
  val bundles by spriteStore.bundles.collectAsState()
  val states by spriteStore.states.collectAsState()
  val bundle = bundles[agent.id]
  return if (bundle != null) {
    AgentAvatarSource.Sprite(
      agentId = agent.id,
      bundle = bundle,
      currentState = states[agent.id],
    )
  } else {
    agentAvatarSource(agent)
  }
}

/** Returns the authoritative Android-renderable agent avatar source, if present. */
internal fun agentAvatarSource(agent: GatewayAgentSummary): AgentAvatarSource? {
  val candidate =
    agent.avatarUrl?.trim()?.takeIf { it.isNotEmpty() }
      ?: agent.avatar?.trim()?.takeIf { it.isNotEmpty() }
      ?: return null
  if (remoteImagePrefix.containsMatchIn(candidate)) {
    return AgentAvatarSource.Remote(candidate)
  }
  if (candidate.length > AGENT_AVATAR_MAX_DATA_URL_CHARS) return null
  val prefix = dataImageBase64Prefix.find(candidate) ?: return null
  val base64 = candidate.substring(prefix.range.last + 1).trim().takeIf { it.isNotEmpty() } ?: return null
  return AgentAvatarSource.Data(
    mimeType = prefix.groupValues[1].lowercase(Locale.US),
    base64 = base64,
  )
}

/** Renders an agent image when loading succeeds, otherwise the caller-owned fallback. */
@Composable
internal fun ClawAgentAvatar(
  source: AgentAvatarSource?,
  size: Dp,
  shape: Shape = CircleShape,
  fallback: @Composable () -> Unit,
) {
  when (source) {
    is AgentAvatarSource.Data ->
      if (source.mimeType == "image/svg+xml") {
        SvgAgentAvatar(base64 = source.base64, size = size, shape = shape, fallback = fallback)
      } else {
        RasterDataAgentAvatar(source = source, size = size, shape = shape, fallback = fallback)
      }
    is AgentAvatarSource.Remote -> RemoteAgentAvatar(source.url, size, shape, fallback)
    is AgentAvatarSource.Sprite ->
      SpriteAgentAvatar(
        agentId = source.agentId,
        bundle = source.bundle,
        currentState = source.currentState,
        size = size,
        shape = shape,
        contentDescription = null,
      )
    null -> fallback()
  }
}

@Composable
private fun RasterDataAgentAvatar(
  source: AgentAvatarSource.Data,
  size: Dp,
  shape: Shape,
  fallback: @Composable () -> Unit,
) {
  var bitmap by remember(source) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(source) {
    bitmap =
      withContext(Dispatchers.Default) {
        val bytes = decodeAgentAvatarBase64(source.base64) ?: return@withContext null
        decodeRemoteImageBitmap(
          bytes = bytes,
          maxDimension = AGENT_AVATAR_MAX_DIMENSION,
          expectedContentType = source.mimeType,
        )
      }
  }
  val resolved = bitmap
  if (resolved == null) {
    fallback()
  } else {
    Image(
      bitmap = resolved.asImageBitmap(),
      contentDescription = null,
      modifier = Modifier.size(size).clip(shape),
      contentScale = ContentScale.Crop,
    )
  }
}

@Composable
private fun RemoteAgentAvatar(
  url: String,
  size: Dp,
  shape: Shape,
  fallback: @Composable () -> Unit,
) {
  var result by remember(url) { mutableStateOf<RemoteImageResult?>(null) }
  LaunchedEffect(url) {
    result = safeRemoteImageStore.get(url)
  }
  when (val image = result) {
    is RemoteImageResult.Raster ->
      Image(
        bitmap = image.bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(size).clip(shape),
        contentScale = ContentScale.Crop,
      )
    is RemoteImageResult.Svg -> SvgAgentAvatar(bytes = image.bytes, size = size, shape = shape, fallback = fallback)
    RemoteImageResult.Failed, null -> fallback()
  }
}

@Composable
private fun SvgAgentAvatar(
  base64: String,
  size: Dp,
  shape: Shape,
  fallback: @Composable () -> Unit,
) {
  var bytes by remember(base64) { mutableStateOf<ByteArray?>(null) }
  LaunchedEffect(base64) {
    bytes =
      withContext(Dispatchers.Default) {
        decodeAgentAvatarBase64(base64)
      }
  }
  val resolved = bytes
  if (resolved == null) {
    fallback()
  } else {
    SvgAgentAvatar(bytes = resolved, size = size, shape = shape, fallback = fallback)
  }
}

@Composable
private fun SvgAgentAvatar(
  bytes: ByteArray,
  size: Dp,
  shape: Shape,
  fallback: @Composable () -> Unit,
) {
  val context = LocalPlatformContext.current
  val request =
    remember(bytes, context) {
      ImageRequest
        .Builder(context)
        .data(bytes)
        .size(AGENT_AVATAR_MAX_DIMENSION)
        .build()
    }
  val painter = rememberAsyncImagePainter(model = request, contentScale = ContentScale.Crop)
  val painterState by painter.state.collectAsState()
  if (painterState !is AsyncImagePainter.State.Success) {
    fallback()
    return
  }
  Image(
    painter = painter,
    contentDescription = null,
    modifier = Modifier.size(size).clip(shape),
    contentScale = ContentScale.Crop,
  )
}

private fun decodeAgentAvatarBase64(base64: String): ByteArray? =
  runCatching { Base64.decode(base64, Base64.DEFAULT) }
    .getOrNull()
    ?.takeIf { it.isNotEmpty() && it.size <= AGENT_AVATAR_MAX_BYTES }
