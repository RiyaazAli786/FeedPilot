package com.feedpilot.client.feature.updates

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.BuildConfig
import com.feedpilot.client.data.remote.dto.VersionDto
import com.feedpilot.client.data.repository.UpdateProgress
import com.feedpilot.client.data.repository.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class UpdatesUiState(
    val checking: Boolean = false,
    val release: VersionDto? = null,
    val upToDate: Boolean = false,
    val downloadPercent: Int = 0,
    val downloading: Boolean = false,
    val readyFile: File? = null,
    val error: String? = null,
    val skipped: Boolean = false
)

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val localDownloadState = MutableStateFlow(Triple(false, 0, null as File?))
    private val localErrorState = MutableStateFlow<String?>(null)

    val state: StateFlow<UpdatesUiState> = combine(
        updateRepository.latestRelease,
        updateRepository.isChecking,
        updateRepository.isSkipped,
        localDownloadState,
        localErrorState
    ) { release: VersionDto?, checking: Boolean, skipped: Boolean, downloadInfo: Triple<Boolean, Int, File?>, error: String? ->
        val (downloading, percent, readyFile) = downloadInfo
        UpdatesUiState(
            checking = checking,
            release = release,
            upToDate = !checking && release == null,
            downloadPercent = percent,
            downloading = downloading,
            readyFile = readyFile,
            error = error,
            skipped = skipped
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UpdatesUiState())

    init { check() }

    fun check() {
        viewModelScope.launch {
            localErrorState.value = null
            updateRepository.checkForUpdate(BuildConfig.VERSION_CODE)
        }
    }

    fun skip() {
        updateRepository.skip()
    }

    fun download() {
        val release = state.value.release ?: return
        viewModelScope.launch {
            localErrorState.value = null
            localDownloadState.value = Triple(true, 0, null)
            updateRepository.downloadAndVerify(release).collect { progress ->
                when (progress) {
                    is UpdateProgress.Downloading ->
                        localDownloadState.value = Triple(true, progress.percent, null)
                    is UpdateProgress.Verified ->
                        localDownloadState.value = Triple(false, 100, progress.file)
                    is UpdateProgress.Failed -> {
                        localDownloadState.value = Triple(false, 0, null)
                        localErrorState.value = progress.reason
                    }
                }
            }
        }
    }

    /** Launches the system package installer for the verified APK. */
    fun install() {
        val file = state.value.readyFile ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
