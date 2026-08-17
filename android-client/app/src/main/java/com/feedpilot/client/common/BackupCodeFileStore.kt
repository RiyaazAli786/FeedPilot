package com.feedpilot.client.common

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a human-readable copy of a generated Backup Code to the device's public Downloads
 * folder — a fallback for an operator who generated a code but didn't copy/save it anywhere
 * before losing local app data.
 *
 * Deliberately write-only: the app never reads this file back automatically. Doing so would
 * reopen exactly the bug [DeviceIdentity.installationId]'s doc describes — a same-package
 * "clone" produced by a virtualization-style cloner tool shares this exact public storage with
 * the original install, so an auto-read here would silently hand a brand-new clone the
 * original's account again. This file exists purely for a human (the operator, or support) to
 * find later in a file manager and paste into the Restore screen by hand — never for the app
 * itself to act on.
 *
 * The filename includes this install's own [DeviceIdentity.installationId] fragment (not
 * [DeviceIdentity.appId], which is identical across exactly this kind of clone) so two clones on
 * one device never silently overwrite each other's saved code under one shared filename.
 */
@Singleton
class BackupCodeFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceIdentity: DeviceIdentity
) {
    fun save(code: String) {
        val now = Date()
        // The timestamp doubles as extra insurance against two clones colliding on one filename
        // alongside the installationId fragment, and lets an operator with several saved copies
        // (one per clone, or one per time they regenerated) tell them apart at a glance in a file
        // manager without having to open each one.
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        val fileName = "FeedPilot_Backup_Code_${deviceIdentity.installationId.take(8)}_$timestamp.txt"
        val content = buildString {
            appendLine("FeedPilot Backup Code")
            appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now)}")
            appendLine("Device: ${deviceIdentity.deviceLabel}")
            appendLine()
            appendLine("BACKUP CODE: $code")
            appendLine()
            appendLine("Keep this safe. After a reinstall or app data clear, open the app, tap")
            appendLine("\"Restore a previous account\" on the first screen (or Settings > Restore),")
            appendLine("and paste this code to get your coins and linked Instagram accounts back.")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToMediaStore(fileName, content)
            } else {
                writeToLegacyStorage(fileName, content)
            }
        }
    }

    private fun writeToMediaStore(fileName: String, content: String) {
        val resolver = context.contentResolver
        val contentUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(contentUri, values) ?: return
        resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
    }

    // Pre-API 29 requires WRITE_EXTERNAL_STORAGE, which this app does not request — this is a
    // best-effort fallback there (silently a no-op via the runCatching in save()), matching how
    // MediaStoreIdentityStore's own legacy path already behaves on those same OS versions.
    @Suppress("DEPRECATION")
    private fun writeToLegacyStorage(fileName: String, content: String) {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        File(dir, fileName).writeText(content)
    }
}
