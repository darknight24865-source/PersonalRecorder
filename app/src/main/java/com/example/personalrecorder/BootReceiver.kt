package com.example.personalrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.personalrecorder.services.ConnectionService

/**
 * Restores the steady-mode relay link after a device reboot, as long as the
 * user's last session was left connected (the explicit Disconnect button
 * sets user_disconnected=true, so it is never overridden).
 *
 * BOOT_COMPLETED stays a valid launch trigger while the app targets SDK 34;
 * the flag is restricted for apps targeting Android 15+ (a good thing to
 * read about in exercise 7).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(ConnectionService.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ConnectionService.KEY_DISCONNECTED, true)) return
        val url = prefs.getString(ConnectionService.KEY_URL, null) ?: return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ConnectionService::class.java)
                    .setAction(ConnectionService.ACTION_START)
                    .putExtra(ConnectionService.EXTRA_URL, url)
            )
        } catch (_: Exception) {
            // Some OEMs reject FGS starts from boot; the link restores on
            // next app launch instead -- never fatal.
        }
    }
}