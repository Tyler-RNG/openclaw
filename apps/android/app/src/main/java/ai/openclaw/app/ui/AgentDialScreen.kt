package ai.openclaw.app.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.voice.VoiceConversationRole

/**
 * Phone-side agent dial. Horizontal pager with one page per agent; each page
 * shows the agent's animated avatar (via DisplayKit through [CharacterAvatar])
 * with a theme-colored ring, plus emoji fallback until the manifest's asset
 * bytes arrive from the gateway.
 *
 * Functionally mirrors the Wear OS AgentDialScreen but targets phone-screen
 * layout: single large avatar, no rotary-scroll plumbing, tap-to-record
 * (tap the avatar to start a voice message to that agent in place; tap
 * again to stop). The reply plays back through the phone's voice-reply
 * speaker regardless of the visible tab.
 */
@Composable
fun AgentDialScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
  val agents by viewModel.dialAgents.collectAsState()
  val characterManifests by viewModel.characterManifests.collectAsState()
  val characterAssets by viewModel.characterAssets.collectAsState()
  val agentStates by viewModel.agentStates.collectAsState()
  val agentMarkerSignals by viewModel.agentMarkerSignals.collectAsState()
  val activeAgentId by viewModel.activeAgentId.collectAsState()
  val micIsListening by viewModel.micIsListening.collectAsState()
  val micIsSending by viewModel.micIsSending.collectAsState()
  val micConversation by viewModel.micConversation.collectAsState()

  // Most recent user + assistant entry from the voice transcript, shown
  // beneath the active agent so the press-and-hold flow has visible text
  // feedback without having to leave the dial for the voice tab.
  val lastUser = remember(micConversation) {
    micConversation.lastOrNull { it.role == VoiceConversationRole.User }
  }
  val lastAssistant = remember(micConversation) {
    micConversation.lastOrNull { it.role == VoiceConversationRole.Assistant }
  }

  if (agents.isEmpty()) {
    EmptyDial(modifier = modifier)
    return
  }

  val pagerState = rememberPagerState(pageCount = { agents.size })

  Box(modifier = modifier.fillMaxSize()) {
    HorizontalPager(
      state = pagerState,
      modifier = Modifier.fillMaxSize(),
    ) { pageIndex ->
      val agent = agents[pageIndex]
      val themeColor = remember(agent.theme) { parseThemeColor(agent.theme) } ?: defaultThemeColor
      val envelope = characterManifests[agent.id]
      val assetBytes = characterAssets[agent.id].orEmpty()

      Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        // "New chat" pill — rotates this agent's session key so the next
        // user turn starts from an empty gateway history. Sits just above
        // the press-to-talk ring so it's discoverable without crowding the
        // avatar. Per-agent: pressing this only resets the current dial
        // page's agent, leaving other agents' conversations untouched.
        Row(
          modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(themeColor.copy(alpha = 0.12f))
            .border(1.dp, themeColor.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .clickable { viewModel.newSessionForAgent(agent.id) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "Start new chat with ${agent.name ?: agent.id}",
            modifier = Modifier.size(16.dp),
            tint = themeColor,
          )
          Text(
            text = "New chat",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = themeColor,
          )
        }
        Spacer(modifier = Modifier.height(14.dp))

        // Theme ring around the avatar — subtle gradient so it feels alive
        // without competing with the animation.
        Box(
          contentAlignment = Alignment.Center,
          modifier =
            Modifier
              .size(280.dp)
              .border(
                width = 3.dp,
                brush =
                  Brush.radialGradient(
                    colors = listOf(themeColor, themeColor.copy(alpha = 0.25f), Color.Transparent),
                  ),
                shape = CircleShape,
              )
              .pointerInput(agent.id) {
                detectTapGestures(
                  onPress = {
                    // Press-and-hold: start recording on press, stop on
                    // release or cancellation. Matches the watch's
                    // push-to-talk protocol — the recognizer drains what
                    // was captured and the phone ships it to this agent.
                    viewModel.startVoiceForAgent(agent.id)
                    tryAwaitRelease()
                    viewModel.stopVoiceForAgent(agent.id)
                  },
                )
              },
        ) {
          Box(
            modifier =
              Modifier
                .size(248.dp)
                .clip(RoundedCornerShape(54.dp))
                .background(themeColor.copy(alpha = 0.08f))
                .border(2.dp, themeColor.copy(alpha = 0.55f), RoundedCornerShape(54.dp)),
            contentAlignment = Alignment.Center,
          ) {
            if (envelope != null) {
              CharacterAvatar(
                agentId = agent.id,
                envelope = envelope,
                assetBytes = assetBytes,
                currentState = agentStates[agent.id],
                contentDescription = agent.name ?: agent.id,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(52.dp)),
                markerSignal = agentMarkerSignals[agent.id],
              )
            } else {
              // No atlas manifest — fall back to image → emoji → letter
              // initial. The agent's name is rendered by the surrounding
              // dial layout below, so [AgentAvatarFallback] only draws the
              // avatar slot itself.
              Box(
                modifier = Modifier
                  .fillMaxSize()
                  .background(themeColor.copy(alpha = 0.08f)),
              ) {
                AgentAvatarFallback(
                  agentName = agent.name,
                  agentId = agent.id,
                  emoji = agent.emoji,
                  avatarUrl = agent.avatarUrl,
                  themeColor = themeColor,
                  modifier = Modifier.fillMaxSize(),
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
          text = agent.name ?: agent.id,
          fontSize = 24.sp,
          fontWeight = FontWeight.SemiBold,
          fontFamily = FontFamily.SansSerif,
          textAlign = TextAlign.Center,
        )
        val subtitle = agent.title
        if (!subtitle.isNullOrBlank()) {
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = subtitle,
            fontSize = 14.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center,
          )
        }
        Spacer(modifier = Modifier.height(12.dp))
        // Voice-mode status (this agent only). Priority: sending > listening;
        // otherwise fall back to the avatar animation state, matching the
        // pre-Phase-4 behavior.
        val voiceStatus = when {
          activeAgentId != agent.id -> null
          micIsSending -> "Sending…"
          micIsListening -> "Listening…"
          else -> null
        }
        val statusLabel = voiceStatus ?: agentStates[agent.id]?.takeIf { it.isNotBlank() }
        if (statusLabel != null) {
          Text(
            text = statusLabel,
            fontSize = 12.sp,
            color = themeColor,
            textAlign = TextAlign.Center,
          )
        }

        // Transcript preview for the currently-active agent only. Keeps the
        // inactive pages visually clean; shows the last thing the user said
        // (muted) and the last assistant reply (highlighted, live while
        // streaming).
        if (activeAgentId == agent.id && (lastUser != null || lastAssistant != null)) {
          Spacer(modifier = Modifier.height(20.dp))
          lastUser?.let { entry ->
            Text(
              text = "You: ${entry.text}",
              fontSize = 13.sp,
              color = Color.Gray,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(horizontal = 12.dp),
            )
          }
          lastAssistant?.let { entry ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = entry.text,
              fontSize = 15.sp,
              color = if (entry.isStreaming) themeColor else Color.White,
              fontWeight = FontWeight.Normal,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(horizontal = 12.dp),
            )
          }
        }
      }
    }

    // Page indicator dots along the bottom so users know there are more agents
    // beyond the current page.
    PageIndicator(
      pageCount = agents.size,
      currentPage = pagerState.currentPage,
      activeColor = run {
        val active = agents.getOrNull(pagerState.currentPage)
        remember(active?.theme) { parseThemeColor(active?.theme) } ?: defaultThemeColor
      },
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = 24.dp)
          .fillMaxWidth(),
    )
  }
}

@Composable
private fun EmptyDial(modifier: Modifier = Modifier) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = "No agents yet",
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = "Connect to a gateway to see your agents.",
        fontSize = 14.sp,
        color = Color.Gray,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
private fun PageIndicator(
  pageCount: Int,
  currentPage: Int,
  activeColor: Color,
  modifier: Modifier = Modifier,
) {
  androidx.compose.foundation.layout.Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (i in 0 until pageCount) {
      val color =
        if (i == currentPage) activeColor else activeColor.copy(alpha = 0.3f)
      Box(
        modifier =
          Modifier
            .size(if (i == currentPage) 10.dp else 6.dp)
            .clip(CircleShape)
            .background(color),
      )
      if (i != pageCount - 1) {
        Spacer(modifier = Modifier.size(6.dp))
      }
    }
  }
}

private val defaultThemeColor = Color(0xFF4CFF7E)

private fun parseThemeColor(theme: String?): Color? {
  if (theme.isNullOrBlank()) return null
  return try {
    Color(AndroidColor.parseColor(if (theme.startsWith("#")) theme else "#$theme"))
  } catch (_: Throwable) {
    null
  }
}
