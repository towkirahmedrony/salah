package io.github.towkirahmedrony.salah.core.domain.widget

import io.github.towkirahmedrony.salah.core.domain.PeriodBoundary
import io.github.towkirahmedrony.salah.core.model.PrayerName
import java.time.Duration
import java.time.ZonedDateTime

/**
 * What the home-screen widget should read at a given moment.
 *
 * A value type, not a rendering concern: the widget's `RemoteViews` are built from this, so the
 * decision of *what* is true is testable on the JVM and the decision of how it looks stays on the
 * Android side. It is derived from [io.github.towkirahmedrony.salah.core.domain.CurrentPrayerStatus]
 * by [PrayerWidgetStateResolver] and carries no calculation of its own.
 *
 * **[Active] and [Upcoming] are both normal.** Under the convention Salah uses, Fajr's period closes
 * at sunrise and Dhuhr's does not open until the sun has passed its zenith, so the middle of the
 * morning belongs to no obligatory prayer's window. The widget then names the prayer that opens next
 * and counts down to it, rather than claiming a period that is not open.
 */
sealed interface PrayerWidgetState {

    /** No location is configured, so no prayer time can be calculated. */
    data object NoLocation : PrayerWidgetState

    /**
     * A prayer period is open right now.
     *
     * @param startedAt when the period began, or `null` when it began before the schedule in hand —
     *   the previous day's Isha, which is still running in the small hours.
     * @param endsAt when the period closes.
     * @param endsWith the boundary that closes it: another prayer, or sunrise.
     * @param remaining time from the resolved moment to [endsAt].
     */
    data class Active(
        val prayer: PrayerName,
        val startedAt: ZonedDateTime?,
        val endsAt: ZonedDateTime,
        val endsWith: PeriodBoundary,
        val remaining: Duration,
    ) : PrayerWidgetState

    /**
     * No prayer period is open; [prayer] opens at [startsAt].
     *
     * A real reading of the timetable between two periods, not an error.
     */
    data class Upcoming(
        val prayer: PrayerName,
        val startsAt: ZonedDateTime,
        val remaining: Duration,
    ) : PrayerWidgetState

    /** The timetable cannot answer, with a reason for diagnostics. Never a guessed time. */
    data class Unavailable(val detail: String? = null) : PrayerWidgetState
}

/**
 * The instant at which this state stops being true, or `null` when nothing is being counted to.
 *
 * The widget schedules one refresh at this instant: it is the moment the label and the countdown
 * must change, and the only moment at which they can. Nothing polls in between — the countdown
 * itself is drawn by the platform's `Chronometer`.
 */
val PrayerWidgetState.nextBoundaryAt: ZonedDateTime?
    get() = when (this) {
        is PrayerWidgetState.Active -> endsAt
        is PrayerWidgetState.Upcoming -> startsAt
        PrayerWidgetState.NoLocation, is PrayerWidgetState.Unavailable -> null
    }

/** The time being counted down to, or `null` when there is none. */
val PrayerWidgetState.remainingUntilBoundary: Duration?
    get() = when (this) {
        is PrayerWidgetState.Active -> remaining
        is PrayerWidgetState.Upcoming -> remaining
        PrayerWidgetState.NoLocation, is PrayerWidgetState.Unavailable -> null
    }
