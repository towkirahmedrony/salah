package io.github.towkirahmedrony.salah.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import io.github.towkirahmedrony.salah.MainActivity
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.domain.PeriodBoundary
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.domain.timeFormatterFor
import io.github.towkirahmedrony.salah.core.domain.widget.PrayerWidgetState
import io.github.towkirahmedrony.salah.core.domain.widget.PrayerWidgetStateResolver
import io.github.towkirahmedrony.salah.core.domain.widget.nextBoundaryAt
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.feature.common.labelRes
import io.github.towkirahmedrony.salah.navigation.SalahDestination
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders every placed Salah widget from the application's own data.
 *
 * **No prayer time is calculated here.** The location and the calculation settings are read from the
 * same DataStore the app uses, the schedule comes from the same [PrayerScheduleRepository] Home and
 * the reminders read, and the period is decided by [PrayerWidgetStateResolver]. There is one
 * prayer-time engine in Salah and the widget is a reader of its output, so the home screen and the
 * app cannot disagree about what time Fajr is.
 *
 * **Battery.** Nothing here runs on a timer. The countdown is a `Chronometer` that the launcher
 * advances itself, and [refreshAll] is invoked only when something actually changed: the widget was
 * added or resized, the device rebooted, the clock or time zone moved, the app recalculated, or the
 * single alarm scheduled for the next prayer boundary fired. Each invocation does a microsecond of
 * local arithmetic and no network call.
 *
 * **Failures are values.** Every read is offline and every failure mode of the repository already
 * arrives as a `PrayerScheduleState` value; a widget with no location, or with a timetable the engine
 * could not produce, renders *that* rather than a stale or invented time.
 *
 * Calls are serialised on [renderMutex]: a resize and a boundary alarm can arrive together, and the
 * last run must win rather than two `updateAppWidget` calls interleaving.
 */
class PrayerWidgetUpdater(
    private val context: Context,
    private val locations: LocationRepository,
    private val preferences: UserPreferencesRepository,
    private val schedules: PrayerScheduleRepository,
    private val resolver: PrayerWidgetStateResolver,
    private val scheduler: PrayerWidgetRefreshScheduler,
    private val clock: Clock,
) {

    private val renderMutex = Mutex()

    private val widgetManager: AppWidgetManager?
        get() = AppWidgetManager.getInstance(context)

    private val providerComponent: ComponentName
        get() = ComponentName(context, PrayerWidgetProvider::class.java)

    /**
     * Re-renders every placed widget and schedules the next boundary refresh.
     *
     * With no widget on any home screen this cancels the pending alarm and returns: Salah does not
     * keep waking the device for a widget nobody has placed.
     */
    suspend fun refreshAll() {
        renderMutex.withLock { renderLocked() }
    }

    private suspend fun renderLocked() {
        val manager = widgetManager
        if (manager == null) {
            scheduler.cancel()
            return
        }
        val ids = manager.getAppWidgetIds(providerComponent)
        if (ids.isEmpty()) {
            // No widget is placed any more, so there is nothing to draw and nobody to wake for it.
            scheduler.cancel()
            return
        }

        val appearance = readAppearance()
        val settings = preferences.prayerSettings.first()
        val location = locations.location.first()

        if (location == null) {
            // A location is the only input the engine cannot invent, and the app's gate asks for one
            // on first launch. The widget says so instead of showing a time for a place nobody chose.
            val presentation = presentationFor(
                state = PrayerWidgetState.NoLocation,
                appearance = appearance,
                settings = settings,
                location = null,
            )
            render(manager, ids, presentation, appearance)
            // Nothing to retry: a location is a choice only the user can make, and the app's own
            // launch and settings reconciliation re-render the widget the moment one exists. An
            // alarm here would wake the device for a value that cannot change on its own.
            scheduler.cancel()
            return
        }

        val now = ZonedDateTime.ofInstant(clock.instant(), location.zoneId)
        val today = PrayerScheduleRequest(
            date = now.toLocalDate(),
            location = location,
            profile = settings.toProfile(),
        )
        // Tomorrow's schedule is asked for through the same repository and engine: it is the only
        // honest way to close Isha's period, which ends at the next day's Fajr.
        val tomorrow = today.copy(date = today.date.plusDays(1))

        val todaySchedule = schedules.ensureSchedule(today).first().scheduleOrNull()
        val tomorrowSchedule = schedules.ensureSchedule(tomorrow).first().scheduleOrNull()

        val state = resolver.resolve(
            schedule = todaySchedule,
            now = now,
            nextDaySchedule = tomorrowSchedule,
        )

        render(
            manager = manager,
            ids = ids,
            presentation = presentationFor(state, appearance, settings, location),
            appearance = appearance,
        )
        scheduleNextRefresh(state)
    }

    private fun render(
        manager: AppWidgetManager,
        ids: IntArray,
        presentation: Presentation,
        appearance: Appearance,
    ) {
        ids.forEach { id ->
            manager.updateAppWidget(id, viewsFor(id, presentation, appearance))
        }
    }

    private fun viewsFor(id: Int, presentation: Presentation, appearance: Appearance): RemoteViews {
        val wide = isWide(id)
        val layout = if (wide) R.layout.prayer_widget_wide else R.layout.prayer_widget_small
        val views = RemoteViews(context.packageName, layout)
        val palette = appearance.palette

        // The card's appearance is chosen here, not in the layout: `setImageViewResource` is a
        // first-class RemoteViews API, so the light and dark cards need no resource qualifier and no
        // reflective call. A preset other than the default re-tones the text below, not the card.
        views.setImageViewResource(R.id.widget_background, palette.background)

        views.setTextViewText(R.id.widget_prayer_name, presentation.title)
        views.setTextColor(R.id.widget_prayer_name, palette.prayerName)

        views.setTextViewText(R.id.widget_status, presentation.status)
        views.setTextColor(R.id.widget_status, palette.status)

        views.setTextViewText(R.id.widget_remaining_label, presentation.countdownLabel)
        views.setTextColor(R.id.widget_remaining_label, palette.label)

        views.setTextColor(R.id.widget_countdown, palette.countdown)

        if (wide) {
            // The compact layout has no room for the boundary line and does not declare the view at
            // all, so the action is issued only for the layout that has it.
            views.setTextViewText(R.id.widget_boundary, presentation.detail.orEmpty())
            views.setTextColor(R.id.widget_boundary, palette.boundary)
            views.setViewVisibility(
                R.id.widget_boundary,
                if (presentation.detail == null) View.GONE else View.VISIBLE,
            )
        }

        val base = presentation.countdownBase
        if (base == null) {
            // Stopped and hidden rather than left running at zero: a countdown that has nothing to
            // count to is not a reading of anything.
            views.setChronometerCountDown(R.id.widget_countdown, true)
            views.setChronometer(R.id.widget_countdown, SystemClock.elapsedRealtime(), null, false)
            views.setViewVisibility(R.id.widget_countdown, View.GONE)
            views.setViewVisibility(R.id.widget_remaining_label, View.GONE)
        } else {
            // The platform's own countdown: it ticks in the launcher's process, so Salah is not
            // woken once a second to redraw a number.
            views.setChronometerCountDown(R.id.widget_countdown, true)
            views.setChronometer(R.id.widget_countdown, base, null, true)
            views.setViewVisibility(R.id.widget_countdown, View.VISIBLE)
            views.setViewVisibility(R.id.widget_remaining_label, View.VISIBLE)
        }

        views.setContentDescription(R.id.widget_root, presentation.contentDescription)
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent())
        return views
    }

    /** Whether the instance is wide enough for the two-column layout. */
    private fun isWide(id: Int): Boolean {
        val manager = widgetManager ?: return false
        val fromOptions = manager.getAppWidgetOptions(id)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        // A launcher that never reported options still has a declared minimum in the provider info,
        // which is the honest fallback rather than assuming small.
        val widthDp = if (fromOptions > 0) {
            fromOptions
        } else {
            manager.getAppWidgetInfo(id)?.minWidth ?: 0
        }
        return widthDp >= WIDE_MIN_WIDTH_DP
    }

    /** Opens Salah's prayer timetable, which shows the same period and the full day's times. */
    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_PRAYER_TIMES
            putExtra(MainActivity.EXTRA_DESTINATION, SalahDestination.PRAYER.route)
            // Delivered to the existing task when Salah is already open, so a tap cannot stack a
            // second copy of the app behind the first.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The next moment the widget's labels stop being true. */
    private fun scheduleNextRefresh(state: PrayerWidgetState) {
        val boundary = state.nextBoundaryAt
        if (boundary == null) {
            scheduleRecovery()
            return
        }
        // A couple of seconds after the boundary, not exactly on it: the boundary is half-open, so a
        // refresh that ran a moment *early* would still describe the period that is ending and would
        // then have to be repeated.
        scheduler.scheduleAt(boundary.toInstant().plusSeconds(BOUNDARY_SLACK_SECONDS))
    }

    /**
     * A coarse retry while the timetable itself is unavailable.
     *
     * Four times a day, not hourly: the state is reached only when a schedule cannot be produced at
     * all (an impossible latitude, a failed calculation), which cannot change minute to minute. The
     * app's own launch and settings reconciliation is what normally clears it, and the next
     * successful render replaces this with a precise prayer-boundary alarm.
     */
    private fun scheduleRecovery() {
        scheduler.scheduleAt(Instant.now(clock).plus(RECOVERY_INTERVAL))
    }

    private suspend fun readAppearance(): Appearance {
        val mode = preferences.themeMode.first()
        val preset = preferences.themePreset.first()
        val language = preferences.appLanguage.first()
        val nightMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = isDarkTheme(mode, nightMode)
        return Appearance(
            resources = resourcesFor(language),
            palette = prayerWidgetPalette(preset, dark),
        )
    }

    /**
     * The resources of the selected interface language.
     *
     * The app applies its language preference through a Compose local, which a `RemoteViews` update
     * never sees. Resolving the widget's own strings through a locale-specific context is what makes
     * the choice reach the home screen too — the same rule the app uses, applied wherever it renders.
     */
    private fun resourcesFor(language: AppLanguage): Resources {
        val tag = language.languageTag ?: return context.resources
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(configuration).resources
    }

    private fun presentationFor(
        state: PrayerWidgetState,
        appearance: Appearance,
        settings: PrayerSettings,
        location: LocationInfo?,
    ): Presentation {
        val resources = appearance.resources
        val locale = resources.configuration.locales[0]
        val formatter = location?.let {
            timeFormatterFor(zone = it.zoneId, locale = locale, preference = settings.timeFormat)
        }

        return when (state) {
            PrayerWidgetState.NoLocation -> unavailablePresentation(
                title = resources.getString(R.string.widget_no_location_title),
                body = resources.getString(R.string.widget_no_location_body),
            )

            is PrayerWidgetState.Unavailable -> unavailablePresentation(
                title = resources.getString(R.string.widget_unavailable_title),
                body = resources.getString(R.string.widget_unavailable_body),
            )

            is PrayerWidgetState.Active -> {
                val title = resources.getString(state.prayer.labelRes())
                val status = resources.getString(R.string.widget_status_active)
                Presentation(
                    title = title,
                    status = status,
                    countdownLabel = resources.getString(R.string.widget_remaining_label),
                    countdownBase = countdownBaseFor(state.remaining),
                    detail = boundaryLine(state.endsWith, formatter, resources),
                    contentDescription = "$title, $status",
                )
            }

            is PrayerWidgetState.Upcoming -> {
                val title = resources.getString(state.prayer.labelRes())
                val status = resources.getString(R.string.widget_status_upcoming)
                Presentation(
                    title = title,
                    status = status,
                    countdownLabel = resources.getString(R.string.widget_starts_in_label),
                    countdownBase = countdownBaseFor(state.remaining),
                    detail = boundaryLine(
                        boundary = PeriodBoundary.Prayer(prayer = state.prayer, time = state.startsAt),
                        formatter = formatter,
                        resources = resources,
                    ),
                    contentDescription = "$title, $status",
                )
            }
        }
    }

    private fun unavailablePresentation(title: String, body: String): Presentation = Presentation(
        title = title,
        status = body,
        countdownLabel = "",
        countdownBase = null,
        detail = null,
        contentDescription = "$title, $body",
    )

    /**
     * The `Chronometer` base for [remaining].
     *
     * `Chronometer` counts down from a base expressed on `elapsedRealtime`, so the widget hands the
     * platform the instant the boundary arrives on that clock instead of redrawing a formatted string
     * itself. A negative remaining time is clamped to zero rather than allowed to count upwards.
     */
    private fun countdownBaseFor(remaining: Duration): Long =
        SystemClock.elapsedRealtime() + remaining.toMillis().coerceAtLeast(0L)

    /** The closing (or opening) boundary as `"Maghrib at 5:42 PM"`. */
    private fun boundaryLine(
        boundary: PeriodBoundary,
        formatter: DateTimeFormatter?,
        resources: Resources,
    ): String? {
        if (formatter == null) return null
        return when (boundary) {
            is PeriodBoundary.Prayer -> resources.getString(
                if (boundary.isNextDay) {
                    R.string.widget_boundary_prayer_next_day
                } else {
                    R.string.widget_boundary_prayer
                },
                resources.getString(boundary.prayer.labelRes()),
                formatter.format(boundary.time),
            )

            is PeriodBoundary.Solar -> resources.getString(
                R.string.widget_boundary_event,
                resources.getString(boundary.event.labelRes()),
                formatter.format(boundary.time),
            )
        }
    }

    private fun PrayerScheduleState.scheduleOrNull(): PrayerSchedule? =
        (this as? PrayerScheduleState.Available)?.schedule

    /** The strings and colours one render pass uses. */
    private data class Appearance(
        val resources: Resources,
        val palette: PrayerWidgetPalette,
    )

    /**
     * One widget's already-resolved text.
     *
     * Resolved here rather than in the layout because a `RemoteViews` layout is inflated in the
     * launcher's process: its own `@string/...` references would be resolved with the launcher's
     * configuration, not with the language this user chose inside Salah.
     */
    private data class Presentation(
        val title: String,
        val status: String,
        val countdownLabel: String,
        /** `elapsedRealtime`-based `Chronometer` base, or `null` when there is nothing to count to. */
        val countdownBase: Long?,
        val detail: String?,
        val contentDescription: String,
    )

    private companion object {
        const val REQUEST_CODE_OPEN = 0x5A1B

        const val ACTION_OPEN_PRAYER_TIMES =
            "io.github.towkirahmedrony.salah.action.OPEN_PRAYER_TIMES"

        /** Width, in dp, at which the widget switches to its two-column layout. */
        const val WIDE_MIN_WIDTH_DP = 250

        const val BOUNDARY_SLACK_SECONDS = 2L

        val RECOVERY_INTERVAL: Duration = Duration.ofHours(6)
    }
}
