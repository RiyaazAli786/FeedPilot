package com.feedpilot.client.data.repository

import com.feedpilot.client.common.Resource
import com.feedpilot.client.common.apiErrorMessage
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.dto.CoinTransferDto
import com.feedpilot.client.data.remote.dto.TransferCoinsRequest
import com.feedpilot.client.data.remote.dto.TransferCoinsResponse
import com.feedpilot.client.data.remote.dto.TransferSearchResultDto
import com.feedpilot.client.data.remote.dto.TransferSuggestionDto
import javax.inject.Inject
import javax.inject.Singleton

/** Sends coins from the signed-in user's own wallet to whoever owns a searched-for username. */
@Singleton
class CoinTransferRepository @Inject constructor(
    private val api: ApiService,
    private val sessionGate: AccountSessionGate
) {
    /**
     * Usernames matching a partial query, most recently active first — lets the sender pick a
     * recipient instead of having to know and type the whole handle. Empty (rather than an
     * error) on any failure since this only drives an optional dropdown; [search] is still what
     * actually resolves the final choice.
     */
    suspend fun suggest(query: String): List<TransferSuggestionDto> {
        if (query.isBlank() || !sessionGate.isSignedIn()) return emptyList()
        return try {
            api.suggestTransferUsernames(query.trim())
        } catch (t: Throwable) {
            emptyList()
        }
    }

    suspend fun search(username: String): Resource<TransferSearchResultDto> = try {
        if (!sessionGate.isSignedIn()) Resource.Error(NO_ACCOUNT)
        else Resource.Success(api.searchTransferUsername(username.trim()))
    } catch (t: Throwable) {
        Resource.Error(t.apiErrorMessage("No user found with that username"), t)
    }

    suspend fun transfer(receiverUsername: String, coins: Long, note: String?): Resource<TransferCoinsResponse> = try {
        if (!sessionGate.isSignedIn()) Resource.Error(NO_ACCOUNT)
        else Resource.Success(
            api.transferCoins(TransferCoinsRequest(receiverUsername.trim(), coins, note?.trim()?.ifBlank { null }))
        )
    } catch (t: Throwable) {
        Resource.Error(t.apiErrorMessage("Transfer failed"), t)
    }

    suspend fun history(): Resource<List<CoinTransferDto>> = try {
        if (!sessionGate.isSignedIn()) Resource.Success(emptyList())
        else Resource.Success(api.getTransferHistory())
    } catch (t: Throwable) {
        Resource.Error(t.apiErrorMessage("Could not load transfer history"), t)
    }

    private companion object {
        const val NO_ACCOUNT = "Link an Instagram account first."
    }
}
