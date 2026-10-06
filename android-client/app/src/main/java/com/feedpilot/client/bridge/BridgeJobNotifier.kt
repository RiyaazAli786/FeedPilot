package com.feedpilot.client.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.feedpilot.client.MainActivity
import com.feedpilot.client.R
import com.feedpilot.client.common.DeviceIdentity
import com.feedpilot.client.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BridgeJobNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: DeviceIdentity,
    private val settings: SettingsRepository
) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val notificationId get() = identity.cloneScopedNotificationId(7301)

    suspend fun show(status: BridgeStatus, newRequest: Boolean = false) {
        val preferences = settings.settings.first()
        if (!preferences.bridgeEnabled || !preferences.notificationsEnabled) { cancel(); return }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Bridge requests", NotificationManager.IMPORTANCE_DEFAULT))
            if (newRequest) manager.cancel(notificationId)
            val target = status.currentUsername?.let { "Target: @$it" }.orEmpty()
            val account = status.accountUsername?.let { "Fetching account: @$it" }.orEmpty()
            val text = listOf(account, target, status.message).filter { it.isNotBlank() }.joinToString("\n")
            val open = PendingIntent.getActivity(context, notificationId,
                Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_LOGS)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(notificationId, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (status.state == BridgeConnectionState.RunningJob) "Resolving bridge request" else status.pillLabel)
                .setContentText(listOf(account, target).filter { it.isNotBlank() }.joinToString(" · "))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build())
        }
    }

    fun cancel() { runCatching { manager.cancel(notificationId) } }

    companion object {
        private const val CHANNEL = "feedpilot_bridge_requests"
        const val ACTION_OPEN_LOGS = "com.feedpilot.client.OPEN_BRIDGE_LOGS"
    }
}
