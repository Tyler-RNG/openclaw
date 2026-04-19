package ai.openclaw.wear.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer

/**
 * Renders an animated GIF once end-to-end, then freezes on the final frame.
 *
 * Coil 3's `AnimatedImageDecoder.Factory` doesn't expose a per-request repeat
 * count, so for state-specific one-shot playback (e.g. the "thinking" avatar
 * that should animate once while the agent composes a reply, then hold) we
 * bypass Coil entirely and drive Android's platform `AnimatedImageDrawable`
 * through an `ImageView` with `repeatCount = 0`.
 *
 * Decoding is memoized on the bytes identity — swapping state re-decodes and
 * restarts the animation; staying on the same state across recompositions
 * reuses the same drawable (no animation restart).
 */
@Composable
internal fun OneShotGif(
    bytes: ByteArray,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val drawable = remember(bytes) {
        runCatching {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ImageDecoder.decodeDrawable(source).also {
                if (it is AnimatedImageDrawable) {
                    it.repeatCount = 0 // play once, then hold last frame
                    it.start()
                }
            }
        }.getOrNull()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                this.contentDescription = contentDescription
            }
        },
        update = { view ->
            if (drawable != null && view.drawable !== drawable) {
                view.setImageDrawable(drawable)
                if (drawable is AnimatedImageDrawable && !drawable.isRunning) {
                    drawable.start()
                }
            }
        },
    )
}
