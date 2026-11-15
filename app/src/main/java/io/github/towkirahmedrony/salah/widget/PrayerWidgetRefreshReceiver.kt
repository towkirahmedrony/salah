package io.github.towkirahmedrony.salah.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Delivers the single alarm that marks the widget's next prayer boundary.
 *
 * The alarm is the only thing that can relabel the widget when no app is open, and it exists to be
 * fired at the instant a period opens or closes — not on a schedule. It is declared
 * `android:exported="false"` and its `Intent` names this class explicitly, so only Salah's own
 * `PendingIntent` can reach it.
 *
 * The receiver does no work inline: it hands the refresh to the application scope under `goAsync`,
 * the same way the app's other background receivers do, so it stays inside the platform's receiver
 * budget however slow the disk is.
 */
class PrayerWidgetRefreshReceiver : BroadcastReceiver(), KoinComponent {

    private val updater: PrayerWidgetUpdater by inject()
    private val applicationScope: CoroutineScope by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REFRESH) return
        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                updater.refreshAll()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Same rule as the provider: an alarm that cannot redraw must not take the app down
                // with it. The boundary alarm is replaced by the next successful render.
                Log.w(TAG, "The boundary refresh failed; keeping the previous rendering", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        /** The action Salah's own refresh alarm carries. */
        const val ACTION_REFRESH = "io.github.towkirahmedrony.salah.action.REFRESH_PRAYER_WIDGET"

        private const val TAG = "SalahWidget"
    }
}
