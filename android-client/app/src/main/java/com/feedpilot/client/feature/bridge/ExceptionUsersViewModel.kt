package com.feedpilot.client.feature.bridge

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.data.remote.ExceptionUsersApi
import com.feedpilot.client.data.remote.ExceptionUsername
import com.feedpilot.client.data.remote.ExceptionUserResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

@HiltViewModel
class ExceptionUsersViewModel @Inject constructor(
    private val api: ExceptionUsersApi,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExceptionUsersState(
        search = savedState["exceptionSearch"] ?: "",
        page = savedState["exceptionPage"] ?: 1
    ))
    val state = mutableState.asStateFlow()
    private var listJob: Job? = null

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
