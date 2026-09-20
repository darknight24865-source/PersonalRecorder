package com.example.personalrecorder.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
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
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.Volatile

/**
 * Captures device *playback* audio (music, games, videos, YouTube) to a WAV
 * file using AudioPlaybackCapture (API 29+).
 *
 * IMPORTANT platform restriction (the reason call audio cannot be captured):
 * AudioPlaybackCapture deliberately EXCLUDES audio played with
 * [AudioFormat.USAGE_VOICE_COMMUNICATION] — which is what WhatsApp / other
 * VoIP calls use. Running this while a WhatsApp call is active therefore
 * yields silence, by design of the Android platform. The captured stream is
 * also encrypted for privacy-sensitive apps unless they opt in (capture
 * policy).
 *
 * Requires the MediaProjection consent flow (screen-capture dialog) even
 * though no pixels are recorded — the platform ties playback capture to the
 * projection permission.
 *
 * Controlled from the UI toggle, or remotely with
 * {"type":"deviceaudio_start"} / {"type":"deviceaudio_stop"}.
 */
class DeviceAudioService : Service() {

    companion object {
        const val ACTION_STOP = "action_stop"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "device_audio_channel"
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val READ_CHUNK_BYTES = 4096
        private const val NOTIFICATION_ID = 1005

        @Volatile
        var isRunning = false
            private set
    }

    private var projectionManager: MediaProjectionManager? = null
    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var currentFile: File? = null
    private val recording = AtomicBoolean(false)
    private var captureThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        createNotificationChannel()
        CommandBus.register(CommandBus.CMD_DEV_AUDIO_START) { startCapture() }
        CommandBus.register(CommandBus.CMD_DEV_AUDIO_STOP) { stopCapture() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY

        if (Build.VERSION.SDK_INT < 29) {
            RealtimeClient.send(
                "deviceaudio_error",
                JSONObject().put("reason", "requires_api_29_or_higher")
            )
            stopSelf()
            return START_NOT_STICKY
        }

        // MediaProjection consent extras (handed over by the UI after the
        // user-approved screen-capture dialog).
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

        val proj = projectionManager?.getMediaProjection(resultCode, resultData)
        if (proj == null) {
            RealtimeClient.send("deviceaudio_error", JSONObject().put("reason", "projection_unavailable"))
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj.apply {
            registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
        }

        startCapture()
        return START_STICKY
    }

    private fun startCapture() {
        if (isRunning) return
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

            // Pick a buffer between 40 and 80 ms to avoid overrun while
            // keeping latency low-ish for the WAV writer.
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
            val bufSize = (minBuf * 4).coerceAtLeast(READ_CHUNK_BYTES * 4)

            val rec = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNELS)
                        .setEncoding(ENCODING)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("audio_record_init_failed")
            }

            val dir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "device_audio")
                .apply { mkdirs() }
            currentFile = File(dir, "dev_${timestamp()}.wav")

            startInForeground()
            audioRecord = rec
            recording.set(true)
            isRunning = true
            rec.startRecording()
            // Write WAV header up front with placeholder sizes; patched on stop.
            writeWavHeader(FileOutputStream(currentFile!!), 0)
            captureThread = Thread { captureLoop(rec) }.also { it.start() }
            RealtimeClient.send(
                "deviceaudio_started",
                JSONObject()
                    .put("path", currentFile!!.absolutePath)
                    .put("sample_rate", SAMPLE_RATE)
            )
        } catch (e: Exception) {
            RealtimeClient.send(
                "deviceaudio_error",
                JSONObject().put("reason", e.message ?: "start_failed")
            )
            teardownResources()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun captureLoop(rec: AudioRecord) {
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var bytesRecorded = 0L
        try {
            BufferedOutputStream(FileOutputStream(currentFile!!, true)).use { out ->
                while (recording.get()) {
                    val n = rec.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        out.write(buffer, 0, n)
                        bytesRecorded += n
                    }
                }
            }
        } catch (_: Exception) {
            // file may be truncated; patched below with what we actually wrote
        } finally {
            patchWavHeader(currentFile!!, bytesRecorded)
        }
    }

    private fun stopCapture() {
        if (!recording.getAndSet(false)) {
            teardownResources()
            return
        }
        val rec = audioRecord
        try {
            rec?.stop()
        } catch (_: Exception) {}
        try {
            rec?.release()
        } catch (_: Exception) {}
        audioRecord = null
        captureThread?.join(2000)
        captureThread = null
        isRunning = false
        val finished = currentFile
        RealtimeClient.send(
            "deviceaudio_stopped",
            JSONObject().put("path", finished?.absolutePath ?: "")
        )
        finished?.let { MediaUploader.upload(it, "audio") }
        currentFile = null
        teardownResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardownResources() {
        try {
            projection?.stop()
        } catch (_: Exception) {}
        projection = null
    }

    /** 44-byte RIFF/WAVE header; sizes (UInt32 LE) patched in [patchWavHeader]. */
    private fun writeWavHeader(out: FileOutputStream, dataSize: Int) {
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        putLe(header, 4, (36 + dataSize).toLong())
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        putLe(header, 16, 16L) // fmt chunk size
        putLe(header, 20, 1L)  // PCM
        putLe(header, 22, 1L)  // mono
        putLe(header, 24, SAMPLE_RATE.toLong())
        putLe(header, 28, (SAMPLE_RATE * 2).toLong()) // byte rate
        putLe(header, 32, 2L)  // block align
        putLe(header, 34, 16L) // bits per sample
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        putLe(header, 40, dataSize.toLong())
        out.write(header)
    }

    /** Rewrites the RIFF sizes after recording stops. */
    private fun patchWavHeader(file: File, dataSize: Long) {
        try {
            val len = file.length()
            if (len < 44) return
            val raf = java.io.RandomAccessFile(file, "rw")
            raf.use {
                it.seek(4)
                writeLe(it, 36 + dataSize)
                it.seek(40)
                writeLe(it, dataSize)
            }
        } catch (_: Exception) {}
    }

    private fun putLe(byteArray: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) {
            byteArray[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
    }

    private fun writeLe(raf: java.io.RandomAccessFile, value: Long) {
        for (i in 0 until 4) raf.write(((value shr (8 * i)) and 0xFF).toInt())
    }

    private fun startInForeground() {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DeviceAudioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Capturing device audio")
            .setContentText("PersonalRecorder is capturing media audio to WAV")
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Device audio capture", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopCapture()
        CommandBus.unregister(CommandBus.CMD_DEV_AUDIO_START)
        CommandBus.unregister(CommandBus.CMD_DEV_AUDIO_STOP)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
