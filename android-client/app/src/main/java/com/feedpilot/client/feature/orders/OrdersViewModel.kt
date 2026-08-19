package com.feedpilot.client.feature.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.data.local.OrderHistoryEntity
import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.repository.AppOrderRepository
import com.feedpilot.client.data.repository.OrderHistoryRepository
import com.feedpilot.client.data.repository.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OrderType(
    val title: String,
    val taskTypeBackend: String,
    val inputLabel: String,
    val inputPlaceholder: String,
    val isCommentType: Boolean = false
) {
    FOLLOWERS("Followers", "Follow", "Instagram Username", "@username or profile link"),
    LIKES("Likes", "Like", "Post or Reel Link", "https://www.instagram.com/p/C..."),
    COMMENTS("Comments", "Comment", "Post or Reel Link", "https://www.instagram.com/p/C...", isCommentType = true),
    REPOSTS("Reposts", "Repost", "Post or Reel Link", "https://www.instagram.com/p/C..."),
    SAVE_POSTS("Saves", "SavePost", "Post or Reel Link", "https://www.instagram.com/p/C..."),
    STORY_VIEWS("Story Views", "StoryView", "Instagram Story Link", "https://www.instagram.com/stories/...")
}

data class OrdersUiState(
    val coins: Long = 0,
    val orderType: OrderType = OrderType.FOLLOWERS,
    val targetInput: String = "",
    val quantityInput: String = "100",
    val commentsInput: String = "",
    val isSubmitting: Boolean = false,
    val orderPlacedSuccess: Boolean = false,
    /** Coin cost quoted by the backend for the current type and quantity. */
    val quotedCoins: Long? = null,
    val minQuantity: Int? = null,
    val maxQuantity: Int? = null,
    val message: String? = null
) {
    val parsedComments: List<String>
        get() = commentsInput.lines().map { it.trim() }.filter { it.isNotBlank() }

    val quantity: Int
        get() {
            if (orderType.isCommentType && parsedComments.isNotEmpty()) {
                return parsedComments.size
            }
            return quantityInput.filter { it.isDigit() }.toIntOrNull() ?: 0
        }

    val effectiveMin: Int get() = if (orderType.isCommentType && parsedComments.isNotEmpty()) 1 else (minQuantity ?: DEFAULT_MIN_QUANTITY)
    val effectiveMax: Int get() = maxQuantity ?: DEFAULT_MAX_QUANTITY

    val quantityError: String? get() = when {
        orderType.isCommentType && commentsInput.isNotBlank() && parsedComments.isEmpty() -> "Enter at least 1 comment line"
        quantityInput.isBlank() && !orderType.isCommentType -> null
        quantity < effectiveMin -> "Minimum quantity is $effectiveMin"
        quantity > effectiveMax -> "Maximum quantity is $effectiveMax"
        else -> null
    }

    val canAfford: Boolean get() = quotedCoins == null || coins >= quotedCoins

    val canPlace: Boolean get() = !isSubmitting &&
        targetInput.isNotBlank() &&
        quantity in effectiveMin..effectiveMax &&
        canAfford &&
        (!orderType.isCommentType || commentsInput.isBlank() || parsedComments.isNotEmpty())

    companion object {
        const val DEFAULT_MIN_QUANTITY = 100
        const val DEFAULT_MAX_QUANTITY = 100_000
    }
}

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    private val orderHistoryRepository: OrderHistoryRepository,
    private val appOrderRepository: AppOrderRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OrdersUiState())
    val uiState: StateFlow<OrdersUiState> = combine(_uiState, walletRepository.spendableWallet) { state, wallet ->
        state.copy(coins = wallet?.totalCoins ?: 0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OrdersUiState())

    init {
        viewModelScope.launch { walletRepository.refresh() }
        refreshQuote()
    }

    fun setOrderType(type: OrderType) {
        val defaultQty = if (type.isCommentType) "10" else "100"
        _uiState.update { it.copy(orderType = type, targetInput = "", quantityInput = defaultQty) }
        refreshQuote()
    }

    fun setTargetInput(input: String) {
        _uiState.update { it.copy(targetInput = input) }
    }

    fun setQuantityInput(input: String) {
        val filtered = input.filter { it.isDigit() }
        _uiState.update { it.copy(quantityInput = filtered) }
        refreshQuote()
    }

    fun setCommentsInput(input: String) {
        _uiState.update { state ->
            val newComments = input.lines().map { it.trim() }.filter { it.isNotBlank() }
            val newQty = if (newComments.isNotEmpty()) newComments.size.toString() else state.quantityInput
            state.copy(commentsInput = input, quantityInput = newQty)
        }
        refreshQuote()
    }

    fun setPresetQuantity(qty: Int) {
        _uiState.update { it.copy(quantityInput = qty.toString()) }
        refreshQuote()
    }

    private fun refreshQuote() {
        val curr = _uiState.value
        val qty = curr.quantity
        if (qty <= 0) {
            _uiState.update { it.copy(quotedCoins = null) }
            return
        }
        viewModelScope.launch {
            when (val res = appOrderRepository.quote(curr.orderType.taskTypeBackend, qty)) {
                is Resource.Success -> _uiState.update {
                    it.copy(
                        quotedCoins = res.data.coins,
                        minQuantity = res.data.minQuantity,
                        maxQuantity = res.data.maxQuantity
                    )
                }
                else -> _uiState.update { it.copy(quotedCoins = null) }
            }
        }
    }

    fun placeOrder() {
        val curr = _uiState.value
        val target = curr.targetInput.trim()
        val qty = curr.quantity

        if (target.isBlank()) {
            _uiState.update { it.copy(message = "Please enter target username or link") }
            return
        }

        if (qty < curr.effectiveMin || qty > curr.effectiveMax) {
            _uiState.update {
                it.copy(message = "Quantity must be between ${curr.effectiveMin} and ${curr.effectiveMax}.")
            }
            return
        }

        val commentsList = if (curr.orderType.isCommentType) curr.parsedComments.ifEmpty { null } else null

        _uiState.update {
            it.copy(isSubmitting = true, message = "Submitting ${curr.orderType.title} order ($qty)...")
        }

        viewModelScope.launch {
            val result = appOrderRepository.placeOrder(
                orderType = curr.orderType.taskTypeBackend,
                targetUrl = target,
                targetUsername = target,
                quantity = qty,
                comments = commentsList
            )

            val order = (result as? Resource.Success)?.data?.order
            val errMsg = (result as? Resource.Error)?.message

            orderHistoryRepository.logOrder(
                OrderHistoryEntity(
                    id = order?.let { "app_order_${it.id}" } ?: java.util.UUID.randomUUID().toString(),
                    smmOrderId = order?.id,
                    providerNickname = "FeedPilot Backend",
                    providerUrl = "api/orders",
                    targetUsername = target,
                    orderType = curr.orderType.title,
                    quantity = qty,
                    coinsSpent = order?.coinsSpent ?: 0L,
                    status = order?.status ?: "FAILED",
                    timestamp = System.currentTimeMillis(),
                    errorMessage = errMsg
                )
            )

            if (order != null) {
                walletRepository.refresh(forceServer = true)
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        targetInput = "",
                        commentsInput = "",
                        orderPlacedSuccess = true,
                        message = "Order Placed Successfully! (${order.coinsSpent} coins used)"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        message = errMsg ?: "Could not place the order"
                    )
                }
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun consumeOrderPlacedSuccess() {
        _uiState.update { it.copy(orderPlacedSuccess = false) }
    }
}
