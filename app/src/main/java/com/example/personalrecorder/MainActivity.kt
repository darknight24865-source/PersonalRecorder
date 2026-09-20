package com.example.personalrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.personalrecorder.net.RealtimeClient
import com.example.personalrecorder.net.UpdateChecker
import com.example.personalrecorder.services.AudioRecorderService
import com.example.personalrecorder.services.ConnectionService
import com.example.personalrecorder.services.DeviceAudioService
import com.example.personalrecorder.services.LocationService
import com.example.personalrecorder.services.ScreenAudioRecorderService
import com.example.personalrecorder.services.ScreenCaptureService

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var urlInput: EditText

    /**
     * Live status lines from the WebSocket client are mirrored into the
     * visible log area. Registered here (and removed in onDestroy) so
     * connection state stays visible while steady mode churns in the
     * background.
     */
    private val statusLogger: (String) -> Unit = { log(it) }

    private val projectionManager by lazy {
        getSystemService(MediaProjectionManager::class.java)
    }

    /**
     * Android requires an explicit per-session user consent dialog before any
     * screen capture can start. The result of that dialog is handed to the
     * foreground service, which creates the MediaProjection from it.
     */
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, intent)
                log("Screen capture service started")
            } else {
                log("Screen capture consent denied")
            }
        }

    private val screenRecLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val intent = Intent(this, ScreenAudioRecorderService::class.java).apply {
                    putExtra(ScreenAudioRecorderService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenAudioRecorderService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, intent)
                log("Screen + mic recording started -> mp4")
            } else {
                log("Recording consent denied")
            }
        }

    private val deviceAudioLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val intent = Intent(this, DeviceAudioService::class.java).apply {
                    putExtra(DeviceAudioService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(DeviceAudioService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, intent)
                log("Device audio capture started -> wav")
            } else {
                log("Device audio consent denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        RealtimeClient.init(this)
        RealtimeClient.addStatusListener(statusLogger)
        restoreSteadyLink()
        // Background auto-update: checks GitHub Releases once per launch
        // (guarded against concurrent runs; silent if device-owner enrolled,
        // otherwise a one-tap system install prompt).
        UpdateChecker.checkAndInstall(this)

        statusView = findViewById(R.id.statusView)
        urlInput = findViewById(R.id.urlInput)
        urlInput.setText(RealtimeClient.defaultUrl)
        ensureRuntimePermissions()

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            // Accept one URL, or a comma/newline-separated failover list
            // (primary first) so the APK keeps finding the dashboard if it
            // moves to a backup host.
            val urls = urlInput.text.toString()
                .split(',', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (urls.isEmpty()) {
                log("Enter one or more ws:// or wss:// URLs (comma/newline separated)")
                return@setOnClickListener
            }
            log("Connecting to ${urls.joinToString(" , ")}")
            getSharedPreferences(ConnectionService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(ConnectionService.KEY_URL, urls.first())
                .putString(ConnectionService.KEY_URLS, urls.joinToString(","))
                .putBoolean(ConnectionService.KEY_DISCONNECTED, false)
                .apply()
            val linkIntent = Intent(this, ConnectionService::class.java).apply {
                action = ConnectionService.ACTION_START
                putExtra(ConnectionService.EXTRA_URL, urls.first())
            }
            ContextCompat.startForegroundService(this, linkIntent)
            RealtimeClient.connect(urls)
        }

        findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
            getSharedPreferences(ConnectionService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(ConnectionService.KEY_DISCONNECTED, true)
                .apply()
            startService(
                Intent(this, ConnectionService::class.java)
                    .setAction(ConnectionService.ACTION_STOP)
            )
            RealtimeClient.disconnect()
            log("Disconnected — steady link will not auto-restore")
        }

        findViewById<Button>(R.id.btnProjection).setOnClickListener {
            ensureRuntimePermissions()
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        findViewById<Button>(R.id.btnCaptureNow).setOnClickListener {
            Intent(this, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_CAPTURE_NOW)
                .let { ContextCompat.startForegroundService(this, it) }
        }

        findViewById<Button>(R.id.btnStopCapture).setOnClickListener {
            startService(
                Intent(this, ScreenCaptureService::class.java)
                    .setAction(ScreenCaptureService.ACTION_STOP)
            )
            log("Screen capture stopped")
        }

        findViewById<Button>(R.id.btnMic).setOnClickListener {
            ensureRuntimePermissions()
            val intent = Intent(this, AudioRecorderService::class.java)
            if (AudioRecorderService.isRunning) intent.action = AudioRecorderService.ACTION_STOP
            ContextCompat.startForegroundService(this, intent)
        }

        findViewById<Button>(R.id.btnLoc).setOnClickListener {
            ensureRuntimePermissions()
            val intent = Intent(this, LocationService::class.java)
            if (LocationService.isRunning) intent.action = LocationService.ACTION_STOP
            ContextCompat.startForegroundService(this, intent)
        }

        findViewById<Button>(R.id.btnRecord).setOnClickListener {
            ensureRuntimePermissions()
            if (ScreenAudioRecorderService.isRunning) {
                startService(
                    Intent(this, ScreenAudioRecorderService::class.java)
                        .setAction(ScreenAudioRecorderService.ACTION_STOP)
                )
                log("Screen + mic recording stopped")
            } else {
                screenRecLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        findViewById<Button>(R.id.btnDeviceAudio).setOnClickListener {
            ensureRuntimePermissions()
            if (Build.VERSION.SDK_INT < 29) {
                log("Device audio capture needs Android 10 (API 29) or newer")
                return@setOnClickListener
            }
            if (DeviceAudioService.isRunning) {
                startService(
                    Intent(this, DeviceAudioService::class.java)
                        .setAction(DeviceAudioService.ACTION_STOP)
                )
                log("Device audio capture stopped")
            } else {
                deviceAudioLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        RealtimeClient.removeStatusListener(statusLogger)
    }

    /**
     * Steady mode: if the user previously connected (and did not press
     * Disconnect), bring the keep-alive link back right away. This covers
     * app relaunches; BootReceiver covers device reboots, and
     * ConnectionService is START_STICKY for OS-kill restarts.
     */
    private fun restoreSteadyLink() {
        val prefs = getSharedPreferences(ConnectionService.PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(ConnectionService.KEY_DISCONNECTED, true)) return
        val url = prefs.getString(ConnectionService.KEY_URL, null) ?: return
        log("Restoring steady link to $url")
        val linkIntent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_START
            putExtra(ConnectionService.EXTRA_URL, url)
        }
        ContextCompat.startForegroundService(this, linkIntent)
    }

    private fun ensureRuntimePermissions() {
        val required = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 42)
        }
    }

    private fun log(line: String) {
        statusView.append(line + "\n")
    }
}
