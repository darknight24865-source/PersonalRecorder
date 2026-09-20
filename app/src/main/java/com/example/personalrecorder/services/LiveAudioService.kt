package com.example.personalrecorder.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import androidx.core.content.ContextCompat
import com.example.personalrecorder.MainActivity
import com.example.personalrecorder.R
import com.example.personalrecorder.net.RealtimeClient
import org.json.JSONObject
import kotlin.jvm.Volatile

/**
 * Live microphone streaming: PCM16 mono @ 16 kHz chunks are pushed over the
 * WebSocket as {"type":"audio_live_chunk", payload:{data:<base64>, rate:16000}}
 * and played in the dashboard's Web Audio player in real time.
 *
 * Controlled from the dashboard ("Live audio on/off") or the UI toggle.
 * Requires only the RECORD_AUDIO permission (no projection consent).
 */
class LiveAudioService : Service() {

    companion object {
        const val ACTION_STOP = "action_stop"
        const val CHANNEL_ID = "live_audio_channel"
        private const val NOTIFICATION_ID = 1007

        const val SAMPLE_RATE = 16_000
        private const val CHUNK_BYTES = 2048

        @Volatile
        var isRunning = false
            private set

        /** Remote "audio_live_start" handler (registered by ConnectionService). */
        fun onRemoteStart(context: Context) {
            if (isRunning) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, LiveAudioService::class.java)
            )
        }

        /** Remote "audio_live_stop" handler. */
        fun onRemoteStop(context: Context) {
            if (!isRunning) return
            context.startService(
                Intent(context, LiveAudioService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private var recordThread: HandlerThread? = null
    private var recordHandler: Handler? = null
    private var recorder: AudioRecord? = null
    private val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopStreaming()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY
        startStreaming()
        return START_STICKY
    }

    private fun startStreaming() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            RealtimeClient.send(
                "audio_live_error",
                JSONObject().put("reason", "buffer_size_unavailable")
            )
            stopSelf()
            return
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (e: Exception) {
            RealtimeClient.send(
                "audio_live_error",
                JSONObject().put("reason", e.message ?: "audio_record_failed")
            )
            stopSelf()
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            RealtimeClient.send(
                "audio_live_error",
                JSONObject().put("reason", "audio_record_not_initialized")
            )
            stopSelf()
            return
        }
        recorder = rec
        stopFlag.set(false)
        try {
            startInForeground()
        } catch (e: Exception) {
            // Android 14 + targetSdk 34: startForeground(...MICROPHONE) throws
            // SecurityException when the app is not in the eligible state (or
            // lacks the foreground-service microphone permission). Report it
            // instead of letting it crash the process and drop the link.
            rec.release()
            recorder = null
            RealtimeClient.send(
                "audio_live_error",
                JSONObject().put("reason", e.message ?: "start_foreground_failed")
            )
            stopSelf()
            return
        }
        recordThread = HandlerThread("live_audio").also { it.start() }
        recordHandler = Handler(recordThread!!.looper)
        recordHandler!!.post {
            try {
                rec.startRecording()
                isRunning = true
                RealtimeClient.send(
                    "audio_live_started",
                    JSONObject().put("rate", SAMPLE_RATE)
                )
                val buf = ByteArray(CHUNK_BYTES)
                while (!stopFlag.get()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        val chunk = if (n == buf.size) buf else buf.copyOf(n)
                        RealtimeClient.send(
                            "audio_live_chunk",
                            JSONObject()
                                .put("data", Base64.encodeToString(chunk, Base64.NO_WRAP))
                                .put("rate", SAMPLE_RATE)
                        )
                    }
                }
            } catch (e: Exception) {
                RealtimeClient.send(
                    "audio_live_error",
                    JSONObject().put("reason", e.message ?: "stream_failed")
                )
            } finally {
                try {
                    rec.stop()
                } catch (_: Exception) {
                }
                rec.release()
                recorder = null
                isRunning = false
                RealtimeClient.send("audio_live_stopped", JSONObject())
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopStreaming() {
        stopFlag.set(true)
        // The reader loop exits on the next read; the finally block cleans up.
    }

    private fun startInForeground() {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, LiveAudioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Live audio streaming")
            .setContentText("Microphone audio is being streamed to the dashboard")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Live audio", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopFlag.set(true)
        recordHandler?.removeCallbacksAndMessages(null)
        recordThread?.quitSafely()
        recordThread = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
