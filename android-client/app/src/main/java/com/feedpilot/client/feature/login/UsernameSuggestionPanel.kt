package com.feedpilot.client.feature.login

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feedpilot.client.common.BrazilUsernameGenerator
import com.feedpilot.client.common.DeviceIdentity
import com.feedpilot.client.data.remote.InstagramWebClient
import com.feedpilot.client.data.remote.UsernameValidationResult
import com.feedpilot.client.data.remote.dto.PagedPickedUsernamesDto
import com.feedpilot.client.data.remote.dto.PickedUsernameDto
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun UsernameSuggestionPanel(
    instagramWebClient: InstagramWebClient,
    onUsernamePicked: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val deviceId = remember(context) { DeviceIdentity(context).hardwareDeviceId }
    // Scopes Suggested/Picked to this one clone: deviceId alone is the physical-device hardware
    // id, shared by every clone on the phone, so without appId they'd all see one merged list.
    val appId = remember(context) { DeviceIdentity(context).appId }
    val pickedPrefs = remember(context) {
        context.getSharedPreferences("picked_usernames_cache", Context.MODE_PRIVATE)
    }

    var selectedTab by remember { mutableIntStateOf(0) }

    // Suggested tab state
    var generatedIdentity by remember { mutableStateOf(BrazilUsernameGenerator.generate()) }
    var isCheckingApi by remember { mutableStateOf(false) }
    var apiResult by remember { mutableStateOf<UsernameValidationResult?>(null) }
    var pickedUsernameState by remember { mutableStateOf<String?>(null) }
    var applyingUsernameState by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(generatedIdentity) {
        kotlinx.coroutines.delay(2000)
        isCheckingApi = true
        apiResult = null
        apiResult = instagramWebClient.fetchUsernameSuggestions(generatedIdentity.username)
        isCheckingApi = false
    }

    // Picked tab state
    var pickedPage by remember { mutableIntStateOf(1) }
    var pickedResponse by remember { mutableStateOf<PagedPickedUsernamesDto?>(null) }
    var isHistoryLoading by remember { mutableStateOf(false) }
    var userToDelete by remember { mutableStateOf<String?>(null) }
    var pickedRefreshKey by remember { mutableIntStateOf(0) }

    fun cleanPickedUsername(username: String): String =
        username.trim().removePrefix("@").lowercase()

    fun localPickedNames(): List<String> =
        pickedPrefs.getString("picked_list", "")
            .orEmpty()
            .lines()
            .map(::cleanPickedUsername)
            .filter { it.isNotBlank() }
            .distinct()

    fun saveLocalPicked(username: String) {
        val clean = cleanPickedUsername(username)
        if (clean.isBlank()) return

        val updated = listOf(clean) + localPickedNames()
            .filterNot { it.equals(clean, ignoreCase = true) }
        val pickedSet = pickedPrefs.getStringSet("picked_set", emptySet()).orEmpty()
            .map(::cleanPickedUsername)
            .filter { it.isNotBlank() }
            .toMutableSet()
        pickedSet.add(clean)
        pickedPrefs.edit()
            .putString("picked_list", updated.joinToString("\n"))
            .putStringSet("picked_set", pickedSet)
            .apply()
    }

    fun removeLocalPicked(username: String) {
        val clean = cleanPickedUsername(username)
        val updated = localPickedNames()
            .filterNot { it.equals(clean, ignoreCase = true) }
        val pickedSet = pickedPrefs.getStringSet("picked_set", emptySet()).orEmpty()
            .map(::cleanPickedUsername)
            .filter { it.isNotBlank() && !it.equals(clean, ignoreCase = true) }
            .toSet()
        pickedPrefs.edit()
            .putString("picked_list", updated.joinToString("\n"))
            .putStringSet("picked_set", pickedSet)
            .apply()
    }

    fun localPickedItems(): List<PickedUsernameDto> =
        localPickedNames().map { PickedUsernameDto(it, "") }

    fun mergedPickedResponse(remote: PagedPickedUsernamesDto?, page: Int, pageSize: Int): PagedPickedUsernamesDto {
        val remoteItems = remote?.items.orEmpty()
        val mergedItems = (localPickedItems() + remoteItems)
            .distinctBy { it.username.lowercase() }
        val total = max(remote?.totalCount ?: 0, mergedItems.size)
        return PagedPickedUsernamesDto(
            items = mergedItems.take(pageSize),
            totalCount = total,
            page = remote?.page ?: page,
            pageSize = remote?.pageSize ?: pageSize
        )
    }

    // Reload history when panel mounts, page changes, or a pick/delete happens
    LaunchedEffect(pickedPage, pickedRefreshKey) {
        isHistoryLoading = true
        val res = instagramWebClient.getPickedUsernames(deviceId, appId, pickedPage, 5)
        pickedResponse = mergedPickedResponse(res, pickedPage, 5)
        isHistoryLoading = false
    }

    // Helper to force a fresh reload from page 1
    fun reloadPickedHistory() {
        pickedPage = 1
        pickedRefreshKey++
    }

    fun showPickedImmediately(username: String) {
        val clean = cleanPickedUsername(username)
        if (clean.isBlank()) return

        saveLocalPicked(clean)
        val current = pickedResponse
        val existingItems = current?.items.orEmpty()
        val alreadyShown = existingItems.any { it.username.equals(clean, ignoreCase = true) }
        val mergedItems = listOf(PickedUsernameDto(clean, "")) +
            existingItems.filterNot { it.username.equals(clean, ignoreCase = true) }
        val pageSize = current?.pageSize ?: 5

        pickedPage = 1
        pickedResponse = PagedPickedUsernamesDto(
            items = mergedItems.take(pageSize),
            totalCount = if (alreadyShown) current?.totalCount ?: mergedItems.size else max((current?.totalCount ?: 0) + 1, mergedItems.size),
            page = 1,
            pageSize = pageSize
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        // Modern Pill Tab Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(if (selectedTab == 0) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { selectedTab = 0 },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Suggested",
                    color = if (selectedTab == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(if (selectedTab == 1) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { selectedTab = 1 },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Picked",
                    color = if (selectedTab == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (selectedTab == 0) {
            // Suggested Section
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Identity details row
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SUGGESTED USERNAME", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    generatedIdentity.username,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(6.dp))
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(generatedIdentity.username))
                                        Toast.makeText(context, "Copied username!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = "Copy username",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    generatedIdentity.fullName,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Spacer(Modifier.width(6.dp))
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(generatedIdentity.fullName))
                                        Toast.makeText(context, "Copied full name!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = "Copy full name",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                generatedIdentity = BrazilUsernameGenerator.generate()
                                apiResult = null
                            }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Generate new", tint = MaterialTheme.colorScheme.primary)
                            }
                            Button(
                                onClick = {
                                    if (applyingUsernameState != null) return@Button
                                    applyingUsernameState = generatedIdentity.username
                                    coroutineScope.launch {
                                        val success = instagramWebClient.pickUsername(generatedIdentity.username, deviceId, appId)
                                        applyingUsernameState = null
                                        if (success) {
                                            pickedUsernameState = generatedIdentity.username
                                            showPickedImmediately(generatedIdentity.username)
                                            selectedTab = 1
                                            reloadPickedHistory()
                                            onUsernamePicked(generatedIdentity.username)
                                        } else {
                                            Toast.makeText(context, "Failed to reserve username.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4CAF50),
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                if (applyingUsernameState == generatedIdentity.username) {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (pickedUsernameState == generatedIdentity.username) "Picked" else "Pick", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Check availability button
                Button(
                    onClick = {
                        isCheckingApi = true
                        apiResult = null
                        coroutineScope.launch {
                            apiResult = instagramWebClient.fetchUsernameSuggestions(generatedIdentity.username)
                            isCheckingApi = false
                        }
                    },
                    enabled = !isCheckingApi,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isCheckingApi) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onSecondaryContainer, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Searching...", fontSize = 13.sp)
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Search", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // API suggestions results
                apiResult?.let { result ->
                    if (result.error != null) {
                        Text("Status: ${result.error}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    } else if (result.suggestions.isNotEmpty()) {
                        Text(
                            "Available suggestions:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            result.suggestions.take(5).forEach { sugg ->
                                val isSuggPicked = pickedUsernameState == sugg
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(sugg, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    clipboardManager.setText(AnnotatedString(sugg))
                                                    Toast.makeText(context, "Copied '$sugg'!", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                                            }
                                            Button(
                                                onClick = {
                                                    if (applyingUsernameState != null) return@Button
                                                    applyingUsernameState = sugg
                                                    coroutineScope.launch {
                                                        val success = instagramWebClient.pickUsername(sugg, deviceId, appId)
                                                        applyingUsernameState = null
                                                        if (success) {
                                                            pickedUsernameState = sugg
                                                            showPickedImmediately(sugg)
                                                            selectedTab = 1
                                                            reloadPickedHistory()
                                                            onUsernamePicked(sugg)
                                                        } else {
                                                            Toast.makeText(context, "Failed to reserve username.", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                enabled = applyingUsernameState == null && !isSuggPicked,
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50), contentColor = Color.White),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                if (applyingUsernameState == sugg) {
                                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color.White, strokeWidth = 2.dp)
                                                } else {
                                                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(12.dp))
                                                    Spacer(Modifier.width(3.dp))
                                                    Text(if (isSuggPicked) "Picked" else "Pick", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            if (result.available) "This handle is available." else "Taken — choose another recommendation.",
                            fontSize = 12.sp,
                            color = if (result.available) Color(0xFF4EAE4E) else Color(0xFFFF5252),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else {
            // Picked History Section
            if (isHistoryLoading) {
                Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                val pagedData = pickedResponse
                if (pagedData == null || pagedData.items.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        Text("No picked usernames on this device yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pagedData.items.forEach { pickedItem ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        pickedItem.username,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(pickedItem.username))
                                                Toast.makeText(context, "Copied '${pickedItem.username}'!", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                        }
                                        Button(
                                            onClick = { onUsernamePicked(pickedItem.username) },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                            contentPadding = PaddingValues(horizontal = 8.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("Use", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                        IconButton(
                                            onClick = { userToDelete = pickedItem.username },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // Pagination controls
                        val totalPages = kotlin.math.max(1, kotlin.math.ceil(pagedData.totalCount.toDouble() / pagedData.pageSize).toInt())
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Text(
                                text = "Page $pickedPage of $totalPages (${pagedData.totalCount} total)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = { if (pickedPage > 1) pickedPage-- },
                                    enabled = pickedPage > 1,
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("Prev", fontSize = 12.sp)
                                }
                                TextButton(
                                    onClick = { if (pickedPage < totalPages) pickedPage++ },
                                    enabled = pickedPage < totalPages,
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("Next", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog for deletion
    userToDelete?.let { username ->
        AlertDialog(
            onDismissRequest = { userToDelete = null },
            title = { Text("Delete picked username?") },
            text = { Text("Are you sure you want to delete '$username' from this device's picked list?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        userToDelete = null
                        coroutineScope.launch {
                            val success = instagramWebClient.deletePickedUsername(username, deviceId, appId)
                            if (success) {
                                removeLocalPicked(username)
                                Toast.makeText(context, "Username deleted successfully", Toast.LENGTH_SHORT).show()
                                reloadPickedHistory()
                            } else {
                                Toast.makeText(context, "Failed to delete username.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { userToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
