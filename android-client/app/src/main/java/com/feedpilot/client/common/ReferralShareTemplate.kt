package com.feedpilot.client.common

object ReferralShareTemplate {

    const val DEFAULT_APK_URL = "https://feedpilot-api-ount.onrender.com/api/apk/latest"

    fun buildShareText(
        referralCode: String = "",
        bonusCoins: Int = 100,
        apkUrl: String? = null
    ): String {
        val codeText = referralCode.trim().ifBlank { "FP-2227D9" }
        val finalApkUrl = apkUrl?.ifBlank { null } ?: DEFAULT_APK_URL

        return """
FeedPilot - Smart Instagram Growth & Rewards Platform

Elevate your Instagram presence with fast engagement and instant coin rewards.

Why FeedPilot?
- Instant Coin Rewards: Complete tasks and earn real coin rewards.
- Organic Growth: Gain followers, likes, comments, saves, reposts, and story views.
- Intelligent Pacing: Multi-account safety and in-app random activity controls.
- Upgraded Accounts: Earn double coins on upgraded accounts.

EXCLUSIVE SIGN-UP BONUS
Claim $bonusCoins free bonus coins instantly on sign-up using my code.

Referral Code: $codeText

Download the official FeedPilot APK:
$finalApkUrl

1. Install the app.
2. Enter code $codeText on the Refer & Earn screen.
3. Claim $bonusCoins bonus coins instantly and start growing.
        """.trimIndent()
    }
}
