package com.feedpilot.client.task

import android.util.Log
import com.feedpilot.client.common.apiErrorMessage
import com.feedpilot.client.data.local.TaskEntity
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes a pending order to the handler for its type and performs the action.
 *
 * To support a new order type: add it to [EngagementTaskType], write a [TaskHandler], and
 * list the handler in this constructor.
 */
@Singleton
class TaskExecutor @Inject constructor(
    likeHandler: LikeTaskHandler,
    followHandler: FollowTaskHandler,
    commentHandler: CommentTaskHandler,
    repostHandler: RepostTaskHandler,
    savePostHandler: SavePostTaskHandler,
    storyViewHandler: StoryViewTaskHandler
) {
    private val handlers: Map<EngagementTaskType, TaskHandler> =
        listOf(likeHandler, followHandler, commentHandler, repostHandler, savePostHandler, storyViewHandler).associateBy { it.type }

    /** Order types this build can execute — used to filter what the runner claims. */
    val supportedTypes: Set<EngagementTaskType> = handlers.keys

    /**
     * Performs [task] as [accountId], dispatching on the order type.
     *
     * [allowedTypes] narrows execution to the user's selected task mode; anything outside it is
     * skipped rather than failed so another run can still pick it up.
     */
    suspend fun execute(
        task: TaskEntity,
        accountId: String,
        allowedTypes: Set<EngagementTaskType> = supportedTypes
    ): TaskOutcome {
        val type = EngagementTaskType.from(task.taskType)
            ?: return TaskOutcome.Skipped("Unsupported order type '${task.taskType}'")

        if (type !in allowedTypes) return TaskOutcome.Skipped("${type.label} not selected")

        val handler = handlers[type]
            ?: return TaskOutcome.Skipped("No handler for ${type.wireName}")

        return try {
            handler.perform(task, accountId)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Order ${task.id} (${type.wireName}) failed", t)
            TaskOutcome.Failure(t.apiErrorMessage("${type.label} failed"))
        }
    }

    private companion object {
        const val TAG = "TaskExecutor"
    }
}
