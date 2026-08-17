package com.feedpilot.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.feedpilot.client.ui.theme.AppTheme
import com.feedpilot.client.ui.theme.StatusGreen
import com.feedpilot.client.ui.theme.StatusRed
import com.feedpilot.client.ui.theme.StatusYellow

/** Maps a backend account status string to a traffic-light color. */
fun statusColor(status: String): Color = when (status) {
    "Active" -> StatusGreen
    "Suspended", "Disabled", "LoginFailed" -> StatusRed
    else -> StatusYellow
}

/**
 * Helper to strip unwanted prefixes (like "code: ", "handle: ", "user: ", "@", "https://...")
 * so copying from order history or profile cards yields the raw handle or post code.
 */
fun cleanCopyText(raw: String): String {
    var text = raw.trim()
    text = text.replace(Regex("""^(code|handle|user|post|target):\s*""", RegexOption.IGNORE_CASE), "")
    text = text.removePrefix("@").trim()

    if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
        val shortcode = com.feedpilot.client.common.InstagramCrypto.getCodeFromUrl(text)
        if (shortcode.isNotBlank()) return shortcode
        val handle = com.feedpilot.client.common.InstagramCrypto.parseUsername(text)
        if (!handle.isNullOrBlank()) return handle.removePrefix("@")
        return text.trimEnd('/')
    }
    return text.trimEnd('/')
}

/**
 * Small icon button that copies an Instagram handle or code to the clipboard and confirms with a toast.
 * Strips prefixes like "code: ", "@", "https://..." before copying.
 */
@Composable
fun CopyUsernameButton(
    username: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    keepAtPrefix: Boolean = false
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.IconButton(
        onClick = {
            val clean = cleanCopyText(username)
            val toCopy = if (keepAtPrefix && !clean.startsWith("http")) "@$clean" else clean
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(toCopy))
            android.widget.Toast.makeText(context, "Copied: $toCopy", android.widget.Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.size(28.dp).then(modifier)
    ) {
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = "Copy $username",
            tint = tint,
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
fun StatusDot(status: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(statusColor(status))
    )
}

/** White rounded card used throughout the app (matches the design's soft-shadow tiles). */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val base = Modifier
        .clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
    Box(
        modifier
            .then(base)
            .then(if (onClick != null) Modifier.clickableRow(onClick) else Modifier)
    ) { content() }
}

/** The white "@username · id" chip shown under the header on Followers / Posts screens. */
@Composable
fun AccountChip(
    username: String,
    accountId: String? = null,
    avatarUrl: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = CircleShape, color = Color.White) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Avatar(avatarUrl, size = 22)
                Text("@$username", color = AppTheme.brand.magenta, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        if (accountId != null) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0x33FFFFFF)) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Badge, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text(accountId, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * @param isActive Shows a green dot badge over the bottom-right corner — this account's session
 *   has been confirmed logged in on instagram.com, as opposed to merely holding stored cookies.
 * @param isUpgraded Shows a gold crown badge over the top-right corner — this account currently
 *   has a live 24h upgrade window (+2 coins per action). See [com.feedpilot.client.common.isUpgradedWithin24h].
 */
@Composable
fun Avatar(
    url: String?,
    size: Int = 44,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    isUpgraded: Boolean = false
) {
    val shape = CircleShape
    Box(modifier) {
        if (url.isNullOrBlank()) {
            Box(
                Modifier
                    .size(size.dp)
                    .clip(shape)
                    .background(AppTheme.brand.magenta.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text("👤", fontSize = (size / 2).sp)
            }
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .size(size.dp)
                    .clip(shape)
            )
        }
        if (isActive) {
            val dotSize = (size * 0.32f).dp
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(StatusGreen)
            )
        }
        if (isUpgraded) {
            val badgeSize = (size * 0.42f).dp
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(badgeSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFB800)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.WorkspacePremium,
                    contentDescription = "Upgraded",
                    tint = Color.White,
                    modifier = Modifier.size(badgeSize * 0.72f)
                )
            }
        }
    }
}

fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
