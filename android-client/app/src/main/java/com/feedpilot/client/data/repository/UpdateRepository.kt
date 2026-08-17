package com.feedpilot.client.data.repository

import android.content.Context
import androidx.core.content.FileProvider
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.dto.VersionDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateProgress {
    data class Downloading(val percent: Int) : UpdateProgress
    data class Verified(val file: File) : UpdateProgress
    data class Failed(val reason: String) : UpdateProgress
}

@Singleton
class UpdateRepository @Inject constructor(
    private val api: ApiService,
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) {
    private val _latestRelease = MutableStateFlow<VersionDto?>(null)
    val latestRelease: StateFlow<VersionDto?> = _latestRelease.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _isSkipped = MutableStateFlow(false)
    val isSkipped: StateFlow<Boolean> = _isSkipped.asStateFlow()

    /** Returns the latest release if its versionCode is newer than the installed one. */
    suspend fun checkForUpdate(currentVersionCode: Int): VersionDto? {
        _isChecking.value = true
        _isSkipped.value = false
        val release = runCatching { api.getLatestVersion() }
            .getOrNull()
            ?.takeIf { it.versionCode > currentVersionCode }
        _latestRelease.value = release
        _isChecking.value = false
        return release
    }

    fun skip() {
        _isSkipped.value = true
    }

    fun resetSkip() {
        _isSkipped.value = false
    }

    /**
     * Downloads the APK with resume support, emitting progress, then verifies its SHA-256
     * against the release metadata before surfacing the file for installation.
     * Gracefully handles HTTP 416 (Range Not Satisfiable) by clearing invalid cached files
     * and retrying full downloads.
     */
    fun downloadAndVerify(release: VersionDto): Flow<UpdateProgress> = flow {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val target = File(dir, release.localApkFileName())

        // 1. If target already exists and passes SHA-256 check, return it immediately
        if (target.exists() && release.sha256.isNotBlank() && sha256(target).equals(release.sha256, ignoreCase = true)) {
            emit(UpdateProgress.Verified(target))
            return@flow
        }

        // 2. If target exists and is >= total expected size, delete to ensure clean download
        if (target.exists() && release.sizeBytes > 0 && target.length() >= release.sizeBytes) {
            target.delete()
        }

        var existing = if (target.exists()) target.length() else 0L

        var request = Request.Builder()
            .url(release.apkUrl)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        var response = okHttpClient.newCall(request).execute()

        // 3. If HTTP 416 (Range Not Satisfiable) or range request failed, clear stale target and fallback to full download from 0
        if (response.code == 416 || (!response.isSuccessful && existing > 0)) {
            response.close()
            target.delete()
            existing = 0L
            request = Request.Builder().url(release.apkUrl).build()
            response = okHttpClient.newCall(request).execute()
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                emit(UpdateProgress.Failed("HTTP ${resp.code}"))
                return@flow
            }
            val body = resp.body ?: run {
                emit(UpdateProgress.Failed("Empty response"))
                return@flow
            }

            val isPartial = resp.code == 206
            val startOffset = if (isPartial) existing else 0L
            val total = body.contentLength() + startOffset
            var downloaded = startOffset

            body.byteStream().use { input ->
                java.io.FileOutputStream(target, isPartial && startOffset > 0).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            emit(UpdateProgress.Downloading(((downloaded * 100) / total).toInt()))
                        }
                    }
                }
            }
        }

        val actualSha = sha256(target)
        if (release.sha256.isNotBlank() && !actualSha.equals(release.sha256, ignoreCase = true)) {
            target.delete()
            emit(UpdateProgress.Failed("SHA-256 mismatch"))
        } else {
            emit(UpdateProgress.Verified(target))
        }
    }.flowOn(Dispatchers.IO)

    /** Returns a content:// uri suitable for the package installer. */
    fun contentUriFor(file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun VersionDto.localApkFileName(): String {
        val urlName = apkUrl.substringBefore('?').substringAfterLast('/')
        return urlName
            .takeIf { it.endsWith(".apk", ignoreCase = true) && it.none(File.separatorChar::equals) }
            ?: "feedpilot-$versionName-$versionCode.apk"
    }
}
