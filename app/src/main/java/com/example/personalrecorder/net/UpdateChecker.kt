package com.example.personalrecorder.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.jvm.Volatile
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Silent background auto-update (milestone m8).
 *
 * Flow:
 *  1. [checkAndInstall] runs on a background thread (guarded against
 *     concurrent runs) whenever the app starts or the relay link opens.
 *  2. It asks the GitHub API for the latest release of [UPDATE_REPO].
 *  3. If the release tag `v<versionCode>` is newer than the installed
 *     versionCode, it downloads the APK asset and verifies its SHA-256
 *     (published in the release body by CI).
 *  4. Install path:
 *     - Device-owner enrollment (`adb shell dpm set-device-owner ...`) ->
 *       fully silent PackageInstaller session (no UI at all).
 *     - Otherwise -> one-tap system installer via FileProvider (the user
 *       taps "Install" once; this is the platform's only public-API path
 *       on a stock phone).
 *
 * The app stays fully visible: enrollment is a deliberate, documented adb
 * step, and the one-tap path shows the normal install screen.
 */
object UpdateChecker {

    /**
     * GitHub repo that publishes the APK releases, "owner/repo".
     * TODO: set this to your GitHub account, e.g. "alice/PersonalRecorder".
     */
    const val UPDATE_REPO = "your-gh-user/PersonalRecorder"

    private const val TAG = "UpdateChecker"
    private const val API_BASE = "https://api.github.com/repos"
    private val SHA256_RE = Regex("sha256[\\s:]+([0-9a-fA-F]{64})")
    private const val UPDATE_DIR = "updates"

    /** Shared with [UpdateInstallReceiver] for the result notification. */
    const val CHANNEL_ID = "update_install_channel"
    const val NOTIF_ID = 2007

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var running = false

    /** Fire-and-forget entry point. Safe to call from any thread. */
    fun checkAndInstall(context: Context) {
        if (running) return
        running = true
        Thread {
            try {
                val info = checkForUpdate(context) ?: return@Thread
                val file = download(context, info) ?: return@Thread
                install(context, file)
            } catch (t: Throwable) {
                Log.w(TAG, "update check failed: ${t.message}")
            } finally {
                running = false
            }
        }.start()
    }

    private fun installedVersionCode(context: Context): Int {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            pkg.versionCode
        }
    }

    private data class UpdateInfo(
        val versionCode: Int,
        val apkUrl: String,
        val apkName: String,
        val sha256: String?
    )

    private fun checkForUpdate(context: Context): UpdateInfo? {
        val repo = UPDATE_REPO.trim().trim('/')
        if (repo.isEmpty() || repo.startsWith("your-gh-user")) {
            Log.i(TAG, "UPDATE_REPO not configured; skipping update check")
            return null
        }
        val url = "$API_BASE/$repo/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PersonalRecorder-lab")
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.i(TAG, "release lookup HTTP ${resp.code} (rate limit or no release yet)")
                return null
            }
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            val versionCode = tag.removePrefix("v").toIntOrNull() ?: return null
            val installed = installedVersionCode(context)
            if (versionCode <= installed) {
                Log.i(TAG, "installed v$installed is current (release $tag)")
                return null
            }
            val releaseBody = json.optString("body", "")
            val sha256 = SHA256_RE.find(releaseBody)?.groupValues?.get(1)
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            var apkName: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                if (name.endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url", "")
                    apkName = name
                    break
                }
            }
            val u = apkUrl ?: return null
            val n = apkName ?: return null
            return UpdateInfo(versionCode, u, n, sha256)
        }
    }

    private fun download(context: Context, info: UpdateInfo): File? {
        val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        val target = File(dir, info.apkName)
        if (target.exists() && info.sha256 != null && sha256(target) == info.sha256) {
            Log.i(TAG, "cached APK already matches sha256")
            return target
        }
        val request = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", "PersonalRecorder-lab")
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "download failed: HTTP ${resp.code}")
                return null
            }
            val bytes = resp.body?.bytes() ?: return null
            FileOutputStream(target).use { it.write(bytes) }
        }
        if (info.sha256 != null) {
            val actual = sha256(target)
            if (!actual.equals(info.sha256, ignoreCase = true)) {
                Log.w(TAG, "sha256 mismatch: expected ${info.sha256}, got $actual")
                target.delete()
                return null
            }
        }
        Log.i(TAG, "downloaded ${target.name} (${target.length()} bytes)")
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun install(context: Context, file: File) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            silentInstall(context, file)
        } else {
            promptInstall(context, file)
        }
    }

    /** Fully silent: only possible for a device-owner app (dpm set-device-owner). */
    private fun silentInstall(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // USER_ACTION_NOT_REQUIRED is public API 34+; on older devices the
            // platform defaults device-owner installs to silent anyway.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        val intent = Intent(context, UpdateInstallReceiver::class.java)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
        val pi = PendingIntent.getBroadcast(
            context, sessionId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            val out = session.openWrite("pkg", 0, file.length())
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            session.fsync(out)
            out.close()
            session.commit(pi.intentSender)
            Log.i(TAG, "silent install committed (session $sessionId)")
        } catch (t: Throwable) {
            session.abandon()
            Log.w(TAG, "silent install failed: ${t.message}")
        } finally {
            session.close()
        }
    }

    /** One-tap fallback: opens the system package installer. */
    private fun promptInstall(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.i(TAG, "one-tap install screen opened")
    }
}

/**
 * Receives the PackageInstaller commit result and surfaces it as a
 * notification. Top-level (not nested) so the manifest entry stays clean.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val message = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Update installed — restart the app to apply it."
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "Update needs one tap to finish."
            else -> "Update failed (status $status)."
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    UpdateChecker.CHANNEL_ID,
                    "App updates",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val notif = Notification.Builder(context, UpdateChecker.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("PersonalRecorder update")
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        nm.notify(UpdateChecker.NOTIF_ID, notif)
    }
}
