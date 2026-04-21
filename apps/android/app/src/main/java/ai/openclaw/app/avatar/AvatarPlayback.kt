package ai.openclaw.app.avatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import ai.openclaw.displaykit.CharacterManifest
import ai.openclaw.displaykit.CharacterManifestEnvelope
import ai.openclaw.displaykit.FrameRef
import ai.openclaw.displaykit.FrameSource
import kotlinx.serialization.json.Json

/**
 * Bridges DisplayKit's pure-JVM model to the Android `Bitmap` world. Given
 * a [CharacterManifest] and the raw bytes for each `assets.refs` entry,
 * [BitmapFrameSource] resolves any [FrameRef] the player emits to a
 * concrete [Bitmap]:
 *
 * - **Sprite-style** frames reference a whole-image asset by key; the full
 *   decoded bitmap is returned.
 * - **Atlas-style** frames reference the atlas image and carry an
 *   `x/y/w/h` crop rect; the returned bitmap is a `createBitmap(src, x, y, w, h)`
 *   slice of the decoded atlas, cached per `(ref, rect)` pair.
 *
 * Mirror of the watch-side type in apps/wearable/.../AvatarPlayback.kt — a
 * shared Android UI module would deduplicate this, pending a refactor. Any
 * functional change here should also land there until the merge happens.
 */
class BitmapFrameSource(
    private val bytesByRef: Map<String, ByteArray>,
) : FrameSource<Bitmap> {
    private val decoded = mutableMapOf<String, Bitmap>()
    private val sliceCache = mutableMapOf<String, Bitmap>()

    override fun frame(ref: FrameRef): Bitmap? {
        val whole = decodedFor(ref.ref) ?: return null
        if (ref.x == null && ref.y == null && ref.w == null && ref.h == null) {
            return whole
        }
        val key = "${ref.ref}@${ref.x},${ref.y},${ref.w},${ref.h}"
        sliceCache[key]?.let { return it }
        val x = ref.x ?: 0
        val y = ref.y ?: 0
        val w = ref.w ?: (whole.width - x)
        val h = ref.h ?: (whole.height - y)
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > whole.width || y + h > whole.height) {
            Log.w(TAG, "slice out of bounds ref=${ref.ref} rect=($x,$y,$w,$h) size=(${whole.width},${whole.height})")
            return null
        }
        return try {
            val slice = Bitmap.createBitmap(whole, x, y, w, h)
            sliceCache[key] = slice
            slice
        } catch (e: Throwable) {
            Log.w(TAG, "slice failed for $key", e)
            null
        }
    }

    private fun decodedFor(refKey: String): Bitmap? {
        decoded[refKey]?.let { return it }
        val bytes = bytesByRef[refKey] ?: return null
        return try {
            val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bm != null) decoded[refKey] = bm
            bm
        } catch (e: Throwable) {
            Log.w(TAG, "decode failed for $refKey", e)
            null
        }
    }

    companion object {
        private const val TAG = "BitmapFrameSource"
    }
}

object CharacterManifestJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): CharacterManifestEnvelope? = try {
        json.decodeFromString(CharacterManifestEnvelope.serializer(), text)
    } catch (e: Throwable) {
        Log.w("CharacterManifestJson", "parse failed: ${e.message}")
        null
    }

    /** Pick the first mode in `manifest.modes` whose content is present. */
    fun pickMode(manifest: CharacterManifest): String? =
        manifest.modes.firstOrNull { manifest.content.containsKey(it) }
}

/** True once every asset ref in [envelope] has bytes in [assetBytes]. */
fun characterManifestBytesReady(
    envelope: CharacterManifestEnvelope,
    assetBytes: Map<String, ByteArray>,
): Boolean {
    val refs = envelope.manifest.assets.refs.keys
    if (refs.isEmpty()) return true
    return refs.all { assetBytes.containsKey(it) }
}
