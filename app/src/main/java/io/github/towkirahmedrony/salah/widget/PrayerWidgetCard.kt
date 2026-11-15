package io.github.towkirahmedrony.salah.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Paints the widget's card: a rounded diagonal gradient, a soft glow and a few fine concentric
 * rings in the top corner, and a hairline border.
 *
 * It is a bitmap because a `RemoteViews` can neither change a shape's fill nor draw a gradient from
 * runtime colours. The bitmap is sized to the widget itself, so `fitXY` never stretches the corner
 * radius, and it is capped so the update stays far below the platform's bitmap budget.
 */
internal object PrayerWidgetCard {

    private const val MIN_SIDE_DP = 40
    private const val MAX_SIDE_PX = 640f
    private const val CORNER_DP = 28f
    private const val RING_ALPHA = 0x2A

    fun render(widthDp: Int, heightDp: Int, density: Float, palette: PrayerWidgetPalette): Bitmap {
        val rawWidth = widthDp.coerceAtLeast(MIN_SIDE_DP) * density
        val rawHeight = heightDp.coerceAtLeast(MIN_SIDE_DP) * density
        val shrink = min(1f, MAX_SIDE_PX / max(rawWidth, rawHeight))
        val width = (rawWidth * shrink).roundToInt().coerceAtLeast(1)
        val height = (rawHeight * shrink).roundToInt().coerceAtLeast(1)
        val unit = density * shrink

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()
        val bounds = RectF(0f, 0f, w, h)
        val radius = CORNER_DP * unit

        // 1. The gradient card.
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w, h, palette.cardStart, palette.cardEnd, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(bounds, radius, radius, fill)

        // Decorations are painted SRC_ATOP, so they only land on pixels the card already covers and
        // the rounded corners stay clean.
        val atop = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        val cx = w * 0.94f
        val cy = h * 0.02f

        // 2. A soft glow in the top-end corner.
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = atop
            shader = RadialGradient(
                cx,
                cy,
                max(w, h) * 0.85f,
                palette.cardGlow,
                ColorUtils.setAlphaComponent(palette.cardGlow, 0),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(bounds, glow)

        // 3. Fine concentric rings around the same corner.
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = atop
            style = Paint.Style.STROKE
            strokeWidth = max(1f, 1.2f * unit)
            color = ColorUtils.setAlphaComponent(palette.cardGlow, RING_ALPHA)
        }
        val shortSide = min(w, h)
        listOf(0.42f, 0.66f, 0.90f).forEach { factor ->
            canvas.drawCircle(cx, cy, shortSide * factor, ring)
        }

        // 4. The hairline border, inset by half its width so it is not clipped.
        val hairline = max(1f, unit)
        val inset = hairline / 2f
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = hairline
            color = palette.cardStroke
        }
        canvas.drawRoundRect(
            RectF(inset, inset, w - inset, h - inset),
            radius - inset,
            radius - inset,
            stroke,
        )
        return bitmap
    }
}
