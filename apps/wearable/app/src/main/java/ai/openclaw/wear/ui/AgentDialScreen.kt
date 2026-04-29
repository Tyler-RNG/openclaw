package ai.openclaw.wear.ui

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import ai.openclaw.wear.BuildConfig
import ai.openclaw.wear.PhoneBridge
import ai.openclaw.wear.VoiceState
import ai.openclaw.wear.WearViewModel
import ai.openclaw.spritecore.client.characterManifestBytesReady
import ai.openclaw.wear.protocol.WearAsset
import ai.openclaw.wear.ui.CharacterAvatar
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/** Parse a hex color string like "#FF5733" into a Compose Color, or null. */
private fun parseThemeColor(theme: String?): Color? {
    if (theme.isNullOrBlank()) return null
    return try {
        Color(AndroidColor.parseColor(if (theme.startsWith("#")) theme else "#$theme"))
    } catch (_: Throwable) {
        null
    }
}

/**
 * Resolves an avatar string to something Coil can load.
 *  - `wear-asset:avatar:<id>` → bytes from the AssetStore (animated GIFs etc).
 *  - http(s) URL → pass through as String (direct fetch if reachable).
 *  - `data:` URI → decode base64 inline.
 *  - anything else → null, caller falls back to the default asset.
 */
private fun resolveAvatarModel(raw: String?, assetBytes: Map<String, ByteArray>? = null): Any? {
    if (raw.isNullOrBlank()) return null
    return when {
        raw.startsWith(WearAsset.AVATAR_REF_PREFIX) -> {
            val id = WearAsset.parseAvatarRef(raw) ?: return null
            assetBytes?.get(id)
        }
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("data:") -> {
            val idx = raw.indexOf("base64,")
            if (idx < 0) return null
            val payload = raw.substring(idx + "base64,".length)
            runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
        }
        else -> null
    }
}

/**
 * Full-page Omnitrix-style agent pager.
 * Swipe left/right to browse agents. Each agent is a full page.
 * Tap the agent icon/GIF to activate mic immediately.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentDialScreen(viewModel: WearViewModel) {
    val agents by viewModel.agents.collectAsState()
    val agentVoiceStates by viewModel.agentVoiceStates.collectAsState()
    val agentResponseTexts by viewModel.agentResponseTexts.collectAsState()
    val liveTranscript by viewModel.liveTranscript.collectAsState()
    val listeningAgentId by viewModel.listeningAgentId.collectAsState()
    val context = LocalContext.current

    if (agents.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("No agents", color = OmnitrixGreen, fontFamily = FontFamily.Monospace)
        }
        return
    }

    // One trailing page past the last agent hosts a rolling build stamp so we
    // can confirm on-glass which APK the watch is actually running.
    val pagerState = rememberPagerState(pageCount = { agents.size + 1 })
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val unreadByAgent by viewModel.unreadByAgent.collectAsState()
    val pendingMailJump by viewModel.pendingMailJump.collectAsState()
    val avatarAssets by viewModel.avatarAssets.collectAsState()
    val avatarVersions by viewModel.avatarVersions.collectAsState()
    val agentStates by viewModel.agentStates.collectAsState()
    val characterManifests by viewModel.characterManifests.collectAsState()
    val characterAssets by viewModel.characterAssets.collectAsState()

    // Request focus so rotary events reach this composable
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Tell the VM which agent is currently being viewed so incoming replies
    // can be attributed as read vs unread. Runs whenever the page changes.
    LaunchedEffect(pagerState.currentPage, agents) {
        viewModel.onAgentViewed(agents.getOrNull(pagerState.currentPage)?.id)
    }

    // When the user taps the mailbox badge, scroll to the unread agent and
    // auto-replay the last final reply on their page.
    LaunchedEffect(pendingMailJump, agents) {
        val target = pendingMailJump ?: return@LaunchedEffect
        val idx = agents.indexOfFirst { it.id == target }
        if (idx < 0) {
            viewModel.consumeMailJump()
            return@LaunchedEffect
        }
        if (pagerState.currentPage != idx) {
            pagerState.animateScrollToPage(idx)
        }
        viewModel.replayLastFinal(target)
        viewModel.consumeMailJump()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .onRotaryScrollEvent { event ->
                coroutineScope.launch {
                    val delta = event.verticalScrollPixels
                    val current = pagerState.currentPage
                    // Last reachable page is the trailing build-stamp page, so
                    // the upper bound is agents.size (not agents.size - 1).
                    if (delta > 0 && current < agents.size) {
                        pagerState.animateScrollToPage(current + 1)
                    } else if (delta < 0 && current > 0) {
                        pagerState.animateScrollToPage(current - 1)
                    }
                }
                true
            }
            .focusable(),
    ) { pageIndex ->
        if (pageIndex >= agents.size) {
            BuildStampPage()
            return@HorizontalPager
        }
        val agent = agents[pageIndex]
        val isCurrentPage = pagerState.currentPage == pageIndex
        val agentColor = parseThemeColor(agent.theme) ?: OmnitrixGreen
        val agentVersion = avatarVersions[agent.id] ?: 0
        val resolvedAvatar = remember(agent.avatarUrl, avatarAssets, agentVersion) {
            val model = resolveAvatarModel(agent.avatarUrl, avatarAssets)
            val size = (model as? ByteArray)?.size ?: 0
            Log.d("AgentDialScreen", "avatar update for ${agent.id}: size=${size}B, version=$agentVersion")
            model
        }

        // Per-agent state — "thinking" no longer bleeds across the dial.
        val voiceState = agentVoiceStates[agent.id] ?: VoiceState.Idle
        val responseText = agentResponseTexts[agent.id]
        val isActive = voiceState == VoiceState.Listening ||
            voiceState == VoiceState.Thinking ||
            voiceState == VoiceState.Sending

        // Press-to-grow scale. No pulse; only the current page responds, and
        // only while the user actively holds the avatar.
        var isPressed by remember(pageIndex) { mutableStateOf(false) }
        val pressScale by animateFloatAsState(
            targetValue = if (isPressed) 1.15f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "avatar-press-scale",
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            // Outer glow ring using agent theme color.
            val ringColor = when {
                isCurrentPage && voiceState == VoiceState.Error -> Color.Red
                isCurrentPage && isActive -> agentColor
                isCurrentPage && voiceState == VoiceState.Thinking -> Color(0xFFFFAA00)
                else -> agentColor.copy(alpha = 0.6f)
            }
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .border(
                        width = 2.dp,
                        brush = Brush.radialGradient(
                            colors = listOf(ringColor, ringColor.copy(alpha = 0.2f), Color.Black),
                        ),
                        shape = CircleShape,
                    ),
            )

            // Big centered GIF/image — the main visual. Tap-and-hold activates
            // PTT and grows the avatar.
            val iconBorderColor = when {
                isCurrentPage && voiceState == VoiceState.Listening -> agentColor
                isCurrentPage && voiceState == VoiceState.Thinking -> Color(0xFFFFAA00)
                isCurrentPage && voiceState == VoiceState.Error -> Color.Red
                else -> agentColor.copy(alpha = 0.6f)
            }

            Box(
                modifier = Modifier
                    .size(143.dp)
                    .scale(pressScale)
                    .clip(RoundedCornerShape(34.dp))
                    .background(agentColor.copy(alpha = 0.1f))
                    .border(2.dp, iconBorderColor, RoundedCornerShape(34.dp))
                    .pointerInput(isCurrentPage) {
                        if (isCurrentPage) {
                            detectTapGestures(
                                onPress = {
                                    isPressed = true
                                    viewModel.startPushToTalk(pageIndex)
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        isPressed = false
                                        viewModel.endPushToTalk()
                                    }
                                },
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val imageData: Any? = resolvedAvatar
                val isThinking = isCurrentPage && voiceState == VoiceState.Thinking

                // Structured avatars (sprites / atlas) flow through the
                // gateway's node.getCharacterManifest RPC + the SpriteCore SDK's
                // SpriteAnimationPlayer. Plain-URL avatars (static PNG / WebP
                // / JPG) render through AsyncImage below. Animated GIFs are
                // no longer supported — agents that want motion must use the
                // sprites or atlas format.
                val characterManifest = characterManifests[agent.id]
                val characterAssetBytes = characterAssets[agent.id].orEmpty()

                when {
                    characterManifest != null &&
                        characterManifestBytesReady(characterManifest, characterAssetBytes) -> {
                        CharacterAvatar(
                            agentId = agent.id,
                            envelope = characterManifest,
                            assetBytes = characterAssetBytes,
                            currentState = agentStates[agent.id],
                            contentDescription = agent.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(32.dp)),
                        )
                        if (isThinking) ThinkingSpinnerOverlay()
                    }
                    imageData != null -> {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(imageData)
                                .memoryCacheKey("avatar:${agent.id}:$agentVersion")
                                .build(),
                            contentDescription = agent.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(32.dp)),
                        )
                        if (isThinking) ThinkingSpinnerOverlay()
                    }
                    else -> {
                        // No atlas and no image URL: fall back to the agent's
                        // emoji, or the first letter of their name tinted in
                        // theme color when no emoji is set. Keeps the avatar
                        // slot non-blank so the user always has a visual
                        // identifier even for misconfigured agents.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(agentColor.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val emoji = agent.emoji?.takeIf { it.isNotBlank() }
                            if (emoji != null) {
                                Text(
                                    text = emoji,
                                    fontSize = 64.sp,
                                )
                            } else {
                                val initial = (agent.name.takeIf { it.isNotBlank() } ?: agent.id)
                                    .firstOrNull()
                                    ?.uppercaseChar()
                                    ?.toString()
                                    ?: "?"
                                Text(
                                    text = initial,
                                    fontSize = 64.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = agentColor,
                                )
                            }
                        }
                    }
                }
            }

            // Tiny status strip under the GIF. Scrollable via flick so errors
            // or long replies can be read without stealing visual focus from
            // the avatar.
            if (isCurrentPage) {
                val statusText = when (voiceState) {
                    VoiceState.Idle -> "hold to talk"
                    VoiceState.Listening -> "listening…"
                    VoiceState.Sending -> "sending…"
                    VoiceState.Thinking -> "thinking…"
                    VoiceState.Speaking -> "speaking…"
                    VoiceState.Error -> "error"
                }

                val displayText = when {
                    voiceState == VoiceState.Listening && !liveTranscript.isNullOrBlank() -> liveTranscript!!
                    responseText != null -> responseText!!
                    else -> statusText
                }

                val textColor = when {
                    voiceState == VoiceState.Error -> Color.Red
                    voiceState == VoiceState.Idle -> agentColor.copy(alpha = 0.35f)
                    responseText != null && voiceState != VoiceState.Listening -> Color(0xFFCCDDCC)
                    else -> agentColor.copy(alpha = 0.65f)
                }

                val statusScrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp, start = 28.dp, end = 28.dp)
                        .fillMaxWidth()
                        .height(28.dp)
                        .verticalScroll(statusScrollState),
                ) {
                    Text(
                        text = displayText,
                        color = textColor,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Per-agent mailbox icons on the left edge — one small circle per
            // agent with unread mail, tinted with their theme color + emoji /
            // initial. Tap any to jump to that agent and auto-replay.
            val agentsWithMail = agents.filter { (unreadByAgent[it.id] ?: 0) > 0 }
            if (agentsWithMail.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    agentsWithMail.take(3).forEach { mailAgent ->
                        val mailColor = parseThemeColor(mailAgent.theme) ?: OmnitrixGreen
                        val avatarModel = remember(mailAgent.avatarUrl, avatarAssets) {
                            resolveAvatarModel(mailAgent.avatarUrl, avatarAssets)
                        }
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(mailColor.copy(alpha = 0.25f))
                                .border(1.5.dp, mailColor, CircleShape)
                                .clickable { viewModel.openMailboxFor(mailAgent.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (avatarModel != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(avatarModel)
                                        .build(),
                                    contentDescription = mailAgent.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape),
                                )
                            } else {
                                Text(
                                    text = mailAgent.emoji?.takeIf { it.isNotBlank() }
                                        ?: mailAgent.name.take(1).uppercase(),
                                    color = mailColor,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    if (agentsWithMail.size > 3) {
                        Text(
                            text = "+${agentsWithMail.size - 3}",
                            color = OmnitrixGreen,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }

        }
    }
        // Screen-fixed indicator overlay. Lives OUTSIDE the pager so chips
        // stay put while pages swipe — the only thing that changes is the
        // active-page highlight shifting between chips.
        AgentDialIndicatorOverlay(agents = agents, currentPage = pagerState.currentPage)
    }
}

/**
 * Screen-fixed overlay that paints the page-indicator chips in a ring around
 * the watch face. Lives outside the HorizontalPager so chips don't shift when
 * pages swipe — only their highlight/size state changes as the active page
 * moves.
 *
 * Chips use a **fixed 10° angular step** starting at 12 o'clock and going
 * clockwise. 18 agents cover the top semi-circle (12 → 3 → 6); 36 agents
 * complete a full revolution. More than 36 agents are still navigable via
 * swipe/rotary but won't have an indicator chip.
 */
@Composable
private fun AgentDialIndicatorOverlay(
    agents: List<PhoneBridge.Agent>,
    currentPage: Int,
) {
    if (agents.size <= 1) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val faceDiameterDp = if (maxWidth < maxHeight) maxWidth else maxHeight
        val faceRadiusDp = faceDiameterDp / 2
        // Pull the chip ring in slightly so the active (larger) chip still
        // fits entirely inside the display.
        val arcRadiusDp = faceRadiusDp - 12.dp
        val displayed = agents.take(36)

        displayed.forEachIndexed { index, a ->
            // Fixed 10° step: 18 slots → half circle (top → bottom via right
            // edge), 36 slots → full revolution.
            val angleDeg = -90f + index * 10f
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val offsetXDp = (arcRadiusDp.value * cos(angleRad).toFloat()).dp
            val offsetYDp = (arcRadiusDp.value * sin(angleRad).toFloat()).dp

            val dotColor = parseThemeColor(a.theme) ?: OmnitrixGreen
            val isCurrent = index == currentPage
            // Chip sizes shrunk ~20% from the previous vertical-column layout
            // so a full 24-dot ring doesn't visually collide.
            val dotSize = if (isCurrent) 16.dp else 11.dp
            val label =
                a.emoji?.takeIf { it.isNotBlank() }
                    ?: a.name.take(1).uppercase()

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = offsetXDp, y = offsetYDp)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(
                        if (isCurrent) dotColor.copy(alpha = 0.7f)
                        else dotColor.copy(alpha = 0.25f),
                    )
                    .border(
                        width = if (isCurrent) 1.5.dp else 1.dp,
                        color = if (isCurrent) dotColor
                        else dotColor.copy(alpha = 0.5f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = if (isCurrent) 9.sp else 7.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Trailing "build" page shown after the last agent on the scroll wheel. Prints
 * the Gradle-baked BUILD_STAMP so we can eyeball whether the watch is actually
 * running a fresh APK without plugging in adb. Rolls every `:app:installDebug`.
 */
@Composable
private fun BuildStampPage() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "build",
                color = OmnitrixGreen.copy(alpha = 0.55f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = BuildConfig.BUILD_STAMP,
                color = OmnitrixGreen,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                color = OmnitrixGreen.copy(alpha = 0.45f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Small orange spinner painted at the top-center of the avatar box to cue
 * "waiting on reply" without occluding the currently-rendering avatar frame.
 * Shared across all three avatar rendering paths (GIF / sprites / atlas).
 */
@Composable
private fun ThinkingSpinnerOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
            indicatorColor = Color(0xFFFFAA00),
            trackColor = Color.Black.copy(alpha = 0.45f),
        )
    }
}
