package com.feedpilot.client.task

/**
 * Order types the client knows how to execute. Mirrors the backend `TaskType` enum —
 * add a value here and a matching [TaskHandler] to support a new order type.
 */
enum class EngagementTaskType(val wireName: String, val label: String) {
    LIKE("Like", "Like post"),
    FOLLOW("Follow", "Follow account"),
    COMMENT("Comment", "Comment on post"),
    REPOST("Repost", "Repost post"),
    SAVE_POST("SavePost", "Save post"),
    STORY_VIEW("StoryView", "View story");

    companion object {
        /** Tolerant parse: accepts the wire name ("Like") or the raw ordinal ("0"). */
        fun from(raw: String?): EngagementTaskType? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }?.let { return it }
            return value.toIntOrNull()?.let { entries.getOrNull(it) }
        }
    }
}
