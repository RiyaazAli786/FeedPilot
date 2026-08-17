package com.feedpilot.client.feature.accounts

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.common.InstagramCrypto
import com.feedpilot.client.common.WebViewNetworkLogging
import com.feedpilot.client.data.repository.AccountRepository
import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.ui.components.PurpleTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionViewerViewModel @Inject constructor(
    private val accountDao: AccountDao,
    private val accountRepository: AccountRepository
) : ViewModel() {
    private val _sessionCookies = MutableStateFlow<String?>(null)
    val sessionCookies: StateFlow<String?> = _sessionCookies.asStateFlow()

    private val _username = MutableStateFlow<String?>(null)
    val username: StateFlow<String?> = _username.asStateFlow()

    private val _notFound = MutableStateFlow(false)
    val notFound: StateFlow<Boolean> = _notFound.asStateFlow()

    fun load(accountId: String) {
        viewModelScope.launch {
            val account = accountDao.getById(accountId)
            if (account == null || account.sessionCookies.isBlank()) {
                _notFound.value = true
            } else {
                _username.value = account.username
                _sessionCookies.value = account.sessionCookies
            }
        }
    }

    fun saveFreshSession(accountId: String, sessionCookies: String) {
        viewModelScope.launch {
            accountRepository.saveFreshBrowserSession(accountId, sessionCookies)
        }
    }
}

/**
 * Opens a real WebView on instagram.com signed in as one linked account, by seeding its captured
 * session cookies into the WebView's own cookie jar before the first load. Lets the operator
 * eyeball an account the same way they'd check any other browser session — is it actually still
 * logged in, checkpointed, logged out — without needing the credentials again.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SessionViewerScreen(
    accountId: String,
    onBack: () -> Unit,
    viewModel: SessionViewerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }

    val cookies by viewModel.sessionCookies.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val notFound by viewModel.notFound.collectAsStateWithLifecycle()
    var capturedFreshSession by remember(accountId) { mutableStateOf(false) }

    LaunchedEffect(accountId) { viewModel.load(accountId) }

    val deviceFingerprint = remember { InstagramCrypto.getDeviceFingerprint(context) }

    Column(Modifier.fillMaxSize()) {
        PurpleTopBar(title = username?.let { "@$it" } ?: "Instagram session", onBack = onBack)

        if (isLoading && !notFound) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        when {
            notFound -> Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "No saved session for this account — re-add it to open a browser view.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            cookies == null -> Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .navigationBarsPadding(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            @Suppress("DEPRECATION")
                            databaseEnabled = true
                            useWideViewPort = false
                            loadWithOverviewMode = false
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = deviceFingerprint.userAgent
                        }

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        // Seed every captured cookie onto the domain Instagram's own pages read
                        // from before the first navigation, so the load itself lands signed in
                        // rather than on the logged-out login form.
                        cookies!!.split(";").forEach { pair ->
                            val trimmed = pair.trim()
                            if (trimmed.isNotEmpty()) {
                                cookieManager.setCookie("https://www.instagram.com", "$trimmed; Domain=.instagram.com; Path=/")
                            }
                        }
                        cookieManager.flush()

                        WebViewNetworkLogging.install(this)

                        webViewClient = object : WebViewClient() {
                            private fun getAllInstagramCookies(): Map<String, String> {
                                val cm = CookieManager.getInstance()
                                val map = mutableMapOf<String, String>()
                                listOf(
                                    "https://www.instagram.com",
                                    "https://instagram.com",
                                    "https://i.instagram.com",
                                    "https://accounts.instagram.com"
                                ).forEach { url ->
                                    cm.getCookie(url)?.split(";")?.forEach { pair ->
                                        val parts = pair.trim().split("=", limit = 2)
                                        if (parts.size == 2) map.putIfAbsent(parts[0].trim(), parts[1].trim())
                                    }
                                }
                                return map
                            }

                            private fun captureIfSignedIn() {
                                if (capturedFreshSession) return
                                val all = getAllInstagramCookies()
                                if (all["sessionid"].isNullOrBlank() || all["ds_user_id"].isNullOrBlank()) return
                                capturedFreshSession = true
                                CookieManager.getInstance().flush()
                                viewModel.saveFreshSession(
                                    accountId,
                                    all.entries.joinToString("; ") { "${it.key}=${it.value}" }
                                )
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                WebViewNetworkLogging.onPageStarted(view)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                view?.postDelayed({ captureIfSignedIn() }, 1_500)
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                view?.postDelayed({ captureIfSignedIn() }, 1_500)
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                return false
                            }
                        }

                        loadUrl("https://www.instagram.com/")
                    }
                }
            )
        }
    }
}
