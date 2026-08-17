package com.feedpilot.client.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.feedpilot.client.BuildConfig
import com.feedpilot.client.R
import com.feedpilot.client.common.Constants
import com.feedpilot.client.common.DeviceIdentity
import com.feedpilot.client.data.repository.UpdateProgress
import com.feedpilot.client.data.repository.UpdateRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/** Periodically checks the backend for a newer release, downloads it, and prompts install. */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val updateRepository: UpdateRepository,
    private val deviceIdentity: DeviceIdentity
) : CoroutineWorker(context, params) {

    /** See DeviceIdentity.cloneScopedNotificationId — keeps two same-package clones from
     *  silently overwriting each other's update-ready notification. */
    private val notificationId by lazy { deviceIdentity.cloneScopedNotificationId(NOTIFICATION_ID_BASE) }

    override suspend fun doWork(): Result {
        val release = updateRepository.checkForUpdate(BuildConfig.VERSION_CODE)
        return if (release != null) {
            var verified: File? = null
            var failed = false
            updateRepository.downloadAndVerify(release).collect { progress ->
                when (progress) {
                    is UpdateProgress.Verified -> verified = progress.file
                    is UpdateProgress.Failed -> failed = true
                    is UpdateProgress.Downloading -> Unit
                }
            }
            verified?.let { showInstallNotification(release.versionName, it) }
            if (failed && verified == null) return Result.retry()
            Result.success(
                workDataOf(
                    KEY_UPDATE_AVAILABLE to true,
                    KEY_VERSION_NAME to release.versionName
                )
            )
        } else {
            Result.success(workDataOf(KEY_UPDATE_AVAILABLE to false))
        }
    }

    private fun showInstallNotification(versionName: String, file: File) {
        createChannel()
        val uri = updateRepository.contentUriFor(file)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        val pending = PendingIntent.getActivity(
            context,
            notificationId,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, Constants.UPDATE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("FeedPilot update ready")
            .setContentText("Version $versionName downloaded. Tap to install.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.UPDATE_CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val NOTIFICATION_ID_BASE = 4301
        const val KEY_UPDATE_AVAILABLE = "update_available"
        const val KEY_VERSION_NAME = "version_name"
    }
}
