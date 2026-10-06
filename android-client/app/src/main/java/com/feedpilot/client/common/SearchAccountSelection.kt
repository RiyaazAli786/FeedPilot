package com.feedpilot.client.common

import com.feedpilot.client.data.local.AccountEntity
import com.feedpilot.client.data.repository.AccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SearchAccountOption(val id: String, val username: String)
data class SearchAccountState(
    val accounts: List<SearchAccountOption> = emptyList(),
    val selectedAccountId: String? = null
)

/** Screen-local selection: searching never switches the runner's active account. */
class SearchAccountSelection(accountFlow: Flow<List<AccountEntity>>, scope: CoroutineScope) {
    constructor(repository: AccountRepository, scope: CoroutineScope) : this(repository.accounts, scope)

    private val accounts = accountFlow.stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val selectedId = MutableStateFlow<String?>(null)

    init {
        scope.launch {
            accounts.collect { list ->
                if (selectedId.value == null) {
                    // Pin the initial choice so background active-account changes do not
                    // silently switch the session used for pagination.
                    selectedId.value = resolve(list.filter { it.sessionCookies.isNotBlank() }, null)?.id
                }
            }
        }
    }

    val state = combine(accounts, selectedId) { list, id ->
        val available = list.filter { it.sessionCookies.isNotBlank() }
        SearchAccountState(
            available.map { SearchAccountOption(it.id, it.username) },
            resolve(available, id)?.id
        )
    }.stateIn(scope, SharingStarted.Eagerly, SearchAccountState())

    fun select(id: String) { selectedId.value = id }

    fun cookies(): String {
        val available = accounts.value.filter { it.sessionCookies.isNotBlank() }
        return resolve(available, selectedId.value)?.sessionCookies
            ?: error("Select an Instagram account with a saved session to search.")
    }

    private fun resolve(list: List<AccountEntity>, id: String?): AccountEntity? =
        if (id != null) list.firstOrNull { it.id == id }
        else list.firstOrNull { it.isLoggedIn } ?: list.firstOrNull()
}
