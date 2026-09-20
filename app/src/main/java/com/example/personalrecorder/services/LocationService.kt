package com.example.personalrecorder.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import com.example.personalrecorder.R
import com.example.personalrecorder.net.CommandBus
import com.example.personalrecorder.net.RealtimeClient
import org.json.JSONObject
import kotlin.jvm.Volatile

/**
 * Live location stream. While running, every new fix (GPS preferred,
 * network fallback) is pushed to the relay as {"type":"location"}.
 *
 * Controlled from the UI toggle, or remotely with
 * {"type":"location_start"} / {"type":"location_stop"}.
 */
class LocationService : Service() {

    companion object {
        const val ACTION_STOP = "action_stop"
        const val CHANNEL_ID = "location_channel"
        const val MIN_TIME_MS = 5000L
        const val MIN_DISTANCE_M = 5f
        private const val NOTIFICATION_ID = 1003

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var locationManager: LocationManager

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            RealtimeClient.send(
                "location",
                JSONObject()
                    .put("lat", location.latitude)
                    .put("lon", location.longitude)
                    .put("accuracy", location.accuracy)
                    .put("time", location.time)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        createNotificationChannel()
        CommandBus.register(CommandBus.CMD_LOC_START) { startUpdates() }
        CommandBus.register(CommandBus.CMD_LOC_STOP) { stopUpdates() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || isRunning) {
            stopUpdates()
            return START_NOT_STICKY
        }
        startUpdates()
        return START_STICKY
    }

    private fun startUpdates() {
        if (isRunning) return
        startInForeground()
        isRunning = true
        val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            LocationManager.GPS_PROVIDER
        } else {
            LocationManager.NETWORK_PROVIDER
        }
        try {
            locationManager.requestLocationUpdates(
                provider, MIN_TIME_MS, MIN_DISTANCE_M, listener, Looper.getMainLooper()
            )
            locationManager.getLastKnownLocation(provider)?.let { listener.onLocationChanged(it) }
            RealtimeClient.send("location_started", JSONObject().put("provider", provider))
        } catch (e: SecurityException) {
            stopUpdates()
        }
    }

    private fun stopUpdates() {
        if (!isRunning) return
        isRunning = false
        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {}
        RealtimeClient.send("location_stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground() {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, LocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sharing location")
            .setContentText("PersonalRecorder is streaming your live location")
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Location sharing", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopUpdates()
        CommandBus.unregister(CommandBus.CMD_LOC_START)
        CommandBus.unregister(CommandBus.CMD_LOC_STOP)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}