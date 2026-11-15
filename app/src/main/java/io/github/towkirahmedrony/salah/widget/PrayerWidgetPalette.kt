package io.github.towkirahmedrony.salah.widget

import android.content.Context
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.theme.themeColorScheme
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.core.model.ThemePreset

/**
 * The colours one widget instance is drawn with.
 *
 * **Material You first, the app's palette otherwise.** On Android 12 and later [prayerWidgetPalette]
 * resolves `dynamicLightColorScheme`/`dynamicDarkColorScheme` — the wallpaper's own tonal palettes —
 * so the card, its text and its accent take the same colours as the rest of the system and re-tone
 * themselves when the wallpaper or the system accent changes. Below Android 12, and on devices without
 * Material You, it falls back to `themeColorScheme`, the function `SalahTheme` composes every screen
 * from, so the widget still matches the selected preset exactly rather than drifting from it.
 *
 * **What a `RemoteViews` background can and cannot be.** A `RemoteViews` cannot be handed a `Drawable`
 * and a shape's fill cannot be swapped at runtime, so the rounded card stays two static drawables —
 * light and dark, selected with `setImageViewResource`. Their fill and hairline are colour resources
 * that `values-v31` replaces with the wallpaper's neutral tones, which is how the card itself becomes
 * dynamic while the shape stays a resource. Every text colour is applied through `setTextColor`, and
 * the countdown carries the scheme's accent, so the widget re-tones even when the card's shape is
 * fixed.
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

/**
 * The colours [preset] draws, in the requested appearance.
 *
 * [context] is only read on Android 12+ (the dynamic schemes are generated from the system's
 * wallpaper-derived palettes); it is passed rather than held so the palette stays a pure value the
 * updater can compute on every bind.
 */
fun prayerWidgetPalette(
    context: Context,
    preset: ThemePreset,
    dark: Boolean,
): PrayerWidgetPalette {
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        themeColorScheme(preset, dark)
    }
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
        // widget exists to show — the accent follows the wallpaper instead of a fixed gold.
        // Everything supporting them steps back to `onSurfaceVariant`.
        countdown = scheme.primary.toArgb(),
        boundary = scheme.onSurfaceVariant.toArgb(),
    )
}
