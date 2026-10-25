package com.feedpilot.client.feature.bridge

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.bridge.toBridgeResultData
import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.local.AccountEntity
import com.feedpilot.client.data.remote.ExceptionUsersApi
import com.feedpilot.client.data.remote.ExceptionUsername
import com.feedpilot.client.data.remote.ExceptionUserResult
import com.feedpilot.client.data.remote.dto.ExceptionUserFetchResultDto
import com.feedpilot.client.data.repository.TargetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.util.Locale

data class ExceptionUsersState(
    val search: String = "",
    val users: List<String> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pages: Int = 1,
    val loading: Boolean = true,
    val error: String? = null,
    val saving: Boolean = false,
    val actionError: String? = null,
    val notice: String? = null
)

data class FetchDialogState(
    val target: String,
    val accountUsername: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val formattedResponse: String? = null,
    val summary: String? = null
)

@HiltViewModel
class ExceptionUsersViewModel @Inject constructor(
    private val api: ExceptionUsersApi,
    private val savedState: SavedStateHandle,
    private val accountDao: AccountDao,
    private val targetRepository: TargetRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExceptionUsersState(
        search = savedState["exceptionSearch"] ?: "",
        page = savedState["exceptionPage"] ?: 1
    ))
    val state = mutableState.asStateFlow()
    private var listJob: Job? = null

    private val _fetchDialogState = MutableStateFlow<FetchDialogState?>(null)
    val fetchDialogState = _fetchDialogState.asStateFlow()
    private var fetchJob: Job? = null

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    init { refresh() }

    fun search(value: String) {
        if (!state.value.saving) load(value, 1, debounce = true)
    }

    fun page(value: Int) {
        if (!state.value.loading && !state.value.saving)
            load(state.value.search, value.coerceIn(1, state.value.pages))
    }

    fun refresh() { load(state.value.search, state.value.page) }

    private fun load(search: String, page: Int, debounce: Boolean = false) {
        listJob?.cancel()
        savedState["exceptionSearch"] = search
        savedState["exceptionPage"] = page
        mutableState.update { it.copy(search = search, page = page, loading = true, error = null, users = emptyList()) }
        listJob = viewModelScope.launch {
            try {
                if (debounce) delay(300)
                val result = api.list(search.trim().removePrefix("@"), page)
                check(result.success) { "The service could not load exception users." }
                savedState["exceptionPage"] = result.page
                mutableState.update { it.copy(users = result.users, total = result.total,
                    page = result.page, pages = result.pages.coerceAtLeast(1), loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(loading = false, error = message(error)) }
            }
        }
    }

    fun clearActionError() { mutableState.update { it.copy(actionError = null) } }

    fun save(original: String?, value: String, onSuccess: () -> Unit) {
        val username = value.trim().removePrefix("@").lowercase(Locale.ROOT)
        if (!Regex("[a-z0-9._]{1,30}").matches(username) || username.all { it.isDigit() }) {
            mutableState.update { it.copy(actionError = "Enter a valid Instagram username, not a numeric ID or URL.") }
            return
        }
        mutate(onSuccess, if (original == null) "Exception user saved." else "Exception user updated.") {
            if (original == null) api.add(ExceptionUsername(username))
            else api.update(original, ExceptionUsername(username))
        }
    }

    fun remove(username: String, onSuccess: () -> Unit) {
        mutate(onSuccess, "Exception user removed.") { api.remove(username) }
    }

    private fun mutate(onSuccess: () -> Unit, notice: String, action: suspend () -> ExceptionUserResult) {
        if (state.value.saving) return
        mutableState.update { it.copy(saving = true, actionError = null, notice = null) }
        viewModelScope.launch {
            try {
                check(action().success) { "The service could not save the change." }
                mutableState.update { it.copy(saving = false, notice = notice) }
                onSuccess()
                // The server clamps pages after removing the final user on a page.
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(saving = false, actionError = message(error)) }
            }
        }
    }

    fun fetchUser(username: String) {
        val cleanUsername = username.trim().removePrefix("@")
        fetchJob?.cancel()
        _fetchDialogState.value = FetchDialogState(
            target = cleanUsername,
            loading = true
        )
        fetchJob = viewModelScope.launch {
            try {
                val account = selectRandomActiveAccount()
                if (account == null) {
                    _fetchDialogState.update {
                        it?.copy(
                            loading = false,
                            error = "No active Instagram account with a saved session was found on this device."
                        )
                    }
                    return@launch
                }

                _fetchDialogState.update { it?.copy(accountUsername = account.username) }

                val cookies = if (account.sessionCookies.isNotBlank()) {
                    account.sessionCookies
                } else {
                    account.lastLogin.orEmpty()
                }

                when (val result = targetRepository.fetchUserFeed(cleanUsername, customCookies = cookies)) {
                    is Resource.Success -> {
                        val feed = result.data
                        val bridgeData = feed.toBridgeResultData(feed.items.size)
                        val dto = ExceptionUserFetchResultDto(
                            target = cleanUsername,
                            fetchedByAccount = account.username,
                            status = "SUCCESS",
                            postsCount = bridgeData.items.size,
                            profile = bridgeData.profile,
                            items = bridgeData.items,
                            maxId = bridgeData.maxId,
                            hasMore = bridgeData.hasMore
                        )
                        val formatted = prettyJson.encodeToString(dto)
                        val summary = buildString {
                            append("Posts: ${bridgeData.items.size}")
                            bridgeData.profile?.let { p ->
                                append(" | Followers: ${p.followerCount} | Following: ${p.followingCount}")
                                if (p.isPrivate) append(" (Private)")
                            }
                        }
                        _fetchDialogState.update {
                            it?.copy(
                                loading = false,
                                formattedResponse = formatted,
                                summary = summary
                            )
                        }
                    }
                    is Resource.Error -> {
                        _fetchDialogState.update {
                            it?.copy(
                                loading = false,
                                error = result.message.ifBlank { "Failed to fetch user feed for @$cleanUsername" }
                            )
                        }
                    }
                    is Resource.Loading -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _fetchDialogState.update {
                    it?.copy(
                        loading = false,
                        error = e.message ?: "An unexpected error occurred while fetching."
                    )
                }
            }
        }
    }

    fun dismissFetchDialog() {
        fetchJob?.cancel()
        fetchJob = null
        _fetchDialogState.value = null
    }

    private suspend fun selectRandomActiveAccount(): AccountEntity? {
        val all = accountDao.getAll()
        val activeWithCookies = all.filter { account ->
            val cookies = account.sessionCookies.takeIf { it.isNotBlank() }
                ?: account.lastLogin?.takeIf { it.contains("sessionid") }
            !cookies.isNullOrBlank() && (account.isLoggedIn || account.status.equals("Active", ignoreCase = true) || account.status.isBlank())
        }
        if (activeWithCookies.isNotEmpty()) {
            return activeWithCookies.random()
        }
        val anyWithCookies = all.filter { account ->
            val cookies = account.sessionCookies.takeIf { it.isNotBlank() }
                ?: account.lastLogin?.takeIf { it.contains("sessionid") }
            !cookies.isNullOrBlank()
        }
        return anyWithCookies.randomOrNull()
    }

    private fun message(error: Exception): String {
        if (error is HttpException) {
            val detail = runCatching {
                Json.parseToJsonElement(error.response()?.errorBody()?.string().orEmpty())
                    .jsonObject["error"]?.jsonPrimitive?.content
            }.getOrNull()
            return detail?.take(200) ?: "Exception-user service returned HTTP ${error.code()}."
        }
        return "Could not reach the exception-user service. Check your connection and retry."
    }
}
