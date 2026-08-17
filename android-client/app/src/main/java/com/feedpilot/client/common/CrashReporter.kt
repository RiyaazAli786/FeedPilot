package com.feedpilot.client.common

import android.content.Context
import android.os.Build
import android.util.Log
import com.feedpilot.client.BuildConfig
import com.feedpilot.client.CrashActivity
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches anything that would otherwise kill the app silently, parses a user-readable error description,
 * relays the crash report to the Telegram backend log, and hands the details to [CrashActivity].
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val DIR = "crashes"
    private const val KEEP = 10

    @Volatile private var installed = false

    /**
     * Installs the handler. Call from `attachBaseContext` — that runs before content providers,
     * Hilt injection and `onCreate`, which is where a startup crash actually happens.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val report = runCatching { buildReport(thread, error) }.getOrElse { error.toString() }
            val (userTitle, userSummary) = runCatching { parseUserFriendlyMessage(error) }
                .getOrDefault("Application Notice" to "An unexpected application error occurred.")

            runCatching { Log.e(TAG, report) }
            val file = runCatching { writeReport(appContext, report) }.getOrNull()

            // Asynchronously post crash details to backend Telegram logger
            runCatching { sendCrashToTelegram(appContext, userTitle, userSummary, report) }

            runCatching { CrashActivity.show(appContext, userTitle, userSummary, report, file?.absolutePath) }

            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                @Suppress("DEPRECATION")
                System.exit(10)
            }
        }
    }

    fun parseUserFriendlyMessage(error: Throwable): Pair<String, String> {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return when {
            stack.contains("ForegroundServiceStartNotAllowedException") ||
                stack.contains("Time limit already exhausted") ||
                stack.contains("ForegroundService") -> {
                "Background Task Limit Reached" to
                    "Android system background execution limits paused the task service. Please open FeedPilot and tap Start again to resume background processing."
            }
            stack.contains("SocketTimeoutException") ||
                stack.contains("UnknownHostException") ||
                stack.contains("ConnectException") -> {
                "Connection Interrupted" to
                    "Unable to reach the server. Please check your internet connection and try again."
            }
            stack.contains("SQLiteException") ||
                stack.contains("RoomException") -> {
                "Local Storage Notice" to
                    "A local database notice occurred. FeedPilot will automatically recover data when restarted."
            }
            else -> {
                val simpleName = error.javaClass.simpleName
                val msg = error.localizedMessage?.takeIf { it.isNotBlank() } ?: "An unexpected error occurred"
                "Application Notice" to "$msg ($simpleName)."
            }
        }
    }

    /**
     * Relays the crash report directly to backend Telegram monitoring endpoint (`/api/log/crash`).
     */
    fun sendCrashToTelegram(context: Context, title: String, summary: String, rawReport: String) {
        Thread {
            try {
                val baseUrl = BuildConfig.API_BASE_URL.trimEnd('/')
                val url = "$baseUrl/api/log/crash"
                val json = JSONObject().apply {
                    put("title", title)
                    put("summary", summary)
                    put("stackTrace", rawReport)
                }.toString()

                val deviceId = runCatching { DeviceIdentity(context).deviceUuid }.getOrDefault("-")

                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("X-App-Id", BuildConfig.APPLICATION_ID)
                    setRequestProperty("X-Device-Model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    setRequestProperty("X-Device-Id", deviceId)
                }
                conn.outputStream.use { os ->
                    os.write(json.toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                Log.d(TAG, "Relayed crash to Telegram endpoint, status code: $code")
                conn.disconnect()
            } catch (t: Throwable) {
                Log.w(TAG, "Could not send crash report to Telegram endpoint", t)
            }
        }.start()
    }

    /** Directory the reports are written to, so it can be named in the UI. */
    fun crashDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, DIR)

    private fun buildReport(thread: Thread, error: Throwable): String {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("FeedPilot crash report")
            appendLine("time      : ${timestamp()}")
            appendLine("app       : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
            appendLine("package   : ${BuildConfig.APPLICATION_ID}")
            appendLine("device    : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("android   : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("abi       : ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("thread    : ${thread.name}")
            appendLine()
            append(stack)
        }
    }

    private fun writeReport(context: Context, report: String): File? = try {
        val dir = crashDir(context).apply { mkdirs() }
        val file = File(dir, "crash-${fileStamp()}.txt")
        file.writeText(report)
        prune(dir)
        Log.e(TAG, "Crash report written to ${file.absolutePath}")
        file
    } catch (t: Throwable) {
        Log.e(TAG, "Could not write the crash report", t)
        null
    }

    /** Keeps the newest [KEEP] reports so a crash loop cannot fill the device up. */
    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") } ?: return
        files.sortedByDescending { it.lastModified() }.drop(KEEP).forEach { it.delete() }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}

/**
 * True when the current process is the separate one [CrashActivity] runs in.
 */
fun Context.isCrashReportProcess(): Boolean {
    val name = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> android.app.Application.getProcessName()
        else -> runCatching {
            File("/proc/self/cmdline").readText().trim { it <= ' ' }
        }.getOrNull()
    }
    return name != null && name.endsWith(CrashActivity.PROCESS_SUFFIX)
}
