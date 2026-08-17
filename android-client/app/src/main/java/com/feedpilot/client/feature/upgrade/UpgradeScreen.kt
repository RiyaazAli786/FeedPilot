package com.feedpilot.client.feature.upgrade

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedpilot.client.ui.components.PurpleTopBar

@Composable
fun UpgradeScreen(
    onBack: () -> Unit,
    viewModel: UpgradeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF6F7FB))
    ) {
        // Purple Top Navigation Bar
        PurpleTopBar(title = "Upgrade Account", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(4.dp))

            // Golden Crown Icon Header
            Icon(
                imageVector = Icons.Filled.EmojiEvents,
                contentDescription = "FeedPilot Emblem",
                tint = Color(0xFFFFB800),
                modifier = Modifier.size(42.dp)
            )

            Spacer(Modifier.height(4.dp))

            // Main Title
            Text(
                text = "Upgrade your account",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFB800),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(4.dp))

            // Subtitle / Description Text
            Text(
                text = "Complete all tasks to migrate your account (+2 coins/action). Resets daily.",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.DarkGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(Modifier.height(14.dp))

            if (!state.isLoading && !state.hasActiveAccount) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "No active account. Go to the Tasks screen, tap an account's avatar and log in to activate it first.",
                        modifier = Modifier.padding(14.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(20.dp))
            } else if (state.isUpgraded) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFE8F5E9),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "@${state.activeAccount?.username} is upgraded for the next 24 hours — earning +2 extra coins per action.",
                        modifier = Modifier.padding(14.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF2E7D32),
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            if (state.hasActiveAccount) {
                GenderSelector(
                    selected = state.activeAccount?.gender ?: "male",
                    onSelect = viewModel::setGender
                )
                Spacer(Modifier.height(20.dp))
            }

            // Task Checklist Items
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                state.tasks.forEach { task ->
                    TaskChecklistCard(
                        task = task,
                        isUpgradeActive = state.isUpgraded,
                        onAction = { viewModel.toggleTaskStatus(task.id) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            val isUpgrading = state.isLoading || state.tasks.any { it.status == TaskStatus.PROCESSING }

            // Main Action Button: Upgrade Account
            Button(
                onClick = {
                    if (state.checklistComplete) {
                        viewModel.performUpgrade()
                    } else {
                        viewModel.autoCompleteAndUpgrade()
                    }
                },
                enabled = !isUpgrading && !state.isUpgraded && state.hasActiveAccount,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF9800),
                    contentColor = Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(44.dp)
            ) {
                if (isUpgrading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = when {
                            state.isUpgraded -> "Account Upgraded ✨"
                            !state.hasActiveAccount -> "Activate an account first"
                            state.checklistComplete -> "Upgrade Account"
                            else -> "Complete and Upgrade"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GenderSelector(
    selected: String,
    onSelect: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Account gender",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF444444),
                modifier = Modifier.weight(1f)
            )
            listOf("male" to "Male", "female" to "Female").forEach { (value, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { onSelect(value) }
                        .padding(start = 4.dp)
                ) {
                    RadioButton(
                        selected = selected == value,
                        onClick = { onSelect(value) },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF8C52FF))
                    )
                    Text(label, fontSize = 13.sp, color = Color(0xFF444444))
                }
            }
        }
    }
}

@Composable
private fun TaskChecklistCard(
    task: UpgradeTaskItem,
    isUpgradeActive: Boolean,
    onAction: () -> Unit
) {
    val effectiveStatus = if (isUpgradeActive) TaskStatus.COMPLETED else task.status
    val canStartTask = !isUpgradeActive && task.status == TaskStatus.PENDING

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 1.5.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canStartTask) Modifier.clickable { onAction() } else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Circle Badge (Light Blue background with small blue stacked text)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEBF3FE)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = task.circleLabel,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3C78D8),
                    textAlign = TextAlign.Center,
                    lineHeight = 11.sp
                )
            }

            Spacer(Modifier.width(10.dp))

            // Middle Text Details
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = task.title,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF222222)
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = task.progressText ?: task.subtitle,
                    fontSize = 10.5.sp,
                    fontWeight = if (task.progressText != null) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (task.progressText != null) Color(0xFF3C78D8) else Color(0xFF777777),
                    lineHeight = 13.sp
                )
            }

            Spacer(Modifier.width(8.dp))

            // Right Action Button Badge
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (effectiveStatus == TaskStatus.COMPLETED) Color(0xFF4CAF50) else Color(0xFF8C52FF),
                modifier = Modifier
                    .height(34.dp)
                    .width(42.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    when (effectiveStatus) {
                        TaskStatus.PENDING -> {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Start Task",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        TaskStatus.PROCESSING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        }
                        TaskStatus.COMPLETED -> {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Done",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
