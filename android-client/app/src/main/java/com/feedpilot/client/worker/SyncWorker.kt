package com.feedpilot.client.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.feedpilot.client.data.repository.AccountRepository
import com.feedpilot.client.data.repository.AccountSessionGate
import com.feedpilot.client.data.repository.AuthRepository
import com.feedpilot.client.data.repository.TaskRepository
import com.feedpilot.client.data.repository.WalletRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic background sync: pulls accounts, downloads pending tasks, and refreshes the wallet.
 * Scheduled via WorkManager (see FeedPilotApp).
 *
 * Runs every 15 minutes for the life of the install, so it checks for an account up front. Each
 * repository below refuses on its own too — this just stops the wake-up from touching the network
 * at all while the app is sitting on the login screen.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accountRepository: AccountRepository,
    private val taskRepository: TaskRepository,
    private val walletRepository: WalletRepository,
    private val authRepository: AuthRepository,
    private val sessionGate: AccountSessionGate
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!authRepository.isLoggedIn.value) return Result.success()

        accountRepository.refresh()
        walletRepository.refresh()

        if (sessionGate.isSignedIn()) {
            taskRepository.downloadPending()
        }
        return Result.success()
    }
}
