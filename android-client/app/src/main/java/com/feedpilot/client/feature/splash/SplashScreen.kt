package com.feedpilot.client.feature.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feedpilot.client.BuildConfig
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onSplashComplete: () -> Unit
) {
    var progress by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("Initializing FeedPilot Engine...") }

    // Pulsing animation for the FeedPilot emblem
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    LaunchedEffect(Unit) {
        progress = 0.25f
        statusText = "Initializing Local Database..."
        delay(350)

        progress = 0.55f
        statusText = "Loading SMM Providers & Task Limits..."
        delay(350)

        progress = 0.85f
        statusText = "Verifying Account Session & Wallet..."
        delay(350)

        progress = 1.0f
        statusText = "Ready! Opening Application..."
        delay(250)

        onSplashComplete()
    }

    val gradientBg = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF1B0F2E),
            Color(0xFF10071F),
            Color(0xFF07030D)
        )
    )

    val emblemGradient = Brush.linearGradient(
        colors = listOf(Color(0xFFFFDF00), Color(0xFFF5B426), Color(0xFFE1306C))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradientBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Multi-ring Glowing Emblem Container (App Icon)
            Box(
                modifier = Modifier
                    .scale(scale)
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFF5B426).copy(alpha = glowAlpha),
                                Color(0xFFE1306C).copy(alpha = glowAlpha * 0.4f),
                                Color.Transparent
                            )
                        )
                    )
                    .border(width = 2.5.dp, brush = emblemGradient, shape = CircleShape)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = com.feedpilot.client.R.drawable.ic_launcher_foreground),
                    contentDescription = "FeedPilot App Icon",
                    modifier = Modifier.size(86.dp)
                )
            }

            Spacer(Modifier.height(30.dp))

            // App Title
            Text(
                text = "FeedPilot",
                fontWeight = FontWeight.Black,
                fontSize = 34.sp,
                color = Color.White,
                letterSpacing = 1.2.sp
            )

            Spacer(Modifier.height(8.dp))

            // Subtitle Glassmorphism Badge
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF833AB4).copy(alpha = 0.35f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF5B426).copy(alpha = 0.4f))
            ) {
                Text(
                    text = "Instant Engagement • Coin Economy • Multi-Account",
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            Spacer(Modifier.height(52.dp))

            // Progress Bar & Percentage
            Column(
                modifier = Modifier.fillMaxWidth(0.84f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFFF5B426),
                    trackColor = Color.White.copy(alpha = 0.12f)
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = statusText,
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1f)
                    )

                    Text(
                        text = "${(progress * 100).toInt()}%",
                        color = Color(0xFFFFDF00),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Bottom Footer
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "FeedPilot v${BuildConfig.VERSION_NAME} • Encrypted Session",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp
            )
        }
    }
}
