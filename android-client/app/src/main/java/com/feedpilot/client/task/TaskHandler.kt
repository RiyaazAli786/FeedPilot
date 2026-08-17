package com.feedpilot.client.task

import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.local.TaskEntity
import com.feedpilot.client.data.repository.TargetRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Executes one order type. One implementation per [EngagementTaskType]. */
interface TaskHandler {
    val type: EngagementTaskType

    /** Performs the order for [accountId]. Throwing is allowed — [TaskExecutor] maps it to a failure. */
    suspend fun perform(task: TaskEntity, accountId: String): TaskOutcome
}

/** Likes the post identified by `task.targetId`. */
@Singleton
class LikeTaskHandler @Inject constructor(
    private val targets: TargetRepository,
    private val engine: EngagementEngine
) : TaskHandler {

    override val type = EngagementTaskType.LIKE

    override suspend fun perform(task: TaskEntity, accountId: String): TaskOutcome {
        if (task.targetId.isBlank()) return TaskOutcome.Failure("Order has no target post")

        // Best effort — a target we can't preview is still worth attempting.
        val preview = targets.fetchPost(task.targetId)
        val label = (preview as? Resource.Success)?.data?.id ?: task.targetId

        return when (val result = engine.like(task.targetId, accountId)) {
            is EngagementResult.Success -> TaskOutcome.Success("Liked $label")
            is EngagementResult.Failure -> TaskOutcome.Failure("$label: ${result.reason}")
        }
    }
}

/** Follows the account identified by `task.targetId`. */
@Singleton
class FollowTaskHandler @Inject constructor(
    private val targets: TargetRepository,
    private val engine: EngagementEngine
) : TaskHandler {

    override val type = EngagementTaskType.FOLLOW

    override suspend fun perform(task: TaskEntity, accountId: String): TaskOutcome {
        if (task.targetId.isBlank()) return TaskOutcome.Failure("Order has no target account")

        // task.targetId can be a raw profile URL, not just a bare username — engine.follow()
        // below normalizes it internally before resolving, but this preview lookup didn't, so it
        // was handing Instagram's profile API something it was never meant to parse as a
        // username. That's what let a lookup resolve to the wrong account (observed: the log
        // ended up naming the performing account itself instead of the actual target) even
        // though the follow below, which normalizes correctly, still landed on the right one.
        val cleanTargetId = com.feedpilot.client.common.InstagramCrypto.parseUsername(task.targetId) ?: task.targetId

        // Best effort — a target we can't preview is still worth attempting.
        val profile = targets.fetchProfile(cleanTargetId)
        val username = (profile as? Resource.Success)?.data?.username ?: cleanTargetId
        if (profile is Resource.Success && profile.data.isPrivate) {
            return TaskOutcome.Failure("Can't process order on private account.")
        }

        return when (val result = engine.follow(task.targetId, accountId)) {
            is EngagementResult.Success -> TaskOutcome.Success("Followed @$username")
            is EngagementResult.Failure -> TaskOutcome.Failure("@$username: ${result.reason}")
        }
    }
}

/** Comments on the post identified by `task.targetId`. */
@Singleton
class CommentTaskHandler @Inject constructor(
    private val targets: TargetRepository,
    private val engine: EngagementEngine
) : TaskHandler {

    override val type = EngagementTaskType.COMMENT

    override suspend fun perform(task: TaskEntity, accountId: String): TaskOutcome {
        if (task.targetId.isBlank()) return TaskOutcome.Failure("Order has no target post")
        val commentText = task.commentText?.ifBlank { null } ?: "Great post!"

        return when (val result = engine.comment(task.targetId, commentText, accountId)) {
            is EngagementResult.Success -> TaskOutcome.Success("Commented on ${task.targetId}")
            is EngagementResult.Failure -> TaskOutcome.Failure("${task.targetId}: ${result.reason}")
        }
    }
}

/** Reposts the post identified by `task.targetId`. */
@Singleton
class RepostTaskHandler @Inject constructor(
    private val engine: EngagementEngine
) : TaskHandler {

    override val type = EngagementTaskType.REPOST

    override suspend fun perform(task: TaskEntity, accountId: String): TaskOutcome {
        if (task.targetId.isBlank()) return TaskOutcome.Failure("Order has no target post")

        return when (val result = engine.repost(task.targetId, accountId)) {
            is EngagementResult.Success -> TaskOutcome.Success("Reposted ${task.targetId}")
            is EngagementResult.Failure -> TaskOutcome.Failure("${task.targetId}: ${result.reason}")
        }
    }
}

/** Saves the post identified by `task.targetId`. */
@Singleton
class SavePostTaskHandler @Inject constructor(
    private val engine: EngagementEngine
) : TaskHandler {

    override val type = EngagementTaskType.SAVE_POST

    override suspend fun perform(task: TaskEntity, accountId: String): TaskOutcome {
        if (task.targetId.isBlank()) return TaskOutcome.Failure("Order has no target post")

        return when (val result = engine.savePost(task.targetId, accountId)) {
            is EngagementResult.Success -> TaskOutcome.Success("Saved post ${task.targetId}")
            is EngagementResult.Failure -> TaskOutcome.Failure("${task.targetId}: ${result.reason}")
        }
    }
}

/** Views the Instagram story identified by `task.targetId`. */
@Singleton
class StoryViewTaskHandler @Inject constructor(
    private val engine: EngagementEngine
) : TaskHandler {

    override val type = EngagementTaskType.STORY_VIEW

    override suspend fun perform(task: TaskEntity, accountId: String): TaskOutcome {
        if (task.targetId.isBlank()) return TaskOutcome.Failure("Order has no target story")

        return when (val result = engine.storyView(task.targetId, accountId)) {
            is EngagementResult.Success -> TaskOutcome.Success("Viewed story ${task.targetId}")
            is EngagementResult.Failure -> TaskOutcome.Failure("${task.targetId}: ${result.reason}")
        }
    }
}
