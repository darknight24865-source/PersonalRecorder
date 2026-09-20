package com.example.personalrecorder

import android.app.Application
import android.os.Build
import android.os.Process
import com.example.personalrecorder.net.RealtimeClient
import org.json.JSONObject

/**
 * Crash reporter.
 *
 * Installs a default uncaught-exception handler that best-effort ships an
 * `app_crash` event (thread, exception class, message, capped stack trace)
 * over the existing WebSocket before the process dies. The server logs it,
 * persists it to data/crashes.jsonl and pushes an event to the dashboard,
 * so crashes on the phone surface without adb/logcat access.
 *
 * The previously installed handler is captured BEFORE ours is set (and we
 * compare identity before delegating), so a handler installed by a library
 * can never wrap us and create a recursive loop. If no previous handler
 * exists we fall back to killing the process ourselves, matching the
 * behaviour of the platform default.
 */
class PersonalRecorderApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Capture the PREVIOUS handler BEFORE installing ours.
        val prev = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val ver = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: ""
                } catch (_: Exception) {
                    ""
                }
                val payload = JSONObject()
                    .put("thread", thread.name)
                    .put("class", throwable.javaClass.name)
                    .put("msg", throwable.message ?: "")
                    .put("stack", buildStack(throwable))
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("model", Build.MODEL)
                    .put("ver", ver)
                // Best effort; a crash thread must never die waiting for the socket.
                try {
                    RealtimeClient.send("app_crash", payload)
                } catch (_: Exception) {
                }
            } catch (_: Exception) {
            }

            // Delegate to the platform default / previously installed handler,
            // unless (pathologically) the handler chain points back at us.
            if (prev != null && prev !== Thread.getDefaultUncaughtExceptionHandler()) {
                prev.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    /** Flat, bounded stack dump (message + up to ~3500 chars of frames). */
    private fun buildStack(t: Throwable): String {
        val sb = StringBuilder()
        sb.append(t.javaClass.name).append(": ").append(t.message ?: "").append('\n')
        for (el in t.stackTrace) {
            if (sb.length > 3500) {
                sb.append("    ...")
                break
            }
            sb.append("    at ").append(el.toString()).append('\n')
        }
        return sb.toString()
    }
}
