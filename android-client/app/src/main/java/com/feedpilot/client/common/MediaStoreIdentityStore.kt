package com.feedpilot.client.common

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists and recovers a per-package installation token using Android MediaStore / Public Storage.
 *
 * MediaStore files survive "App Data Clear" (Settings -> Apps -> Storage -> Clear Data) and
 * app reinstalls on modern Android versions, allowing each clone/installation to reliably
 * recover its original installation ID even after local app storage is wiped.
 */
@Singleton
class MediaStoreIdentityStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Reads the stored installation ID for this specific package and user handle.
     * Returns null if no valid token is found or storage is inaccessible.
     */
    fun readToken(packageName: String, userHandleId: Int): String? {
        val fileName = getFileName(packageName, userHandleId)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                readFromMediaStore(fileName)
            } else {
                readFromLegacyStorage(fileName)
            }
        }.getOrNull()?.takeIf { it.isNotBlank() && it.length >= 8 }
    }

    /**
     * Writes the installation ID for this specific package and user handle to persistent external storage.
     */
    fun writeToken(packageName: String, userHandleId: Int, token: String) {
        if (token.isBlank()) return
        val fileName = getFileName(packageName, userHandleId)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToMediaStore(fileName, token)
            } else {
                writeToLegacyStorage(fileName, token)
            }
        }
    }

    private fun getFileName(packageName: String, userHandleId: Int): String {
        val seed = "$packageName|user_$userHandleId"
        val hash = sha256Hex(seed).take(16)
        return ".tf_install_$hash.id"
    }

    private fun readFromMediaStore(fileName: String): String? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)
        val contentUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        context.contentResolver.query(contentUri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val id = cursor.getLong(idIndex)
                val fileUri = Uri.withAppendedPath(contentUri, id.toString())
                return context.contentResolver.openInputStream(fileUri)?.use { stream ->
                    stream.bufferedReader().readText().trim()
                }
            }
        }
        return null
    }

    private fun writeToMediaStore(fileName: String, token: String) {
        val contentResolver = context.contentResolver
        val contentUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        // Query to check if file already exists
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)

        var existingUri: Uri? = null
        contentResolver.query(contentUri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val id = cursor.getLong(idIndex)
                existingUri = Uri.withAppendedPath(contentUri, id.toString())
            }
        }

        val targetUri = existingUri ?: run {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            contentResolver.insert(contentUri, contentValues)
        } ?: return

        contentResolver.openOutputStream(targetUri, "rwt")?.use { stream ->
            stream.write(token.toByteArray())
            stream.flush()
        }
    }

    @Suppress("DEPRECATION")
    private fun readFromLegacyStorage(fileName: String): String? {
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
        return if (file.exists()) file.readText().trim() else null
    }

    @Suppress("DEPRECATION")
    private fun writeToLegacyStorage(fileName: String, token: String) {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(token)
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
}
