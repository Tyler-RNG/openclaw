package ai.openclaw.app.ui

import ai.openclaw.app.diag.PhoneDiagLog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fallback avatar renderer used when an agent has no sprite / atlas manifest.
 *
 * Hierarchy (most → least preferred):
 *   1. `avatarUrl`  — fetched + decoded to a [Bitmap] once and cached in
 *      [remember] keyed on the URL. Shown as `ContentScale.Crop`.
 *   2. `emoji`      — single-character drawn in the centre of the box.
 *   3. Initial      — first grapheme of `name` or `id`, in the theme color.
 *
 * The agent's name is rendered by the surrounding dial layout separately,
 * so this composable intentionally renders nothing below the avatar itself.
 */
@Composable
fun AgentAvatarFallback(
    agentName: String?,
    agentId: String,
    emoji: String?,
    avatarUrl: String?,
    themeColor: Color,
    modifier: Modifier = Modifier,
) {
    val displayLabel = agentName?.takeIf { it.isNotBlank() } ?: agentId
    val imageBitmap = rememberRemoteBitmap(avatarUrl)

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            imageBitmap != null -> {
                Image(
                    bitmap = imageBitmap.asImageBitmap(),
                    contentDescription = displayLabel,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            !emoji.isNullOrBlank() -> {
                Text(
                    text = emoji,
                    fontSize = 96.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            else -> {
                // Last-resort: the first grapheme of the agent's display name,
                // tinted in theme color. Keeps the slot non-blank so the user
                // always has a visible identifier, even on misconfigured
                // agents with no emoji and no image.
                val initial = displayLabel.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                Text(
                    text = initial,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                    color = themeColor,
                )
            }
        }
    }
}

/**
 * Fetch [url] once and cache the decoded [Bitmap] in composition-scoped
 * memory keyed on the URL. Returns null while loading or on any error
 * (DNS / HTTP / decode). All fetch attempts log under the `avatar` tag
 * so we can see why a fallback didn't land.
 */
@Composable
private fun rememberRemoteBitmap(url: String?): Bitmap? {
    if (url.isNullOrBlank()) return null
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        val fetched = try {
            withContext(Dispatchers.IO) { fetchAndDecode(url) }
        } catch (e: Throwable) {
            PhoneDiagLog.warn("avatar", "remote bitmap threw ${e.javaClass.simpleName}: ${url.take(60)}")
            null
        }
        bitmap = fetched
    }
    return bitmap
}

private fun fetchAndDecode(url: String): Bitmap? {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = 10_000
        requestMethod = "GET"
    }
    return try {
        val code = conn.responseCode
        if (code !in 200..299) {
            PhoneDiagLog.warn("avatar", "remote bitmap HTTP $code: ${url.take(60)}")
            return null
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp == null) {
            PhoneDiagLog.warn("avatar", "remote bitmap decode failed (${bytes.size}B): ${url.take(60)}")
        } else {
            PhoneDiagLog.info("avatar", "remote bitmap ok (${bytes.size / 1000}KB ${bmp.width}x${bmp.height})")
        }
        bmp
    } finally {
        conn.disconnect()
    }
}
