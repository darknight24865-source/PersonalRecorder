package com.example.personalrecorder.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import com.example.personalrecorder.R
import com.example.personalrecorder.net.CommandBus
import com.example.personalrecorder.net.RealtimeClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Screen capture via MediaProjection.
 *
 * - Auto-captures every 2 s (CAPTURE_INTERVAL_MS).
 * - Smart diff: when the screen has not visibly changed since the last frame,
 *   the frame is skipped (no disk write, no bytes on the wire). The diff is
 *   computed on a tiny 48px-wide preview, so it is cheap.
 * - Battery-aware: when the battery drops to BATTERY_PAUSE_LEVEL while not
 *   charging, the auto loop pauses and resumes when conditions recover.
 *   Explicit/remote captures always go through (battery pause and diff only
 *   apply to the automatic loop).
 * - One-off captures can be requested locally (ACTION_CAPTURE_NOW) or
 *   remotely (relay {"type":"capture"}) — these force a capture.
 * - Full-resolution PNGs are saved locally; a scaled JPEG (max 720 px wide)
 *   is pushed over the WebSocket to the relay.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_STOP = "action_stop"
        const val ACTION_CAPTURE_NOW = "action_capture_now"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "screen_capture_channel"
        const val CAPTURE_INTERVAL_MS = 2000L
        const val WIRE_MAX_WIDTH = 720

        /** Minimum fraction of preview pixels that must differ to treat a frame as "new". */
        const val DIFF_REQUIRED_CHANGE_FRACTION = 0.005f

        /** Preview size used for diff comparison (kept tiny on purpose). */
        const val DIFF_PREVIEW_WIDTH = 48

        /** Pause the auto loop at or below this level while not charging. */
        const val BATTERY_PAUSE_LEVEL = 15

        /** Report a "skipped" event at most every N skipped frames. */
        const val SKIP_REPORT_EVERY = 10

        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val active = AtomicBoolean(false)

    private var prevSignature: IntArray? = null
    private var skipCount = 0

    @Volatile
    private var batteryPaused = false

    private var batteryReceiverRegistered = false

    private val captureLoop = object : Runnable {
        override fun run() {
            captureNow(force = false)
            if (active.get() && !batteryPaused) {
                captureHandler?.postDelayed(this, CAPTURE_INTERVAL_MS)
            }
        }
    }

    /** Sticky battery broadcast; fires immediately on register with current state. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            if (level < 0 || scale <= 0) return
            val pct = level * 100 / scale
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val shouldPause = pct <= BATTERY_PAUSE_LEVEL && !charging
            if (shouldPause == batteryPaused) return
            batteryPaused = shouldPause
            RealtimeClient.send(
                if (shouldPause) "capture_paused" else "capture_resumed",
                JSONObject().put("level", pct).put("charging", charging)
            )
            val handler = captureHandler ?: return
            if (shouldPause) {
                handler.removeCallbacks(captureLoop)
            } else if (active.get()) {
                handler.post(captureLoop)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        createNotificationChannel()
        CommandBus.register(CommandBus.CMD_CAPTURE) { captureNow(force = true) }
        registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        batteryReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CAPTURE_NOW -> {
                if (active.get()) captureNow(force = true)
                return START_NOT_STICKY
            }
        }

        if (active.get()) return START_NOT_STICKY

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

        startInForeground()

        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                teardown()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2
        )
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "personal_recorder_capture",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            // Non-null handler is required on API 34+
            Handler(Looper.getMainLooper())
        )

        captureThread = HandlerThread("screen-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        active.set(true)
        isRunning = true
        if (!batteryPaused) {
            captureHandler?.post(captureLoop)
        } else {
            RealtimeClient.send("capture_paused", JSONObject().put("reason", "battery_at_start"))
        }
        RealtimeClient.send("capture_started", JSONObject().put("interval_ms", CAPTURE_INTERVAL_MS))
        return START_STICKY
    }

    /**
     * @param force when true, the frame is captured regardless of diff state.
     *              Remote "capture" commands and the local button force.
     */
    private fun captureNow(force: Boolean) {
        if (!active.get()) return
        if (!force && batteryPaused) return
        val reader = imageReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return

        try {
            val bitmap = imageToBitmap(image) ?: return
            val signature = diffSignature(bitmap)

            // Diff gate (auto loop only). Forced captures always proceed.
            if (!force && !signaturesDiffer(prevSignature, signature)) {
                prevSignature = signature
                skipCount++
                if (skipCount % SKIP_REPORT_EVERY == 0) {
                    RealtimeClient.send(
                        "screenshot_skipped",
                        JSONObject().put("reason", "no_change").put("skipped", skipCount)
                    )
                }
                return
            }
            prevSignature = signature

            // Full-resolution local copy
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "screen_captures")
                .apply { mkdirs() }
            val file = File(dir, "cap_${timestamp()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

            // Downscaled JPEG for the wire
            val wire = scaleDown(bitmap, WIRE_MAX_WIDTH)
            val bytes = ByteArrayOutputStream().also {
                wire.compress(Bitmap.CompressFormat.JPEG, 70, it)
            }
            val payload = JSONObject()
                .put("path", file.absolutePath)
                .put("width", bitmap.width)
                .put("height", bitmap.height)
                .put("data", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
            RealtimeClient.send("screenshot", payload)
        } catch (_: Exception) {
            // a failed frame is non-fatal
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmapWidth = image.width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return if (bitmapWidth == image.width) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also { bitmap.recycle() }
        }
    }

    /**
     * Downscale the frame to a tiny preview and return its raw pixels.
     * Comparing these int arrays is far cheaper than comparing full frames.
     */
    private fun diffSignature(src: Bitmap): IntArray {
        val w = DIFF_PREVIEW_WIDTH
        val h = (src.height.toFloat() * w / src.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()
        return pixels
    }

    private fun signaturesDiffer(old: IntArray?, new: IntArray): Boolean {
        if (old == null || old.size != new.size) return true
        val total = new.size
        val threshold = (total * DIFF_REQUIRED_CHANGE_FRACTION).toInt().coerceAtLeast(1)
        var diff = 0
        for (i in 0 until total) {
            if (old[i] != new[i]) {
                diff++
                if (diff >= threshold) return true
            }
        }
        return false
    }

    private fun scaleDown(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth) return src
        val ratio = maxWidth.toFloat() / src.width
        return Bitmap.createScaledBitmap(src, maxWidth, (src.height * ratio).toInt(), true)
    }

    private fun startInForeground() {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Screen capture active")
            .setContentText("PersonalRecorder captures a screenshot every 2 seconds")
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
            CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun teardown() {
        if (!active.getAndSet(false)) {
            isRunning = false
            if (batteryReceiverRegistered) {
                try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
                batteryReceiverRegistered = false
            }
            CommandBus.unregister(CommandBus.CMD_CAPTURE)
            return
        }
        isRunning = false
        captureHandler?.removeCallbacks(captureLoop)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        prevSignature = null
        if (batteryReceiverRegistered) {
            try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
            batteryReceiverRegistered = false
        }
        CommandBus.unregister(CommandBus.CMD_CAPTURE)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
}