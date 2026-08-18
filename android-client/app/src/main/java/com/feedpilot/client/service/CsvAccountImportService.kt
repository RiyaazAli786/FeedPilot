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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.feedpilot.client.MainActivity
import com.feedpilot.client.R
import com.feedpilot.client.common.Constants
import com.feedpilot.client.common.TotpCode
import com.feedpilot.client.data.repository.AccountRepository
import com.feedpilot.client.data.repository.AddAccountOutcome
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class CsvAccountImportService : Service() {
    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var importState: CsvAccountImportState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        val filePath = intent?.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        startForegroundCompat(buildNotification("Starting CSV import...", ongoing = true))
        running = true
        scope.launch {
            try {
                processFile(filePath)
            } finally {
                running = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun processFile(filePath: String) {
        val csvText = runCatching { File(filePath).readText() }.getOrDefault("")
        val rows = parseCsvLoginRows(csvText)
        if (rows.isEmpty()) {
            val stats = CsvAccountImportStats(message = "No usable rows found in CSV.")
            importState.update(stats)
            notify(stats)
            return
        }

        var added = 0
        var failed = 0
        rows.forEachIndexed { index, row ->
            update(
                CsvAccountImportStats(
                    running = true,
                    total = rows.size,
                    processed = index,
                    added = added,
                    failed = failed,
                    currentUsername = row.username,
                    message = "Logging in @${row.username}"
                )
            )

            val outcome = runCatching {
                val login = accountRepository.addImportedAccountWithCredentials(row.username, row.password)
                submitTotpIfAvailable(login, row.twoFactorSecret)
            }.getOrElse { AddAccountOutcome.Failed(it.message ?: "Import failed") }

            when (outcome) {
                AddAccountOutcome.Added -> added++
                else -> failed++
            }

            update(
                CsvAccountImportStats(
                    running = true,
                    total = rows.size,
                    processed = index + 1,
                    added = added,
                    failed = failed,
                    currentUsername = row.username,
                    message = importOutcomeMessage(row.username, outcome)
                )
            )
        }

        update(
            CsvAccountImportStats(
                running = false,
                total = rows.size,
                processed = rows.size,
                added = added,
                failed = failed,
                message = "CSV import complete: $added added, $failed failed."
            )
        )
    }

    private fun update(stats: CsvAccountImportStats) {
        importState.update(stats)
        notify(stats)
    }

    private suspend fun submitTotpIfAvailable(
        outcome: AddAccountOutcome,
        twoFactorSecret: String
    ): AddAccountOutcome {
        if (outcome !is AddAccountOutcome.NeedsTwoFactor) return outcome
        val normalizedSecret = TotpCode.normalizeSecret(twoFactorSecret)
        if (normalizedSecret.isBlank()) {
            return AddAccountOutcome.Failed("Two-factor code required, but CSV row has no 2FA secret.")
        }
        val code = TotpCode.generate(normalizedSecret)
            ?: return AddAccountOutcome.Failed("Invalid 2FA secret for @${outcome.challenge.username}.")
        return accountRepository.submitTwoFactorCode(outcome.challenge, code, requirePicked = false)
    }

    private fun importOutcomeMessage(username: String, outcome: AddAccountOutcome): String = when (outcome) {
        AddAccountOutcome.Added -> "Added @$username"
        is AddAccountOutcome.AlreadyExists -> "@$username already exists"
        is AddAccountOutcome.Failed -> "@$username failed: ${outcome.message}"
        is AddAccountOutcome.NeedsTwoFactor -> "@$username needs a valid 2FA secret"
        is AddAccountOutcome.NeedsEmailCode -> "@$username needs email verification"
    }

    private fun notify(stats: CsvAccountImportStats) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(stats.message ?: "CSV import", ongoing = stats.running))
    }

    private fun buildNotification(text: String, ongoing: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_OPEN_CSV_IMPORT_STATUS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Constants.CSV_IMPORT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("CSV Account Login")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    Constants.CSV_IMPORT_CHANNEL_ID,
                    "CSV account import",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun parseCsvLoginRows(csvText: String): List<CsvLoginRow> {
        val lines = csvText.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val headers = parseCsvLine(lines.first()).map { it.trim().uppercase() }
        fun column(vararg names: String): Int =
            names.firstNotNullOfOrNull { name -> headers.indexOf(name).takeIf { it >= 0 } } ?: -1

        val usernameIndex = column("USERNAME", "USER", "HANDLE")
        val emailIndex = column("EMAIL")
        val passwordIndex = column("PASSWORD", "PASS")
        val twoFactorIndex = column("2FA CODE", "2FA SECRET", "TWO FACTOR SECRET", "TOTP SECRET")
        if (passwordIndex < 0 || (usernameIndex < 0 && emailIndex < 0)) return emptyList()

        return lines.drop(1).mapNotNull { line ->
            val cells = parseCsvLine(line)
            fun cell(index: Int): String = if (index >= 0) cells.getOrNull(index)?.trim().orEmpty() else ""
            val username = cell(usernameIndex).ifBlank { cell(emailIndex) }
            val password = cell(passwordIndex)
            if (username.isBlank() || password.isBlank()) {
                null
            } else {
                CsvLoginRow(username.removePrefix("@"), password, TotpCode.normalizeSecret(cell(twoFactorIndex)))
            }
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val char = line[i]
            when {
                char == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    cells.add(current.toString())
                    current.clear()
                }
                else -> current.append(char)
            }
            i++
        }
        cells.add(current.toString())
        return cells
    }

    private data class CsvLoginRow(
        val username: String,
        val password: String,
        val twoFactorSecret: String
    )

    companion object {
        const val ACTION_OPEN_CSV_IMPORT_STATUS = "com.feedpilot.client.OPEN_CSV_IMPORT_STATUS"
        private const val EXTRA_FILE_PATH = "csv_file_path"
        private const val NOTIFICATION_ID = 4309

        fun start(context: Context, csvFilePath: String) {
            val intent = Intent(context, CsvAccountImportService::class.java)
                .putExtra(EXTRA_FILE_PATH, csvFilePath)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
