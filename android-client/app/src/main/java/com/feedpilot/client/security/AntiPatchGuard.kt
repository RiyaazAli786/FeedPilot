package com.feedpilot.client.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

object AntiPatchGuard {

    data class IntegrityResult(
        val isTampered: Boolean,
        val reason: String? = null
    )

    /**
     * Performs full integrity and anti-tamper check.
     */
    fun verifyAppIntegrity(context: Context, expectedSignatureSha256: String? = null): IntegrityResult {
        // Automatically bypass checks for debug builds and debug APK installations
        if (com.feedpilot.client.BuildConfig.DEBUG ||
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 ||
            context.packageName.endsWith(".debug") ||
            context.packageName.endsWith(".staging")
        ) {
            return IntegrityResult(isTampered = false, reason = "Debug build allowed for testing")
        }

        // 1. Check for attached Debugger or Debuggable flag in release
        if (isDebuggerAttached(context)) {
            return IntegrityResult(isTampered = true, reason = "Debugger or dynamic analysis detected")
        }

        // 2. Cloners commonly rewrite the runtime package name after the APK was built.
        if (context.packageName != com.feedpilot.client.BuildConfig.APPLICATION_ID) {
            return IntegrityResult(isTampered = true, reason = "Unofficial cloned package detected")
        }

        // 3. Check for Frida / Xposed / Hooking frameworks in process memory
        if (isHookingFrameworkPresent()) {
            return IntegrityResult(isTampered = true, reason = "Instrumentation hooking engine detected")
        }

        // 4. Verify APK signature hash if expected hash is provided
        if (!expectedSignatureSha256.isNullOrBlank()) {
            val currentSignature = getAppSignatureSha256(context)
            if (currentSignature == null || !currentSignature.equals(expectedSignatureSha256, ignoreCase = true)) {
                return IntegrityResult(isTampered = true, reason = "APK signature mismatch (tampered/re-signed)")
            }
        }

        return IntegrityResult(isTampered = false)
    }

    /**
     * Obtains the SHA-256 hex digest of the application's signing certificate.
     */
    fun getAppSignatureSha256(context: Context): String? {
        return try {
            val pm = context.packageManager
            val packageName = context.packageName

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = packageInfo.signingInfo
                if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                } else null
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (signatures.isNullOrEmpty()) return null

            val certBytes = signatures[0].toByteArray()
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(certBytes)
            bytesToHex(digest)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Detects Frida, Xposed, Substrate, and other dynamic hooking libraries loaded into memory.
     */
    fun isHookingFrameworkPresent(): Boolean {
        try {
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists() && mapsFile.canRead()) {
                BufferedReader(FileReader(mapsFile)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line?.lowercase() ?: continue
                        if (HOOK_LIBRARY_MARKERS.any { l.contains(it) }) return true
                    }
                }
            }
        } catch (_: Exception) { }

        // Frida's server listens on these. The connect is bounded: an unbounded one runs on
        // whatever thread called in — the main thread, at startup — and a network stack that
        // does not refuse the connection outright would stall the launch into an ANR.
        for (port in FRIDA_PORTS) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), PORT_PROBE_TIMEOUT_MS)
                    return true
                }
            } catch (_: Exception) { } catch (_: Error) { }
        }

        return false
    }

    /**
     * Checks if the app is currently running under a debugger.
     */
    fun isDebuggerAttached(context: Context): Boolean {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            return true
        }

        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // In release builds, debuggable flag must not be true
        if (!context.packageName.endsWith(".debug") && !context.packageName.endsWith(".staging") && isDebuggable) {
            return true
        }

        return false
    }

    /**
     * Gracefully exits the application process if severe tampering is detected.
     *
     * Deliberately not called from application startup. Killing the process there turns any
     * false positive from these heuristics into an app that cannot be opened at all, with no
     * message explaining why — indistinguishable from a crash, and unfixable by the user.
     * Callers should treat [verifyAppIntegrity] as a signal to report or degrade, not to exit.
     */
    fun terminateProcess() {
        try {
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        } catch (_: Exception) { }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        val hexArray = "0123456789ABCDEF".toCharArray()
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = hexArray[v ushr 4]
            hexChars[i * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    /**
     * Substrings that identify a hooking framework's library in /proc/self/maps.
     *
     * Kept specific on purpose. Bare "epic" and "gadget" used to be on this list and match any
     * mapped path that happens to contain those letters — vendor libraries, asset paths, the
     * app's own data directory — on hardware that has nothing to do with hooking. With the
     * result no longer able to kill the process a false positive is survivable, but it still
     * makes the signal useless.
     */
    private val HOOK_LIBRARY_MARKERS = listOf(
        "frida",        // covers libfrida-agent / libfrida-gadget
        "xposed",
        "substrate",
        "sandhook",
        "libepic",      // the Epic hooking framework's library, not the word "epic"
        "libriru",
        "libwhale"
    )

    private val FRIDA_PORTS = intArrayOf(27042, 27047)
    private const val PORT_PROBE_TIMEOUT_MS = 150
}
