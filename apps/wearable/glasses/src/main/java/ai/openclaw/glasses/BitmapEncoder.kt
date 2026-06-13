package ai.openclaw.glasses

import android.graphics.Bitmap

/**
 * Encodes an Android [Bitmap] into the byte format consumed by
 * `frame.display.bitmap(x, y, w, 2, palette_offset, data)` — i.e. 1bpp,
 * MSB-first within each byte, row-major (left→right, top→bottom).
 *
 * Port of FrameDinoGame's `bmp_gen.py` (`load_image` + the byte-packing
 * loop in `scale_bmp`). Same dark-pixel-is-foreground convention: the
 * pixel's inverted luminosity is thresholded so dark drawings on a light
 * canvas turn into foreground bits — which is what you get when you draw
 * an icon with black ink onto a white Android Bitmap.
 *
 * Width MUST be a multiple of 8. Frame's bitmap rasteriser packs bits with
 * row-aligned bytes, so a non-multiple-of-8 width would silently shift
 * every row by a few pixels.
 */
object BitmapEncoder {
    /**
     * @param bitmap source image; width must be a multiple of 8
     * @param threshold 0..255 — pixels whose inverted luminosity is >= this
     *   become foreground bits. Default 128 = midpoint, matching bmp_gen.py.
     */
    fun encode1bpp(bitmap: Bitmap, threshold: Int = 128): ByteArray {
        require(bitmap.width % 8 == 0) {
            "bitmap width must be a multiple of 8, got ${bitmap.width}"
        }
        val w = bitmap.width
        val h = bitmap.height
        val rowBytes = w / 8
        val out = ByteArray(rowBytes * h)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var bitIdx = 0
        for (i in 0 until pixels.size) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val lum = (r + g + b) / 3
            if (255 - lum >= threshold) {
                val byteIdx = bitIdx ushr 3
                val bitInByte = 7 - (bitIdx and 7) // MSB-first
                out[byteIdx] = (out[byteIdx].toInt() or (1 shl bitInByte)).toByte()
            }
            bitIdx++
        }
        return out
    }
}
