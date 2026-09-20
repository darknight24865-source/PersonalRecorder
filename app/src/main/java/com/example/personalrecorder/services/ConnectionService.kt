package com.example.personalrecorder.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.example.personalrecorder.MainActivity
import com.example.personalrecorder.R
import com.example.personalrecorder.net.CommandBus
import com.example.personalrecorder.net.RealtimeClient
import com.example.personalrecorder.net.UpdateChecker
import kotlin.jvm.Volatile

/**
 * "Steady mode" keep-alive for the relay link.
 *
 * Why it exists: a plain background connection can be suspended by the OS or
 * killed together with the activity. This foreground service keeps the
 * process active, watches the network type, and reconnects instantly when
 * the phone hops between Wi-Fi and mobile data (or back).
 *
 * The service itself carries no capture capability -- it only maintains the
 * WebSocket. Android 14 requires every foreground service to declare a type;
 * "specialUse" is the documented category for a keep-alive that fits no other
 * type, with the required subtype property declared in the manifest.
 *
 * Start/stop it from MainActivity (or BootReceiver) with ACTION_START /
 * ACTION_STOP. The relay URL is persisted in `relay_prefs` so the link can
 * be restored after a relaunch or reboot, unless the user pressed Disconnect
 * (which sets user_disconnected=true).
 */
class ConnectionService : Service() {

    companion object {
        const val ACTION_START = "com.example.personalrecorder.action.CONN_START"
        const val ACTION_STOP = "com.example.personalrecorder.action.CONN_STOP"
        const val EXTRA_URL = "relay_url"

        const val PREFS_NAME = "relay_prefs"
        const val KEY_URL = "relay_url"
        /** Optional comma/newline-separated failover list (see RealtimeClient.connectCsv). */
        const val KEY_URLS = "relay_urls"
        const val KEY_DISCONNECTED = "user_disconnected"

        private const val NOTIFICATION_ID = 1006
        private const val NOTIFICATION_CHANNEL = "relay_conn_channel"
        private const val NOTIF_UPDATE_MS = 3000L

        @Volatile
        var isRunning = false
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val notifUpdater = object : Runnable {
        override fun run() {
            updateNotification()
            mainHandler.postDelayed(this, NOTIF_UPDATE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Call-recording command handlers live here (long-lived) so they work
        // even when the recording services are not running:
        //  - call_video_start needs the one-time screen-capture consent first;
        //    if no session is alive the dashboard is told consent is required.
        //  - call_audio_start needs no consent and cold-starts the mic service.
        CommandBus.register(CommandBus.CMD_CALL_VIDEO_START) {
            ScreenAudioRecorderService.onRemoteCallVideoStart(applicationContext)
        }
        CommandBus.register(CommandBus.CMD_CALL_VIDEO_STOP) {
            ScreenAudioRecorderService.onRemoteCallVideoStop(applicationContext)
        }
        CommandBus.register(CommandBus.CMD_CALL_AUDIO_START) {
            AudioRecorderService.onRemoteCallAudioStart(applicationContext)
        }
        CommandBus.register(CommandBus.CMD_CALL_AUDIO_STOP) {
            AudioRecorderService.onRemoteCallAudioStop(applicationContext)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopLink()
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // A stored comma/newline-separated failover list (KEY_URLS) wins;
        // otherwise the URL from the caller, or -- after a STICKY restart or
        // reboot -- the last session the user did not end (KEY_URL).
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val lastUrls = prefs.getString(KEY_URLS, null)?.trim().orEmpty()
        val url = lastUrls.ifEmpty { null }
            ?: intent?.getStringExtra(EXTRA_URL)
            ?: prefs.getString(KEY_URL, null)

        RealtimeClient.init(applicationContext)
        if (url != null && !RealtimeClient.isActive(url)) {
            if (lastUrls.isNotEmpty()) RealtimeClient.connectCsv(lastUrls)
            else RealtimeClient.connect(url)
        }
        // Background auto-update: also checked on every steady-link start
        // (boot, relaunch, OS restart), so a pushed release lands even if
        // the activity never opens. Guarded against concurrent runs.
        UpdateChecker.checkAndInstall(applicationContext)
        startForeground(NOTIFICATION_ID, buildNotification(RealtimeClient.lastStatus))
        registerNetworkCallback()
        mainHandler.removeCallbacks(notifUpdater)
        mainHandler.post(notifUpdater)
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(notifUpdater)
        unregisterNetworkCallback()
        CommandBus.unregister(CommandBus.CMD_CALL_VIDEO_START)
        CommandBus.unregister(CommandBus.CMD_CALL_VIDEO_STOP)
        CommandBus.unregister(CommandBus.CMD_CALL_AUDIO_START)
        CommandBus.unregister(CommandBus.CMD_CALL_AUDIO_STOP)
        super.onDestroy()
    }

    private fun stopLink() {
        mainHandler.removeCallbacks(notifUpdater)
        unregisterNetworkCallback()
        RealtimeClient.disconnect()
    }

    /**
     * The moment Android reports a connectivity change (new default network,
     * or the old one gone), drop the stale socket and dial again. This is
     * what makes Wi-Fi <-> mobile-data hand-offs reconnect instantly instead
     * of waiting for a ping timeout.
     */
    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = RealtimeClient.reconnectNow()
            override fun onLost(network: Network) = RealtimeClient.reconnectNow()
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: Exception) {
            networkCallback = null
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // already unregistered
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(RealtimeClient.lastStatus))
        } catch (_: Exception) {
            // POST_NOTIFICATIONS denied: the service still runs
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Steady-mode relay link")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(0, "Stop link", stop)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            "Steady-mode link",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}