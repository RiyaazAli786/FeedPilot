package com.feedpilot.client.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.feedpilot.client.bridge.FeedBridgeRunner
import com.feedpilot.client.data.repository.AuthRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

@HiltWorker
class FeedBridgeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val authRepository: AuthRepository,
    private val runner: FeedBridgeRunner
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!authRepository.isLoggedIn.value) return Result.success()

        val until = Instant.now().plus(RUN_WINDOW)
        return runCatching {
            runner.heartbeat()
            while (Instant.now().isBefore(until)) {
                val ranJob = runner.runOneJob()
                if (!ranJob) delay(IDLE_DELAY_MS)
            }
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    private companion object {
        val RUN_WINDOW: Duration = Duration.ofSeconds(45)
        const val IDLE_DELAY_MS = 5_000L
    }
}
