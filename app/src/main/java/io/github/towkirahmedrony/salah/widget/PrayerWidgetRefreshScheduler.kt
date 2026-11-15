package io.github.towkirahmedrony.salah.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.time.Instant

/**
 * Schedules the widget's *next* update, at the moment its content stops being true.
 *
 * **One alarm, not a polling loop.** The countdown itself is drawn by the platform's `Chronometer`,
 * which keeps ticking inside the launcher without waking Salah; the only thing an alarm is needed
 * for is the instant the *labels* change — a prayer period opening or closing — which is at most a
 * handful of times a day. `updatePeriodMillis` is deliberately `0` in the widget's info XML for the
 * same reason: a periodic update would wake the device every thirty minutes to redraw a value the
 * `Chronometer` is already animating.
 *
 * **Exactness follows the app's existing policy.** `setExactAndAllowWhileIdle` keeps a boundary
 * update punctual through Doze when the user has granted `SCHEDULE_EXACT_ALARM`; without it the
 * inexact but Doze-tolerant `setAndAllowWhileIdle` is used instead, and the widget may relabel a few
 * minutes late. A revoke between the check and the call is caught and takes the same fallback, so a
 * single permission change cannot leave the widget without any update at all.
 *
 * **A stable identity.** One request code plus one explicit `Intent` to
 * [PrayerWidgetRefreshReceiver] means every schedule replaces the previous one rather than adding a
 * second; there is never more than one pending widget alarm, whatever the triggers.
 */
class PrayerWidgetRefreshScheduler(private val context: Context) {

    private val alarmManager: AlarmManager?
        get() = ContextCompat.getSystemService(context, AlarmManager::class.java)

    /** Registers the single widget refresh alarm for [at], replacing any previous one. */
    fun scheduleAt(at: Instant) {
        val manager = alarmManager ?: return
        val now = System.currentTimeMillis()
        // A trigger in the past fires immediately, which would loop: an alarm that resolves to a
        // moment already gone cannot tell the widget anything new. The caller schedules slightly
        // after the boundary, and a short floor covers clock skew and an early delivery.
        val triggerAtMillis = maxOf(at.toEpochMilli(), now + MIN_DELAY_MILLIS)

        val intent = pendingIntent()
        val exact = canScheduleExact(manager) && tryScheduleExact(manager, triggerAtMillis, intent)
        if (!exact) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        }
    }

    /** Removes the pending refresh. Used when no widget is placed any more. */
    fun cancel() {
        val manager = alarmManager ?: return
        val intent = pendingIntent()
        manager.cancel(intent)
        intent.cancel()
    }

    private fun canScheduleExact(manager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    /** @return `true` when the platform accepted an exact alarm, `false` when it refused one. */
    private fun tryScheduleExact(
        manager: AlarmManager,
        triggerAtMillis: Long,
        intent: PendingIntent,
    ): Boolean = try {
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        true
    } catch (_: SecurityException) {
        false
    }

    /**
     * The refresh alarm's `PendingIntent`.
     *
     * The `Intent` names the receiver's class explicitly, so no other app can resolve it; the data
     * URI gives the alarm a stable identity that extras cannot change.
     */
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, PrayerWidgetRefreshReceiver::class.java)
            .setAction(PrayerWidgetRefreshReceiver.ACTION_REFRESH)
            .setData(REFRESH_URI.toUri()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val REQUEST_CODE = 0x5A1A

        const val REFRESH_URI = "salah://widget/refresh"

        /** How soon a boundary refresh may be registered when the boundary has already passed. */
        const val MIN_DELAY_MILLIS = 5_000L
    }
}
