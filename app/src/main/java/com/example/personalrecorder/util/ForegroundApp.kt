package com.example.personalrecorder.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Resolves the app that is currently in the foreground using UsageStats.
 *
 * Requires the PACKAGE_USAGE_STATS special permission (Settings -> Special
 * access -> Usage access). When the permission is missing or no event is
 * found in the look-back window, returns null so callers can fall back to
 * "unknown".
 */
object ForegroundApp {

    private const val LOOKBACK_MS = 5_000L

    /** Package name of the app that moved to the foreground most recently. */
    fun current(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val end = System.currentTimeMillis()
        val begin = end - LOOKBACK_MS
        val events = usm.queryEvents(begin, end)
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                last = event.packageName
            }
        }
        return last
    }
}
