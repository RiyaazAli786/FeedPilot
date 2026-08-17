package com.feedpilot.client.feature.followers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.local.OrderHistoryEntity
import com.feedpilot.client.data.repository.AppOrderRepository
import com.feedpilot.client.data.repository.AuthRepository
import com.feedpilot.client.data.repository.OrderHistoryRepository
import com.feedpilot.client.data.repository.SettingsRepository
import com.feedpilot.client.data.repository.TargetRepository
import com.feedpilot.client.data.repository.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FollowerPackage(val followers: Int, val coins: Long)

data class TargetSummary(val username: String, val avatarUrl: String?, val followers: Long)

data class FollowersUiState(
    val coins: Long = 0,
    val diamonds: Long = 0,
    val targetUsername: String? = null,
    val targetSummary: TargetSummary? = null,
    val loadingTarget: Boolean = false,
    val packages: List<FollowerPackage> = DEFAULT_PACKAGES,
    val message: String? = null
) {
    companion object {
        val DEFAULT_PACKAGES = listOf(100, 200, 400, 500, 1000, 2000, 5000, 10000)
            .map { FollowerPackage(it, it * 8L) }
    }
}

@HiltViewModel
class FollowersViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    private val targetRepository: TargetRepository,
    private val authRepository: AuthRepository,
    private val orderHistoryRepository: OrderHistoryRepository,
    private val appOrderRepository: AppOrderRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private data class Content(
        val targetUsername: String? = null,
        val targetSummary: TargetSummary? = null,
        val loadingTarget: Boolean = false,
        val message: String? = null
    )

    private val content = MutableStateFlow(Content())

    val state: StateFlow<FollowersUiState> =
        combine(walletRepository.wallet, settingsRepository.settings, content) { wallet, settings, c ->
            val followerCounts = listOf(100, 200, 400, 500, 1000, 2000, 5000, 10000)
            val packages = followerCounts.map { count ->
                FollowerPackage(count, count * settings.pricePerFollow.toLong())
            }
            FollowersUiState(
                coins = wallet?.totalCoins ?: 0,
                targetUsername = c.targetUsername,
                targetSummary = c.targetSummary,
                loadingTarget = c.loadingTarget,
                packages = packages,
                message = c.message
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FollowersUiState())

    init { viewModelScope.launch { walletRepository.refresh() } }

    fun setTarget(username: String) {
        val clean = username.trim().removePrefix("@")
        if (clean.isBlank()) {
            content.update { it.copy(message = "Enter a valid username") }
            return
        }
        if (!authRepository.isLoggedIn.value) {
            content.update { it.copy(targetUsername = clean, targetSummary = null, message = "Log in to load @$clean") }
            return
        }
        content.update { it.copy(targetUsername = clean, loadingTarget = true, message = null) }
        viewModelScope.launch {
            when (val r = targetRepository.fetchProfile(clean)) {
                is Resource.Success -> content.update {
                    it.copy(loadingTarget = false, targetSummary = TargetSummary(r.data.username, r.data.avatarUrl, r.data.followers))
                }
                is Resource.Error -> content.update { it.copy(loadingTarget = false, targetSummary = null, message = r.message) }
                Resource.Loading -> Unit
            }
        }
    }

    /**
     * Submits a follower order to the FeedPilot backend, which prices it, debits coins and
     * fulfils it from the admin dashboard. The app no longer calls an SMM panel directly.
     */
    fun order(pkg: FollowerPackage) {
        val target = content.value.targetUsername
        if (target.isNullOrBlank()) {
            content.update { it.copy(message = "Select a target first") }
            return
        }
        // A local pre-check keeps the obvious case snappy; the backend re-checks and is
        // the authority, since only it can see the true balance.
        if (state.value.coins < pkg.coins) {
            content.update { it.copy(message = "Not enough coins for ${pkg.followers} followers") }
            return
        }

        content.update { it.copy(message = "Submitting order for ${pkg.followers} followers...") }

        viewModelScope.launch {
            // The exact count fetched for the target profile (shown in the TargetCard) is the
            // baseline progress on this order is measured against — without it every order
            // starts from an implicit 0, which reads as "this order delivered nothing" no
            // matter how many followers actually landed.
            val startCount = content.value.targetSummary
                ?.takeIf { it.username.equals(target, ignoreCase = true) }
                ?.followers?.toInt()

            val result = appOrderRepository.placeOrder(
                orderType = ORDER_TYPE_FOLLOW,
                targetUrl = target,
                targetUsername = target,
                quantity = pkg.followers,
                startCount = startCount
            )

            val order = (result as? Resource.Success)?.data?.order
            val errMsg = (result as? Resource.Error)?.message

            orderHistoryRepository.logOrder(
                OrderHistoryEntity(
                    smmOrderId = order?.id,
                    providerNickname = "FeedPilot Backend",
                    providerUrl = "api/orders",
                    targetUsername = target,
                    orderType = "Followers",
                    quantity = pkg.followers,
                    coinsSpent = order?.coinsSpent ?: 0L,
                    status = order?.status ?: "FAILED",
                    timestamp = System.currentTimeMillis(),
                    errorMessage = errMsg
                )
            )

            // The backend is the source of truth for the balance after a debit.
            walletRepository.refresh(forceServer = true)

            content.update {
                it.copy(
                    message = if (order != null) {
                        "Order placed — ${pkg.followers} followers for @$target " +
                            "(${order.coinsSpent} coins). Status: ${order.status}."
                    } else {
                        errMsg ?: "Could not place the order"
                    }
                )
            }
        }
    }

    fun consumeMessage() { content.update { it.copy(message = null) } }

    private companion object {
        /** Matches the backend's TaskType enum, which travels as its name. */
        const val ORDER_TYPE_FOLLOW = "Follow"
    }
}
