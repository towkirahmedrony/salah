package io.github.towkirahmedrony.salah.core.domain.widget

import io.github.towkirahmedrony.salah.core.domain.CurrentPrayerPeriodResolver
import io.github.towkirahmedrony.salah.core.domain.CurrentPrayerStatus
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import java.time.ZonedDateTime

/**
 * Decides what the widget reads at [resolve]'s moment.
 *
 * **This is not a second prayer-time implementation.** It asks
 * [CurrentPrayerPeriodResolver] — the same object Home, the Prayer screen and the reminders read —
 * which period is open, and only re-shapes the answer into [PrayerWidgetState]. The schedule it is
 * given was produced by the one engine in the application
 * ([io.github.towkirahmedrony.salah.data.prayer.AdhanPrayerTimesProvider]); nothing here computes an
 * astronomical value, reads a clock or touches Android.
 *
 * **Why tomorrow's schedule is accepted.** Isha's period closes at the *next* day's Fajr, and that
 * instant is not on today's schedule. [nextDaySchedule] is the only way to close it honestly, and it
 * is accepted only when it is a complete schedule for exactly the next day, for the same location
 * and the same calculation profile — the three things that decide a prayer time. Anything else is
 * another day's or another place's timetable, and its Fajr would silently answer a different
 * question; it is dropped rather than used, and the Isha period is then reported as unavailable
 * instead of being ended at a boundary Salah cannot name.
 */
class PrayerWidgetStateResolver(
    private val currentPrayerResolver: CurrentPrayerPeriodResolver = CurrentPrayerPeriodResolver(),
) {

    fun resolve(
        schedule: PrayerSchedule?,
        now: ZonedDateTime,
        nextDaySchedule: PrayerSchedule? = null,
    ): PrayerWidgetState {
        if (schedule == null) {
            return PrayerWidgetState.Unavailable(NO_SCHEDULE)
        }

        val status = currentPrayerResolver.resolve(
            schedule = schedule,
            now = now,
            nextDayFajr = nextDayFajrFor(schedule, nextDaySchedule),
        )

        return when (status) {
            is CurrentPrayerStatus.Active -> PrayerWidgetState.Active(
                prayer = status.period.prayer,
                startedAt = status.period.startsAt,
                endsAt = status.period.endsAt,
                endsWith = status.period.endsWith,
                remaining = status.period.remaining,
            )

            is CurrentPrayerStatus.Between -> PrayerWidgetState.Upcoming(
                prayer = status.nextPrayer,
                startsAt = status.nextPrayerTime,
                remaining = status.remaining,
            )

            is CurrentPrayerStatus.Unavailable -> PrayerWidgetState.Unavailable(status.detail)
        }
    }

    /**
     * The following day's Fajr, or `null` when [nextDaySchedule] is not that day's timetable.
     *
     * The same three-part check `NextPrayerResolver` performs on a next-day schedule, and for the
     * same reason: a value that fails it is not evidence about tomorrow.
     */
    private fun nextDayFajrFor(
        schedule: PrayerSchedule,
        nextDaySchedule: PrayerSchedule?,
    ): ZonedDateTime? {
        if (nextDaySchedule == null) return null
        if (!nextDaySchedule.isComplete) return null
        if (nextDaySchedule.date != schedule.date.plusDays(1)) return null
        if (nextDaySchedule.location != schedule.location) return null
        if (nextDaySchedule.profile != schedule.profile) return null
        return nextDaySchedule.timeOf(PrayerName.FAJR)
    }

    private companion object {
        const val NO_SCHEDULE = "No prayer schedule is available, so no period can be identified."
    }
}
