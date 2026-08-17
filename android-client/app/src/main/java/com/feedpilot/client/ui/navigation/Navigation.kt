package com.feedpilot.client.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val SPLASH = "splash"
    const val START = "start"
    const val MORE = "more"
    const val FOLLOWERS = "followers"
    const val POSTS = "posts"
    const val HANDLES = "handles"
    const val ORDERS = "orders"
    const val TASKS = "tasks"

    const val LOGIN = "login"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val LEADERBOARD = "leaderboard"
    const val UPGRADE = "upgrade"
    const val ADD_ACCOUNT = "add_account"
    const val UPDATES = "updates"
    const val CONNECT_INSTAGRAM = "connect_instagram"
    const val VERIFY_CODE = "verify_code"
    const val ACCOUNT_LOGIN = "account_login"
    const val WEB_LOGIN = "web_login"
    const val WITHDRAW = "withdraw"
    const val TRANSFER = "transfer"
    const val REFERRAL = "referral"

    private const val SESSION_VIEWER_BASE = "session_viewer"
    const val SESSION_VIEWER = "$SESSION_VIEWER_BASE/{accountId}"
    fun sessionViewer(accountId: String) = "$SESSION_VIEWER_BASE/$accountId"
}

/** Bottom navigation tabs, left to right, matching the design. */
enum class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    Tasks(Routes.TASKS, "Tasks", Icons.AutoMirrored.Filled.Assignment),
    Followers(Routes.FOLLOWERS, "Followers", Icons.Filled.People),
    Posts(Routes.POSTS, "Posts", Icons.Filled.Favorite),
    Handles(Routes.HANDLES, "Handle", Icons.Filled.Visibility),
    Orders(Routes.ORDERS, "Orders", Icons.Filled.ShoppingCart)
}
