package ai.openclaw.glasses

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Programmatic sprite library for the bitmap demos. Each function draws into a
 * fresh ARGB_8888 [Bitmap] using black ink on a white canvas — which the
 * encoder ([BitmapEncoder]) thresholds into the 1bpp byte stream consumed by
 * `frame.display.bitmap` (dark pixels become foreground bits).
 *
 * All sprite widths are multiples of 8 (enforced by the encoder).
 *
 * Antialiasing is disabled everywhere so the post-threshold edges land
 * exactly where Canvas drew them — no muddy half-tones that flip on or off
 * unpredictably at the encoder's 50% threshold.
 */
object Sprites {

    private fun fillPaint() = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    private fun strokePaint(stroke: Float = 2f) = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = stroke
        isAntiAlias = false
    }

    private fun newCanvas(width: Int, height: Int): Pair<Bitmap, Canvas> {
        require(width % 8 == 0) { "width must be multiple of 8, got $width" }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        return bmp to c
    }

    fun smiley(size: Int = 64): Bitmap {
        val (bmp, c) = newCanvas(size, size)
        val ink = fillPaint()
        val outline = strokePaint(stroke = 3f)
        c.drawCircle(size / 2f, size / 2f, size / 2f - 2f, outline)
        val eyeR = size / 12f
        c.drawCircle(size / 3f, size / 2.6f, eyeR, ink)
        c.drawCircle(2 * size / 3f, size / 2.6f, eyeR, ink)
        val mouth = RectF(size / 4f, size / 2.5f, 3 * size / 4f, 7 * size / 8f)
        c.drawArc(mouth, 20f, 140f, false, outline)
        return bmp
    }

    fun heart(size: Int = 64): Bitmap {
        val (bmp, c) = newCanvas(size, size)
        val ink = fillPaint()
        val w = size.toFloat()
        val h = size.toFloat()
        val path = Path().apply {
            moveTo(w / 2, h * 0.85f)
            cubicTo(w * 0.05f, h * 0.55f, w * 0.10f, h * 0.10f, w / 2, h * 0.30f)
            cubicTo(w * 0.90f, h * 0.10f, w * 0.95f, h * 0.55f, w / 2, h * 0.85f)
            close()
        }
        c.drawPath(path, ink)
        return bmp
    }

    /**
     * Full-screen 640×400 checkerboard with [cell]-px squares. Exercises the
     * chunked-upload path (~2 s @ MTU≈247 for the full 32 KB at 1bpp).
     */
    fun checkerboardFullScreen(cell: Int = 40): Bitmap {
        val (bmp, c) = newCanvas(640, 400)
        val ink = fillPaint()
        var gy = 0
        while (gy < 400) {
            var gx = 0
            while (gx < 640) {
                if (((gx / cell) + (gy / cell)) and 1 == 0) {
                    c.drawRect(gx.toFloat(), gy.toFloat(), (gx + cell).toFloat(), (gy + cell).toFloat(), ink)
                }
                gx += cell
            }
            gy += cell
        }
        return bmp
    }

    /** Filled circle, used as the bouncing-ball animation sprite. */
    fun ball(size: Int = 24): Bitmap {
        val (bmp, c) = newCanvas(size, size)
        c.drawCircle(size / 2f, size / 2f, size / 2f - 1f, fillPaint())
        return bmp
    }

    /**
     * Loading-spinner arc rotated to slot [idx] of [total]. Drawing the same
     * span-of-3-slots arc at a different starting angle each frame yields the
     * classic Chrome-style spinning indicator.
     */
    fun spinnerFrame(idx: Int, total: Int = 8, size: Int = 32): Bitmap {
        val (bmp, c) = newCanvas(size, size)
        val arc = strokePaint(stroke = 5f).apply { strokeCap = Paint.Cap.ROUND }
        val sweepDeg = 360f / total
        val rect = RectF(5f, 5f, size - 5f, size - 5f)
        c.drawArc(rect, sweepDeg * idx, sweepDeg * 3, false, arc)
        return bmp
    }

    /** Horizontal battery icon with body outline, terminal cap, and [fillPct] fill bar. */
    fun batteryAtFill(fillPct: Float, width: Int = 48, height: Int = 24): Bitmap {
        val (bmp, c) = newCanvas(width, height)
        val ink = fillPaint()
        val outline = strokePaint(stroke = 2f)
        c.drawRect(1f, 3f, (width - 5).toFloat(), (height - 3).toFloat(), outline)
        c.drawRect((width - 4).toFloat(), 8f, (width - 1).toFloat(), (height - 8).toFloat(), ink)
        val maxFillW = (width - 9).toFloat()
        val fillW = (maxFillW * fillPct.coerceIn(0f, 1f)).toInt()
        if (fillW > 0) {
            c.drawRect(4f, 7f, (4 + fillW).toFloat(), (height - 7).toFloat(), ink)
        }
        return bmp
    }
}
