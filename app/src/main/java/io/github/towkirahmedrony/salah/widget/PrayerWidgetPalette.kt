package io.github.towkirahmedrony.salah.widget

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.toArgb
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.theme.themeColorScheme
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.core.model.ThemePreset

/**
 * The colours one widget instance is drawn with.
 *
 * **Resolved through the app's own theme, not a copy of it.** [prayerWidgetPalette] reads
 * `themeColorScheme` — the function `SalahTheme` itself composes every screen from — so the widget's
 * text is the selected preset's exact colour rather than one chosen to look similar and drift when a
 * palette is retuned.
 *
 * **What a `RemoteViews` background can and cannot be.** The rounded card is a static drawable
 * resource ([background]), because a `RemoteViews` cannot be handed a `Drawable` and a shape's fill
 * cannot be swapped at runtime; that is why there are two of them, light and dark, selected with
 * `setImageViewResource`. Every other colour is applied through `setTextColor`, so a preset that is
 * not the default still re-tones the widget's text even though its card fill is fixed.
 */
data class PrayerWidgetPalette(
    /** The rounded card drawable the widget's background layer is given. */
    @param:DrawableRes val background: Int,
    @param:ColorInt val prayerName: Int,
    @param:ColorInt val status: Int,
    @param:ColorInt val label: Int,
    @param:ColorInt val countdown: Int,
    @param:ColorInt val boundary: Int,
)

/**
 * Whether [mode] resolves to a dark widget on a device whose own night mode is [systemNightMode].
 *
 * [ThemeMode.SYSTEM] follows the device, which is exactly what the app's own theme does; an explicit
 * choice overrides it, so a user who pinned Salah to dark gets a dark widget on a light phone.
 */
fun isDarkTheme(mode: ThemeMode, systemNightMode: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemNightMode
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** The colours [preset] draws, in the requested appearance. */
fun prayerWidgetPalette(preset: ThemePreset, dark: Boolean): PrayerWidgetPalette {
    val scheme = themeColorScheme(preset, dark)
    return PrayerWidgetPalette(
        background = if (dark) {
            R.drawable.prayer_widget_background_dark
        } else {
            R.drawable.prayer_widget_background_light
        },
        prayerName = scheme.onSurface.toArgb(),
        status = scheme.onSurfaceVariant.toArgb(),
        label = scheme.onSurfaceVariant.toArgb(),
        // The prayer's name and the countdown are the two things a glance needs, so they carry the
        // scheme's strongest content colours: `onSurface` for the name, `primary` for the number the
        // widget exists to show. Everything supporting them steps back to `onSurfaceVariant`.
        countdown = scheme.primary.toArgb(),
        boundary = scheme.onSurfaceVariant.toArgb(),
    )
}
