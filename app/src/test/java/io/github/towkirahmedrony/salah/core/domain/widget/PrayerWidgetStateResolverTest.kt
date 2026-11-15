package io.github.towkirahmedrony.salah.core.domain.widget

import io.github.towkirahmedrony.salah.core.domain.PeriodBoundary
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Locks in what the home-screen widget reads at a moment.
 *
 * Every instant is fixed, so nothing here depends on when the suite runs. The timetable is Dhaka's
 * on one date — Fajr 04:39, sunrise 05:54, Dhuhr 11:46, Asr 15:58, Maghrib 17:36, Isha 18:51 — and
 * the assertions are about *which* period is open and *which boundary* it is counted to, not about
 * the astronomy, which the engine owns and tests separately.
 *
 * The cases that matter for a widget are the ones no screen would ever notice: the small hours, an
 * exact boundary, and Isha's period, whose closing instant is on the *next* day's timetable.
 */
class PrayerWidgetStateResolverTest {

    private val resolver = PrayerWidgetStateResolver()
    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val date: LocalDate = LocalDate.of(2026, 10, 11)

    private val dhaka: LocationInfo = checkNotNull(
        LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"),
    )

    /** A second valid coordinate, used only to prove another place's timetable is refused. */
    private val chattogram: LocationInfo = checkNotNull(
        LocationInfo.create("Chattogram", 22.3569, 91.7832, "Asia/Dhaka"),
    )

    private fun at(hour: Int, minute: Int, second: Int = 0): ZonedDateTime =
        date.atTime(hour, minute, second).atZone(zone)

    private fun defaultPrayers(targetDate: LocalDate): List<PrayerTimeEntry> = listOf(
        PrayerTimeEntry(PrayerName.FAJR, targetDate.atTime(4, 39).atZone(zone)),
        PrayerTimeEntry(PrayerName.DHUHR, targetDate.atTime(11, 46).atZone(zone)),
        PrayerTimeEntry(PrayerName.ASR, targetDate.atTime(15, 58).atZone(zone)),
        PrayerTimeEntry(PrayerName.MAGHRIB, targetDate.atTime(17, 36).atZone(zone)),
        PrayerTimeEntry(PrayerName.ISHA, targetDate.atTime(18, 51).atZone(zone)),
    )

    private fun defaultSolarEvents(targetDate: LocalDate): List<SolarEventEntry> = listOf(
        SolarEventEntry(SolarEvent.SUNRISE, targetDate.atTime(5, 54).atZone(zone)),
        SolarEventEntry(SolarEvent.SOLAR_NOON, targetDate.atTime(11, 45).atZone(zone)),
        SolarEventEntry(SolarEvent.SUNSET, targetDate.atTime(17, 36).atZone(zone)),
    )

    private fun schedule(
        targetDate: LocalDate = date,
        location: LocationInfo = dhaka,
        profile: PrayerCalculationProfile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI,
        prayers: List<PrayerTimeEntry>? = null,
        solarEvents: List<SolarEventEntry>? = null,
    ): PrayerSchedule = PrayerSchedule(
        date = targetDate,
        location = location,
        profile = profile,
        prayers = prayers ?: defaultPrayers(targetDate),
        solarEvents = solarEvents ?: defaultSolarEvents(targetDate),
        generatedAt = Instant.parse("2026-10-11T00:00:00Z"),
    )

    private fun active(state: PrayerWidgetState): PrayerWidgetState.Active {
        assertTrue("expected an active period but was $state", state is PrayerWidgetState.Active)
        return state as PrayerWidgetState.Active
    }

    private fun upcoming(state: PrayerWidgetState): PrayerWidgetState.Upcoming {
        assertTrue("expected an upcoming prayer but was $state", state is PrayerWidgetState.Upcoming)
        return state as PrayerWidgetState.Upcoming
    }

    // ------------------------------------------------------------------ active periods

    @Test
    fun `fajr is active from its time until sunrise`() {
        val active = active(resolver.resolve(schedule(), at(4, 45)))

        assertEquals(PrayerName.FAJR, active.prayer)
        assertEquals(at(4, 39), active.startedAt)
        assertEquals(at(5, 54), active.endsAt)
        // Sunrise is a solar event, not a prayer: the widget names it as the closing boundary.
        assertTrue(active.endsWith is PeriodBoundary.Solar)
        assertEquals(Duration.ofMinutes(69), active.remaining)
    }

    @Test
    fun `dhuhr is active from its time until asr`() {
        val active = active(resolver.resolve(schedule(), at(12, 30)))

        assertEquals(PrayerName.DHUHR, active.prayer)
        assertEquals(at(11, 46), active.startedAt)
        assertEquals(at(15, 58), active.endsAt)
        assertEquals(Duration.ofMinutes(208), active.remaining)
    }

    @Test
    fun `asr is active from its time until maghrib`() {
        val active = active(resolver.resolve(schedule(), at(16, 30)))

        assertEquals(PrayerName.ASR, active.prayer)
        assertEquals(PrayerName.MAGHRIB, (active.endsWith as PeriodBoundary.Prayer).prayer)
        assertEquals(Duration.ofMinutes(66), active.remaining)
    }

    @Test
    fun `maghrib is active from its time until isha`() {
        val active = active(resolver.resolve(schedule(), at(18, 0)))

        assertEquals(PrayerName.MAGHRIB, active.prayer)
        assertEquals(at(18, 51), active.endsAt)
        assertEquals(Duration.ofMinutes(51), active.remaining)
    }

    // ------------------------------------------------------------------ between two periods

    @Test
    fun `after sunrise and before dhuhr the widget shows the prayer that opens next`() {
        val upcoming = upcoming(resolver.resolve(schedule(), at(9, 0)))

        assertEquals(PrayerName.DHUHR, upcoming.prayer)
        assertEquals(at(11, 46), upcoming.startsAt)
        assertEquals(Duration.ofMinutes(166), upcoming.remaining)
    }

    // ------------------------------------------------------------------ exact boundaries

    @Test
    fun `a prayer's own time belongs to that prayer, not the one that just ended`() {
        val active = active(resolver.resolve(schedule(), at(11, 46, 0)))

        assertEquals(PrayerName.DHUHR, active.prayer)
        assertEquals(Duration.ofMinutes(252), active.remaining)
    }

    @Test
    fun `the exact instant of sunrise closes fajr and opens the gap before dhuhr`() {
        val upcoming = upcoming(resolver.resolve(schedule(), at(5, 54, 0)))

        assertEquals(PrayerName.DHUHR, upcoming.prayer)
    }

    // ------------------------------------------------------------------ midnight and Isha's end

    @Test
    fun `the small hours still belong to the previous day's isha`() {
        val active = active(resolver.resolve(schedule(), at(0, 30)))

        assertEquals(PrayerName.ISHA, active.prayer)
        // The period began before the schedule in hand, so its start is reported as unknown rather
        // than filled in with a carried-over timetable.
        assertNull(active.startedAt)
        assertEquals(at(4, 39), active.endsAt)
        assertEquals(Duration.ofMinutes(249), active.remaining)
    }

    @Test
    fun `isha is closed by the next day's fajr when that schedule is supplied`() {
        val active = active(
            resolver.resolve(schedule(), at(20, 0), nextDaySchedule = schedule(date.plusDays(1))),
        )

        assertEquals(PrayerName.ISHA, active.prayer)
        assertEquals(date.plusDays(1).atTime(4, 39).atZone(zone), active.endsAt)
        val boundary = active.endsWith as PeriodBoundary.Prayer
        assertEquals(PrayerName.FAJR, boundary.prayer)
        assertTrue("the closing Fajr is on the following local date", boundary.isNextDay)
        assertEquals(Duration.ofMinutes(519), active.remaining)
    }

    @Test
    fun `isha without tomorrow's timetable is unavailable rather than ended at a guess`() {
        val state = resolver.resolve(schedule(), at(20, 0))

        assertTrue(
            "expected Unavailable but was $state",
            state is PrayerWidgetState.Unavailable,
        )
    }

    // ------------------------------------------------------------------ rejected inputs

    @Test
    fun `no schedule is unavailable`() {
        val state = resolver.resolve(null, at(12, 0))

        assertTrue(state is PrayerWidgetState.Unavailable)
    }

    @Test
    fun `a schedule for another day cannot describe this moment`() {
        val state = resolver.resolve(schedule(), date.plusDays(1).atTime(12, 0).atZone(zone))

        assertTrue(state is PrayerWidgetState.Unavailable)
    }

    @Test
    fun `an incomplete timetable is unavailable`() {
        val incomplete = schedule(
            prayers = defaultPrayers(date).filterNot { it.prayer == PrayerName.ASR },
        )

        val state = resolver.resolve(incomplete, at(12, 0))

        assertTrue(state is PrayerWidgetState.Unavailable)
    }

    @Test
    fun `another place's tomorrow is refused as a closing boundary`() {
        val state = resolver.resolve(
            schedule(),
            at(20, 0),
            nextDaySchedule = schedule(date.plusDays(1), location = chattogram),
        )

        assertTrue(
            "another location's Fajr must not close Isha's period",
            state is PrayerWidgetState.Unavailable,
        )
    }

    @Test
    fun `another calculation profile's tomorrow is refused as a closing boundary`() {
        val otherProfile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI.copy(
            asrMethod = AsrCalculationMethod.STANDARD,
        )
        val state = resolver.resolve(
            schedule(),
            at(20, 0),
            nextDaySchedule = schedule(date.plusDays(1), profile = otherProfile),
        )

        assertTrue(
            "another profile's Fajr must not close Isha's period",
            state is PrayerWidgetState.Unavailable,
        )
    }

    @Test
    fun `a schedule two days out is not tomorrow's`() {
        val state = resolver.resolve(
            schedule(),
            at(20, 0),
            nextDaySchedule = schedule(date.plusDays(2)),
        )

        assertTrue(
            "a schedule two days out is not tomorrow's",
            state is PrayerWidgetState.Unavailable,
        )
    }

    // ------------------------------------------------------------------ boundary instant

    @Test
    fun `the next boundary is the instant the widget must be refreshed`() {
        val schedule = schedule()

        assertEquals(at(5, 54), resolver.resolve(schedule, at(4, 45)).nextBoundaryAt)
        assertEquals(at(11, 46), resolver.resolve(schedule, at(9, 0)).nextBoundaryAt)
        assertEquals(
            date.plusDays(1).atTime(4, 39).atZone(zone),
            resolver.resolve(schedule, at(20, 0), schedule(date.plusDays(1))).nextBoundaryAt,
        )
        assertNull(PrayerWidgetState.NoLocation.nextBoundaryAt)
        assertNull(PrayerWidgetState.Unavailable().nextBoundaryAt)
    }
}
