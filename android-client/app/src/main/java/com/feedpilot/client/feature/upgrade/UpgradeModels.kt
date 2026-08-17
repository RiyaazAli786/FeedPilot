package com.feedpilot.client.feature.upgrade

enum class TaskStatus { PENDING, PROCESSING, COMPLETED }

data class UpgradeTaskItem(
    val id: String,
    val circleLabel: String,
    val title: String,
    val subtitle: String,
    val status: TaskStatus = TaskStatus.PENDING,
    val progressText: String? = null
)
