package io.github.towkirahmedrony.salah.widget

import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.annotation.ColorInt
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import io.github.towkirahmedrony.salah.core.designsystem.theme.themeColorScheme
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.core.model.ThemePreset

/**
 * The colours one widget instance is drawn with.
 *
 * **Material You first, the app's palette otherwise.** On Android 12 and later [prayerWidgetPalette]
 * resolves `dynamicLightColorScheme`/`dynamicDarkColorScheme`; below that, or without Material You,
 * it uses `themeColorScheme`, the function `SalahTheme` composes every screen from, so the widget
 * always matches the selected preset.
 *
 * **A card, not a flat tile.** A `RemoteViews` cannot change a shape's fill, so the card is a bitmap
 * painted at bind time by [PrayerWidgetCard] from [cardStart], [cardEnd], [cardGlow] and
 * [cardStroke]. Because the bitmap is drawn from the active scheme, the gradient follows the
 * wallpaper or the chosen preset exactly like the text does.
 *
 * - Light appearance: a deep gradient of the scheme's `primary` with `onPrimary` text.
 * - Dark appearance: a dark surface tinted with `primary`, with the countdown in the accent colour.
 */
data class PrayerWidgetPalette(
    @param:ColorInt val cardStart: Int,
    @param:ColorInt val cardEnd: Int,
    @param:ColorInt val cardGlow: Int,
    @param:ColorInt val cardStroke: Int,
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
    return if (dark) darkPalette(scheme) else lightPalette(scheme)
}

private fun lightPalette(scheme: ColorScheme): PrayerWidgetPalette {
    val primary = scheme.primary.toArgb()
    val onPrimary = scheme.onPrimary.toArgb()
    return PrayerWidgetPalette(
        cardStart = ColorUtils.blendARGB(primary, Color.WHITE, 0.06f),
        cardEnd = ColorUtils.blendARGB(primary, Color.BLACK, 0.38f),
        cardGlow = ColorUtils.setAlphaComponent(Color.WHITE, 0x38),
        cardStroke = ColorUtils.setAlphaComponent(onPrimary, 0x30),
        prayerName = onPrimary,
        status = ColorUtils.setAlphaComponent(onPrimary, 0xE0),
        label = ColorUtils.setAlphaComponent(onPrimary, 0xC8),
        countdown = onPrimary,
        boundary = ColorUtils.setAlphaComponent(onPrimary, 0xE0),
    )
}

private fun darkPalette(scheme: ColorScheme): PrayerWidgetPalette {
    val surface = scheme.surface.toArgb()
    val primary = scheme.primary.toArgb()
    return PrayerWidgetPalette(
        cardStart = ColorUtils.blendARGB(surface, primary, 0.22f),
        cardEnd = ColorUtils.blendARGB(surface, primary, 0.05f),
        cardGlow = ColorUtils.setAlphaComponent(primary, 0x40),
        cardStroke = ColorUtils.setAlphaComponent(scheme.onSurface.toArgb(), 0x24),
        prayerName = scheme.onSurface.toArgb(),
        status = scheme.onSurfaceVariant.toArgb(),
        label = scheme.onSurfaceVariant.toArgb(),
        // The number the widget exists to show carries the accent.
        countdown = primary,
        boundary = scheme.onSurfaceVariant.toArgb(),
    )
}
