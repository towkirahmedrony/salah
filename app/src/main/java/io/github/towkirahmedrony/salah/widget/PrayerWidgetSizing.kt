package io.github.towkirahmedrony.salah.widget

import android.util.TypedValue
import android.widget.RemoteViews
import io.github.towkirahmedrony.salah.R

/**
 * Sizes the compact layout's two large texts for the widget's real width.
 *
 * `autoSizeTextType` is deliberately not used: with a `wrap_content` height a `RemoteViews`
 * TextView keeps the height of the largest size it tried and then clips the shrunk glyphs. Here the
 * size is computed once from the width, so the view is measured at the size it is drawn at.
 */
internal fun RemoteViews.applyCompactTextSizes(widthDp: Int) {
    val available = (widthDp - HORIZONTAL_PADDING_DP).coerceAtLeast(60)
    // A bold "H:MM:SS" is about 3.6 em wide and a prayer name up to about 4.6 em.
    val countdown = (available / 4.0f).coerceIn(20f, 36f)
    val name = (available / 4.8f).coerceIn(20f, 30f)
    setTextViewTextSize(R.id.widget_countdown, TypedValue.COMPLEX_UNIT_SP, countdown)
    setTextViewTextSize(R.id.widget_prayer_name, TypedValue.COMPLEX_UNIT_SP, name)
}

private const val HORIZONTAL_PADDING_DP = 30
