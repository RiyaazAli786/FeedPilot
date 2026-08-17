package com.feedpilot.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.feedpilot.client.MainActivity
import com.feedpilot.client.R
import com.feedpilot.client.common.Constants
import com.feedpilot.client.common.DeviceIdentity
import com.feedpilot.client.common.isWithinLast24h
import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.repository.InstagramRepository
import com.feedpilot.client.feature.upgrade.provider.UpgradeDataProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Foreground service that runs one upgrade-checklist task (post/profile/story/bio upload +
 * verify) to completion independently of [com.feedpilot.client.feature.upgrade.UpgradeScreen].
 *
 * This used to run inside [com.feedpilot.client.feature.upgrade.UpgradeViewModel] on
 * `viewModelScope`, which is cancelled the moment that screen is popped off the back stack —
 * navigating away (or switching tabs) mid-upload silently killed the task with nothing
 * uploaded, and tapping the same task again just repeated the same partial work. Running it
 * here instead means it survives navigating away, backgrounding the app, or locking the screen.
 * A deliberate swipe from Recents is treated as an explicit stop.
 */
@AndroidEntryPoint
class UpgradeRunnerService : Service() {

    @Inject lateinit var accountDao: AccountDao
    @Inject lateinit var instagramRepository: InstagramRepository
    @Inject lateinit var upgradeDataProvider: UpgradeDataProvider
    @Inject lateinit var runnerState: UpgradeRunnerState
    @Inject lateinit var accountRepository: com.feedpilot.client.data.repository.AccountRepository
    @Inject lateinit var deviceIdentity: DeviceIdentity

    /** See DeviceIdentity.cloneScopedNotificationId — keeps two same-package clones from
     *  silently overwriting each other's foreground-service notification. */
    private val notificationId by lazy { deviceIdentity.cloneScopedNotificationId(NOTIFICATION_ID_BASE) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** At most one task runs at a time — a second start request for the same task is a no-op. */
    private var job: Job? = null
    private var runningAccountId: String? = null
    private var runningTaskId: String? = null
    private var stoppedFromRecents = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID)
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)

        if (accountId.isNullOrBlank() || taskId.isNullOrBlank()) {
            // Obligated to call startForeground() shortly after startForegroundService() even
            // when there is nothing to run — see TaskRunnerService's identical handling.
            if (job?.isActive != true) {
                startForegroundCompat(buildNotification("Nothing to run"))
                stopSelf()
            }
            return START_NOT_STICKY
        }

        if (job?.isActive == true) {
            // Already running some task — a duplicate tap on the checklist must not start a
            // second run of it (or of a different task) in parallel.
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification("Starting…"))
        runningAccountId = accountId
        runningTaskId = taskId
        stoppedFromRecents = false
        job = scope.launch { runTask(accountId, taskId) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "App task removed from recents; stopping active upgrade runner")
        stoppedFromRecents = true
        val accountId = runningAccountId
        val taskId = runningTaskId
        if (!accountId.isNullOrBlank() && !taskId.isNullOrBlank()) {
            runnerState.finish(
                accountId,
                taskId,
                success = false,
                progressText = null,
                message = "Stopped because the app was cleared from recent apps."
            )
        }
        job?.cancel()
        stopForegroundCompat()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private suspend fun executeSingleTask(accountId: String, taskId: String): Boolean {
        val account = accountDao.getById(accountId) ?: return false
        runnerState.start(accountId, taskId)
        updateNotification("Verifying ${taskLabel(taskId)} for @${account.username}")

        var profile = runCatching {
            instagramRepository.getLoggedInUserProfile(account.sessionCookies)
        }.onFailure {
            Log.w(TAG, "Could not fetch current Instagram profile for @${account.username}", it)
        }.getOrNull()

        var failureReason: String? = null

        var success = when (taskId) {
            "posts" -> {
                val feed = runCatching {
                    instagramRepository.getUserFeed(profile?.id ?: "", customCookies = account.sessionCookies)
                }.getOrNull()
                val nowSec = System.currentTimeMillis() / 1000
                val oneDayAgoSec = nowSec - 24 * 60 * 60
                val count = feed?.items?.count { item -> item.takenAt >= oneDayAgoSec } ?: 0
                count >= 6
            }
            "profile" -> profile != null && !profile.hasAnonymousProfilePic && isWithinLast24h(account.profileTaskCompletedAtMs)
            "story" -> profile?.let {
                runCatching { instagramRepository.hasActiveStory(it.id, account.sessionCookies) }.getOrDefault(false)
            } ?: false
            "bio" -> !profile?.biography.isNullOrBlank() && isWithinLast24h(account.bioTaskCompletedAtMs)
            else -> false
        }
        Log.i(TAG, "Initial verification taskId=$taskId success=$success profileLoaded=${profile != null}")

        if (!success) {
            when (taskId) {
                "posts" -> {
                    val feed = runCatching {
                        instagramRepository.getUserFeed(profile?.id ?: "", customCookies = account.sessionCookies)
                    }.getOrNull()
                    val nowSec = System.currentTimeMillis() / 1000
                    val oneDayAgoSec = nowSec - 24 * 60 * 60
                    val currentCount = feed?.items?.count { item -> item.takenAt >= oneDayAgoSec } ?: 0
                    val needed = 6 - currentCount
                    if (needed > 0) {
                        for (i in 1..needed) {
                            val uploadId = System.currentTimeMillis().toString()
                            Log.i(TAG, "Uploading upgrade post $i/$needed for @${account.username} uploadId=$uploadId")
                            val postAsset = withContext(Dispatchers.IO) {
                                upgradeDataProvider.getNextPost(account.username, account.gender)
                            }
                            setProgress(accountId, taskId, "Uploading post $i/$needed from ${postAsset.imageName}")
                            val uploaded = instagramRepository.uploadPhoto(postAsset.imageBytes, uploadId, isStory = false, account.sessionCookies)
                            if (uploaded) {
                                setProgress(accountId, taskId, "Publishing post $i/$needed from ${postAsset.imageName}")
                                instagramRepository.configurePhoto(uploadId, postAsset.caption, account.sessionCookies)
                            }
                            delay(1000)
                        }
                    }
                    success = true
                }
                "profile" -> {
                    val profileAsset = withContext(Dispatchers.IO) {
                        upgradeDataProvider.getNextProfilePic(account.username, account.gender)
                    }
                    setProgress(accountId, taskId, "Uploading profile from ${profileAsset.fileName}")
                    instagramRepository.getProfilePicProps(account.username, account.sessionCookies)
                    instagramRepository.updateProfilePicture(profileAsset.bytes, account.sessionCookies)
                    delay(1000)
                    success = true
                }
                "story" -> {
                    val uploadId = System.currentTimeMillis().toString()
                    val storyAsset = withContext(Dispatchers.IO) {
                        upgradeDataProvider.getNextStoryImage(account.username, account.gender)
                    }
                    setProgress(accountId, taskId, "Uploading story from ${storyAsset.fileName}")
                    val uploaded = instagramRepository.uploadPhoto(storyAsset.bytes, uploadId, isStory = true, account.sessionCookies)
                    if (uploaded) {
                        setProgress(accountId, taskId, "Publishing story from ${storyAsset.fileName}")
                        instagramRepository.configureStory(uploadId, account.sessionCookies)
                    }
                    delay(1000)
                    success = true
                }
                "bio" -> {
                    val firstName = profile?.fullName ?: ""
                    val username = account.username
                    val bioAsset = withContext(Dispatchers.IO) {
                        upgradeDataProvider.getNextBioText(account.username, account.gender)
                    }
                    setProgress(accountId, taskId, "Uploading bio from ${bioAsset.fileName}")
                    instagramRepository.updateBiography(bioAsset.text, firstName, username, account.sessionCookies)
                    delay(1000)
                    success = true
                }
            }
        }

        if (success) {
            when (taskId) {
                "profile" -> accountDao.updateProfileTaskCompletedAt(accountId, System.currentTimeMillis())
                "bio" -> accountDao.updateBioTaskCompletedAt(accountId, System.currentTimeMillis())
            }
        }

        val finalProgress = _lastProgress[taskId]?.let { text ->
            if (success) text.replace("Uploading", "Uploaded").replace("Publishing", "Uploaded") else null
        }
        val message = if (success) "Task verified — completed on Instagram." else (failureReason ?: "Not done yet — complete this on Instagram or tap again to retry.")
        Log.i(TAG, "runTask finished: taskId=$taskId success=$success reason=$failureReason")
        runnerState.finish(accountId, taskId, success, finalProgress, message)
        return success
    }

    private suspend fun runTask(accountId: String, taskId: String) {
        try {
            val account = accountDao.getById(accountId)
            if (account == null || account.sessionCookies.isBlank()) {
                runnerState.finish(accountId, taskId, success = false, progressText = null,
                    message = "Instagram session missing. Log in to this account again.")
                return
            }

            if (taskId == "all") {
                val tasks = listOf("posts", "profile", "story", "bio")
                var allSuccess = true
                for (t in tasks) {
                    val success = executeSingleTask(accountId, t)
                    if (!success) {
                        allSuccess = false
                        break
                    }
                    delay(3000) // Delay between tasks as requested
                }
                if (allSuccess) {
                    updateNotification("Upgrading account for @${account.username}")
                    when (val result = accountRepository.performUpgrade(accountId)) {
                        is com.feedpilot.client.common.Resource.Success -> {
                            upgradeDataProvider.resetAccountFolderAssignment(account.username, account.gender)
                            runnerState.finish(
                                accountId = accountId,
                                taskId = "all",
                                success = true,
                                progressText = "Upgraded",
                                message = "Account successfully upgraded! You now earn +2 extra coins per action for the next 24 hours."
                            )
                        }
                        is com.feedpilot.client.common.Resource.Error -> {
                            runnerState.finish(
                                accountId = accountId,
                                taskId = "all",
                                success = false,
                                progressText = null,
                                message = result.message
                            )
                        }
                        com.feedpilot.client.common.Resource.Loading -> Unit
                    }
                } else {
                    runnerState.finish(
                        accountId = accountId,
                        taskId = "all",
                        success = false,
                        progressText = null,
                        message = "Could not complete all tasks. Please try again."
                    )
                }
            } else {
                executeSingleTask(accountId, taskId)
            }
        } catch (c: CancellationException) {
            if (!stoppedFromRecents) {
                runnerState.finish(accountId, taskId, success = false, progressText = null, message = "Stopped")
            }
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "runTask crashed for taskId=$taskId", t)
            runnerState.finish(accountId, taskId, success = false, progressText = null,
                message = "Something went wrong: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            _lastProgress.remove(taskId)
            runningAccountId = null
            runningTaskId = null
            stoppedFromRecents = false
            stopForegroundCompat()
            stopSelf()
        }
    }

    /** Tracks the latest progress line per task so it can be "Uploaded…"-ified on success above. */
    private val _lastProgress = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun setProgress(accountId: String, taskId: String, text: String) {
        _lastProgress[taskId] = text
        runnerState.progress(accountId, taskId, text)
        updateNotification(text)
    }

    private fun taskLabel(taskId: String) = when (taskId) {
        "posts" -> "posts"
        "profile" -> "profile photo"
        "story" -> "story"
        "bio" -> "bio"
        else -> taskId
    }

    // ---------- Notification ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.UPGRADE_RUNNER_CHANNEL_ID,
                "Account Upgrade",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while an upgrade-checklist task uploads to Instagram."
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val open = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, Constants.UPGRADE_RUNNER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Upgrading account")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            NotificationManagerCompat.from(this).notify(notificationId, buildNotification(text))
        }
    }

    companion object {
        private const val TAG = "UpgradeRunnerService"
        private const val NOTIFICATION_ID_BASE = 4202
        private const val EXTRA_ACCOUNT_ID = "account_id"
        private const val EXTRA_TASK_ID = "task_id"

        /** True while some upgrade task is running for [accountId], per [UpgradeRunnerState]. */
        fun isRunning(runnerState: UpgradeRunnerState, accountId: String): Boolean =
            runnerState.anyRunning(accountId)

        fun start(context: Context, accountId: String, taskId: String) {
            val intent = Intent(context, UpgradeRunnerService::class.java).apply {
                putExtra(EXTRA_ACCOUNT_ID, accountId)
                putExtra(EXTRA_TASK_ID, taskId)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
