package io.github.towkirahmedrony.salah.widget

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Salah's home-screen widget entry point.
 *
 * **One path for every broadcast.** The platform tells a provider about several different events —
 * the widget was added, resized, deleted, its provider was enabled or disabled, the device rebooted,
 * the app was updated — and all of them mean exactly the same thing here: the data must be read
 * again and the views rebuilt. [AppWidgetProvider] dispatches those to separate callbacks that would
 * each need the same body, so this overrides the dispatch instead and lets
 * [PrayerWidgetUpdater.refreshAll] decide what to draw from the widget manager's own id list. A
 * resize therefore cannot take a different code path from a reboot.
 *
 * **Why `goAsync`.** The refresh reads DataStore and asks the prayer repository for a schedule. A
 * manifest receiver only owns the process until `onReceive` returns, so the work is launched on the
 * application scope and the pending result is finished when it is done. The work is bounded — a
 * local calculation and a handful of disk reads — well inside the platform's receiver budget.
 *
 * No prayer time is computed here; see [PrayerWidgetUpdater].
 */
class PrayerWidgetProvider : AppWidgetProvider(), KoinComponent {

    private val updater: PrayerWidgetUpdater by inject()
    private val applicationScope: CoroutineScope by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        // Dispatching first keeps AppWidgetProvider's own bookkeeping intact (it tracks the ids it
        // was told about); the render that follows is the only work this receiver performs.
        super.onReceive(context, intent)
        applicationScope.launch {
            try {
                updater.refreshAll()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // The broadcast can arrive before the user has unlocked a just-rebooted device, when
                // the preferences store is not readable yet. A widget is not a reason to crash the
                // app: the launcher keeps the last rendering, and the next trigger redraws it.
                Log.w(TAG, "A widget refresh failed; keeping the previous rendering", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SalahWidget"
    }
}
