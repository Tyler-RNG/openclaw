package ai.openclaw.wear.ui

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.gif.AnimatedImageDecoder
import coil3.ImageLoader
import ai.openclaw.wear.PhoneBridge
import ai.openclaw.wear.VoiceState
import ai.openclaw.wear.WearViewModel
import android.util.Base64
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
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
        raw.startsWith("wear-asset:avatar:") -> {
            val id = raw.removePrefix("wear-asset:avatar:")
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

    // Check if default GIF exists in res/raw
    val defaultGifResId = remember {
        context.resources.getIdentifier("default_agent", "raw", context.packageName)
    }
    val hasDefaultGif = defaultGifResId != 0

    // Image loader with GIF support
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components { add(AnimatedImageDecoder.Factory()) }
            .build()
    }

    val pagerState = rememberPagerState(pageCount = { agents.size })
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val unreadByAgent by viewModel.unreadByAgent.collectAsState()
    val pendingMailJump by viewModel.pendingMailJump.collectAsState()
    val avatarAssets by viewModel.avatarAssets.collectAsState()

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

    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .onRotaryScrollEvent { event ->
                coroutineScope.launch {
                    val delta = event.verticalScrollPixels
                    val current = pagerState.currentPage
                    if (delta > 0 && current < agents.size - 1) {
                        pagerState.animateScrollToPage(current + 1)
                    } else if (delta < 0 && current > 0) {
                        pagerState.animateScrollToPage(current - 1)
                    }
                }
                true
            }
            .focusable(),
    ) { pageIndex ->
        val agent = agents[pageIndex]
        val isCurrentPage = pagerState.currentPage == pageIndex
        val agentColor = parseThemeColor(agent.theme) ?: OmnitrixGreen
        val resolvedAvatar = remember(agent.avatarUrl, avatarAssets) {
            resolveAvatarModel(agent.avatarUrl, avatarAssets)
        }

        // Per-agent state — "thinking" no longer bleeds across the dial.
        val voiceState = agentVoiceStates[agent.id] ?: VoiceState.Idle
        val responseText = agentResponseTexts[agent.id]
        val isActive = voiceState == VoiceState.Listening ||
            voiceState == VoiceState.Thinking ||
            voiceState == VoiceState.Sending

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            // Outer glow ring using agent theme color
            val ringColor = when {
                isCurrentPage && voiceState == VoiceState.Error -> Color.Red
                isCurrentPage && isActive -> agentColor
                isCurrentPage && voiceState == VoiceState.Thinking -> Color(0xFFFFAA00)
                else -> agentColor.copy(alpha = 0.6f)
            }
            Box(
                modifier = Modifier
                    .size(170.dp)
                    .border(
                        width = 2.dp,
                        brush = Brush.radialGradient(
                            colors = listOf(ringColor, ringColor.copy(alpha = 0.2f), Color.Black),
                        ),
                        shape = CircleShape,
                    ),
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            ) {
                // Agent icon: GIF/image or placeholder — tap to activate
                val iconBorderColor = when {
                    isCurrentPage && voiceState == VoiceState.Listening -> agentColor
                    isCurrentPage && voiceState == VoiceState.Thinking -> Color(0xFFFFAA00)
                    isCurrentPage && voiceState == VoiceState.Error -> Color.Red
                    else -> agentColor.copy(alpha = 0.6f)
                }

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .then(if (isCurrentPage && isActive) Modifier.scale(pulseScale) else Modifier)
                        .clip(RoundedCornerShape(16.dp))
                        .background(agentColor.copy(alpha = 0.1f))
                        .border(2.dp, iconBorderColor, RoundedCornerShape(16.dp))
                        .pointerInput(isCurrentPage) {
                            if (isCurrentPage) {
                                detectTapGestures(
                                    onPress = {
                                        viewModel.startPushToTalk(pageIndex)
                                        tryAwaitRelease()
                                        viewModel.endPushToTalk()
                                    },
                                )
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Show gateway-supplied avatar (URL or inlined data URI),
                    // else bundled default GIF, else emoji/initials fallback below.
                    val imageData: Any? = when {
                        resolvedAvatar != null -> resolvedAvatar
                        hasDefaultGif -> "android.resource://${context.packageName}/$defaultGifResId"
                        else -> null
                    }
                    if (imageData != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(imageData)
                                .build(),
                            imageLoader = imageLoader,
                            contentDescription = agent.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(14.dp)),
                        )
                    } else {
                        // Fallback: emoji or initials
                        val label = when {
                            isCurrentPage && voiceState == VoiceState.Listening -> "MIC"
                            isCurrentPage && voiceState == VoiceState.Thinking -> "..."
                            isCurrentPage && voiceState == VoiceState.Sending -> "..."
                            isCurrentPage && voiceState == VoiceState.Speaking -> ">>>"
                            !agent.emoji.isNullOrBlank() -> agent.emoji!!
                            else -> agent.name.take(2).uppercase()
                        }
                        Text(
                            text = label,
                            color = agentColor,
                            fontSize = if (agent.emoji != null) 28.sp else 22.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Agent name
                Text(
                    text = agent.name,
                    color = agentColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (!agent.title.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = agent.title,
                        color = agentColor.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Status / transcript / response
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
                        voiceState == VoiceState.Idle -> agentColor.copy(alpha = 0.4f)
                        responseText != null && voiceState != VoiceState.Listening -> Color(0xFFCCDDCC)
                        else -> agentColor.copy(alpha = 0.7f)
                    }

                    Text(
                        text = displayText,
                        color = textColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
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
                                    imageLoader = imageLoader,
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

            // Page indicator dots at bottom
            if (agents.size > 1) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 12.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        agents.forEachIndexed { index, a ->
                            val dotColor = parseThemeColor(a.theme) ?: OmnitrixGreen
                            Box(
                                modifier = Modifier
                                    .size(if (index == pagerState.currentPage) 6.dp else 4.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (index == pagerState.currentPage) dotColor
                                        else dotColor.copy(alpha = 0.3f),
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}
