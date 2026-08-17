package com.feedpilot.client.feature.connect

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.feedpilot.client.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class ConnectUiState(
    val launching: Boolean = false,
    val connectedUsername: String? = null,
    val error: String? = null
)

/**
 * Builds the official Instagram OAuth authorization URL. The user signs in and approves scopes on
 * Instagram's own page (opened in a Custom Tab), so this app never sees their password. Instagram
 * redirects back to [BuildConfig.INSTAGRAM_REDIRECT_URI] with a short-lived `code` that the backend
 * exchanges for an access token.
 */
@HiltViewModel
class ConnectInstagramViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state

    /** The authorize endpoint to open. Returns null (and sets an error) if the app id isn't set. */
    fun authorizeUrl(): String? {
        if (BuildConfig.INSTAGRAM_CLIENT_ID.startsWith("YOUR_")) {
            _state.update { it.copy(error = "Instagram app id isn't configured yet.") }
            return null
        }
        _state.update { it.copy(launching = true, error = null) }
        return Uri.parse("https://api.instagram.com/oauth/authorize").buildUpon()
            .appendQueryParameter("client_id", BuildConfig.INSTAGRAM_CLIENT_ID)
            .appendQueryParameter("redirect_uri", BuildConfig.INSTAGRAM_REDIRECT_URI)
            .appendQueryParameter("scope", BuildConfig.INSTAGRAM_SCOPES)
            .appendQueryParameter("response_type", "code")
            .build()
            .toString()
    }

    fun onReturnedFromBrowser() = _state.update { it.copy(launching = false) }

    /** Called once the backend confirms the token exchange for a redirect `code`. */
    fun onConnected(username: String) =
        _state.update { it.copy(launching = false, connectedUsername = username, error = null) }

    fun onError(message: String) =
        _state.update { it.copy(launching = false, error = message) }
}
