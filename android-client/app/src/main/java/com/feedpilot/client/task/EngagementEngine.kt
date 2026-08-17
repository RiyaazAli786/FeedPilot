package com.feedpilot.client.task

import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Outcome of one engagement attempt.
 *
 * Deliberately carries a reason rather than being a bare Boolean: the caller reports this
 * straight to the backend and pays coins for it, so "why did it not land" has to survive
 * the trip. Anything that is not a real, confirmed action must be [Failure].
 */
sealed interface EngagementResult {
    data object Success : EngagementResult

    /** [reason] is user-visible — it lands on the account card and in the action log. */
    data class Failure(val reason: String) : EngagementResult
}

/**
 * Performs the actual platform engagement for one order.
 *
 * This is the single seam between the order pipeline and whatever drives the social platform.
 * Implementations must only return [EngagementResult.Success] when the action genuinely
 * happened — a simulated or assumed success is reported to the backend as real work and
 * earns coins for it.
 */
interface EngagementEngine {

    /** Likes [targetId] (a post link/id) as [accountId]. */
    suspend fun like(targetId: String, accountId: String): EngagementResult

    /** Follows [targetId] (a username/id) as [accountId]. */
    suspend fun follow(targetId: String, accountId: String): EngagementResult

    /** Comments on [targetId] as [accountId]. */
    suspend fun comment(targetId: String, commentText: String, accountId: String): EngagementResult

    /** Reposts [targetId] as [accountId]. */
    suspend fun repost(targetId: String, accountId: String): EngagementResult

    /** Saves post [targetId] as [accountId]. */
    suspend fun savePost(targetId: String, accountId: String): EngagementResult

    /** Views story [targetId] as [accountId]. */
    suspend fun storyView(targetId: String, accountId: String): EngagementResult
}

/**
 * Stand-in engine that performs no automation and always reports success.
 *
 * For local development against the scaffold backend only — bind it in `di/TaskModule` in
 * place of [InstagramEngagementEngine]. It must never be used as a fallback behind the real
 * engine: doing so turns every failed action into a paid, falsely-completed order.
 */
@Singleton
class SimulatedEngagementEngine @Inject constructor() : EngagementEngine {

    override suspend fun like(targetId: String, accountId: String): EngagementResult = dwell()

    override suspend fun follow(targetId: String, accountId: String): EngagementResult = dwell()

    override suspend fun comment(targetId: String, commentText: String, accountId: String): EngagementResult = dwell()

    override suspend fun repost(targetId: String, accountId: String): EngagementResult = dwell()

    override suspend fun savePost(targetId: String, accountId: String): EngagementResult = dwell()

    override suspend fun storyView(targetId: String, accountId: String): EngagementResult = dwell()

    private suspend fun dwell(): EngagementResult {
        delay(Random.nextLong(700, 1800))
        return EngagementResult.Success
    }
}
