package com.example.personalrecorder.net

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

/**
 * Uploads finished recordings to the dashboard's /upload endpoint.
 *
 * The target URL is derived from the live WebSocket link
 * ([RealtimeClient.uploadUrl]), so an upload always hits the same host/port
 * and carries the same ?token= as the command channel -- no extra config on
 * the phone even when the server address changes.
 *
 * Uploads run on a single background thread so recordings queue up instead
 * of racing each other or blocking the service's stop path. A failing
 * upload is retried (3 attempts, 2s * attempt backoff) so a brief network
 * blip does not lose the file.
 *
 * Success is silent: the dashboard itself pushes the "media_uploaded"
 * event / rebuilds the file list, and a duplicate success line would clutter
 * the live log. Only failures are reported, via "media_upload_failed" so
 * the commander sees the file that could not be delivered.
 */
object MediaUploader {

    private const val MAX_ATTEMPTS = 3
    private const val BACKOFF_BASE_MS = 2_000L

    /** Serializes uploads; daemon flag keeps the thread from pinning the process. */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "media-uploader").apply { isDaemon = true }
    }

    /**
     * Queue [file] for delivery to the dashboard.
     *
     * @param kind "video" or "audio" -- the dashboard stores it under
     *   media/video or media/audio accordingly.
     * @param category optional sub-category tag (e.g. "call_video",
     *   "call_audio") so the dashboard can split recordings into dedicated
     *   sections (Video calls / Audio calls) on top of the plain kind.
     */
    fun upload(file: File, kind: String, category: String? = null) {
        // Empty/vanished files (instant stop, tiny capture) are not worth
        // three network attempts; the *_stopped event already told the
        // dashboard the recording ended.
        if (!file.exists() || file.length() == 0L) return
        executor.execute { uploadBlocking(file, kind, category) }
    }

    private fun uploadBlocking(file: File, kind: String, category: String?) {
        var lastError: String? = null
        var attempt = 1
        while (attempt <= MAX_ATTEMPTS) {
            val url = RealtimeClient.uploadUrl()
            if (url == null) {
                lastError = "no_active_connection"
                break
            }
            try {
                // Per-upload client: generous timeouts for big video files.
                // newBuilder() keeps the TLS setup (custom lab CA for wss)
                // from RealtimeClient.uploadClient().
                val client: OkHttpClient = RealtimeClient.uploadClient().newBuilder()
                    .writeTimeout(120, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .build()

                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("type", kind)
                    .addFormDataPart("name", file.name)
                    .apply { if (category != null) addFormDataPart("category", category) }
                    .addFormDataPart(
                        "file",
                        file.name,
                        file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                    )
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val ok = response.isSuccessful
                val status = response.code
                response.close()
                if (ok) {
                    // The dashboard pushed media_uploaded and rebuilt the gallery.
                    return
                }
                lastError = "http_$status"
            } catch (e: Exception) {
                lastError = e.message ?: "upload_error"
            }
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(BACKOFF_BASE_MS * attempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
            attempt++
        }
        RealtimeClient.send(
            "media_upload_failed",
            JSONObject()
                .put("path", file.absolutePath)
                .put("kind", kind)
                .put("reason", lastError ?: "unknown")
                .put("attempts", MAX_ATTEMPTS)
        )
    }
}