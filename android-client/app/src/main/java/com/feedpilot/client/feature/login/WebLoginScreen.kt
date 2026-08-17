package com.feedpilot.client.feature.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import com.feedpilot.client.common.BrazilUsernameGenerator
import com.feedpilot.client.common.DeviceIdentity
import com.feedpilot.client.common.InstagramCrypto
import com.feedpilot.client.common.WebViewNetworkLogging
import com.feedpilot.client.data.remote.InstagramWebClient
import com.feedpilot.client.data.remote.UsernameValidationResult
import com.feedpilot.client.ui.components.CircleIconButton
import com.feedpilot.client.ui.components.LoginColors
import com.feedpilot.client.ui.components.PurpleTopBar
import com.feedpilot.client.ui.theme.AppTheme
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

private const val LOGIN_URL = "https://www.instagram.com/accounts/login/"

/** How many times to re-wipe the browser session before telling the user it would not clear. */
private const val MAX_RESET_ATTEMPTS = 2

/**
 * How long to wait, after the home feed's first settle signal, before taking the final cookie
 * snapshot. Covers the gap between the SPA's client-side route change (near-instant) and its
 * background requests actually finishing and rotating cookies (see the comment at the call site),
 * plus the extra round trip through the "Save Your Login Info?" (One Tap) interstitial that
 * Instagram's own post-login redirect can now show before landing on the real home feed — see
 * [isHomeFeedUrl]'s call site.
 */
private const val HOME_FEED_SETTLE_DELAY_MS = 5_000L

/**
 * Tracks how the current login attempt started. Lives outside the [WebViewClient] so the
 * "Try again" action can put it back to its initial state along with the browser session.
 */
private class ResetState {
    /** True once the cookie jar has been seen empty, i.e. the wipe took and the form is up. */
    var startedLoggedOut = false
    var attempts = 0
    /** True once the post-login redirect to the real home feed has been kicked off. */
    var awaitingHomeLoad = false
    /** True once the delayed final capture after the home feed load has been scheduled. */
    var captureScheduled = false

    fun clear() {
        startedLoggedOut = false
        attempts = 0
        awaitingHomeLoad = false
        captureScheduled = false
    }
}

/**
 * Wipes everything a previous Instagram login could be restored from, then runs [onDone].
 *
 * The flush is the part that matters: `removeAllCookies` drops the in-memory jar but leaves the
 * on-disk copy until it is flushed, so the old session came back on the next start and the login
 * page opened already authenticated — no form, and the stale session captured on the first
 * callback. DOM storage and the cache go too, since Instagram restores state from both.
 */
private fun resetBrowserSession(webView: WebView, onDone: () -> Unit) {
    val cookieManager = CookieManager.getInstance()
    WebStorage.getInstance().deleteAllData()
    webView.clearCache(true)
    webView.clearFormData()
    webView.clearHistory()

    val finished = AtomicBoolean(false)
    fun finish() {
        cookieManager.flush()
        if (finished.compareAndSet(false, true)) onDone()
    }

    cookieManager.removeAllCookies { finish() }
    // Some WebView builds never deliver that callback. Without a backstop the load it gates
    // would never be issued and the screen would sit on an empty page.
    webView.postDelayed(::finish, 1_200)
}

/**
 * Loads [url] only once this WebView actually has a height.
 *
 * The initial load is kicked off from inside the Compose `AndroidView` factory, before Compose has
 * measured the view — so its height is still 0. Instagram's login page sizes itself to the viewport
 * (100vh / 100% chains), and a load issued at 0 height lays the whole page out at zero height and
 * paints nothing, even though the form is present in the DOM. Waiting for the first non-zero layout
 * makes Instagram lay out against the real viewport and render normally.
 */
private fun WebView.loadUrlWhenSized(url: String) {
    // Already measured — load now; otherwise wait for the first layout, which is when Compose
    // gives the view its real height.
    if (height > 0) loadUrl(url) else doOnLayout { loadUrl(url) }
}

/**
 * Types [username] into Instagram's login form. The field is a React-controlled input, so a bare
 * `el.value = "..."` assignment (fine for the read-only scraping in [VIEWER_USERNAME_JS]) would
 * not update React's internal state or enable the "Log in" button — this goes through the native
 * value setter and dispatches a bubbling `input` event, which is what React actually listens for.
 */
private fun WebView.fillUsernameFieldAttempt(username: String, onResult: (Boolean) -> Unit) {
    val js = """
        (function(){
          try {
            var el = document.querySelector('input[name="username"]');
            if (!el) return false;
            el.focus();
            var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
            setter.call(el, ${JSONObject.quote(username)});
            el.dispatchEvent(new Event('input', { bubbles: true }));
            return true;
          } catch (e) { return false; }
        })();
    """.trimIndent()
    evaluateJavascript(js) { result -> onResult(result == "true") }
}

/**
 * Retries [fillUsernameFieldAttempt] until it succeeds or the attempts run out.
 *
 * `onPageFinished`/`isLoading = false` only mark the initial document load — Instagram's login
 * form is hydrated into the DOM by React afterward, so a single attempt fired right when the
 * suggestion sheet's "Pick" button is tapped can run before `input[name="username"]` exists yet,
 * silently doing nothing. Polling covers that gap the same way [AccountLoginScreen] avoids it
 * entirely by writing straight into Compose state instead of a WebView's DOM.
 */
private suspend fun WebView.fillUsernameField(
    username: String,
    attempts: Int = 15,
    intervalMs: Long = 300
): Boolean {
    repeat(attempts) { attempt ->
        val filled = suspendCancellableCoroutine<Boolean> { cont ->
            fillUsernameFieldAttempt(username) { success -> cont.resume(success) }
        }
        if (filled) return true
        if (attempt < attempts - 1) delay(intervalMs)
    }
    return false
}

/**
 * Reads the logged-in handle straight off the page. The cookie jar only carries the numeric
 * `ds_user_id`, and Instagram does not always expose `_sharedData`, so a blank result is normal —
 * the caller resolves the handle over the API in that case.
 */
private const val VIEWER_USERNAME_JS =
    "(function(){try{" +
        "if(window._sharedData&&window._sharedData.config&&window._sharedData.config.viewer&&window._sharedData.config.viewer.username){" +
            "var u=window._sharedData.config.viewer.username;if(u&&!/^\\d+$/.test(u)&&u!=='null')return u;" +
        "}" +
        "var scripts=document.querySelectorAll('script');" +
        "for(var i=0;i<scripts.length;i++){" +
            "var txt=scripts[i].textContent||'';" +
            "if(txt.indexOf('username')!==-1){" +
                "var m=txt.match(/\"username\"\\s*:\\s*\"([A-Za-z0-9._]+)\"/);" +
                "if(m&&m[1]&&!/^\\d+$/.test(m[1])&&m[1]!=='null')return m[1];" +
            "}" +
        "}" +
        "var reserved=['explore','direct','reels','stories','accounts','p','tv','reel','legal','about'];" +
        "var navLinks=document.querySelectorAll('a[href^=\"/\"]');" +
        "for(var j=0;j<navLinks.length;j++){" +
            "var href=navLinks[j].getAttribute('href')||'';" +
            "var m2=href.match(/^\\/([A-Za-z0-9._]+)\\/?$/);" +
            "if(m2&&m2[1]&&!/^\\d+$/.test(m2[1])&&m2[1]!=='null'&&reserved.indexOf(m2[1].toLowerCase())===-1){" +
                "return m2[1];" +
            "}" +
        "}" +
        "var roleLinks=document.querySelectorAll('a[role=\"link\"]');" +
        "for(var k=0;k<roleLinks.length;k++){" +
            "var h=roleLinks[k].href||roleLinks[k].getAttribute('href')||'';" +
            "if(h){" +
                "var m3=h.match(/(?:https?:\\/\\/(?:www\\.)?instagram\\.com)?\\/([A-Za-z0-9._]+)\\/?$/i);" +
                "if(m3&&m3[1]&&!/^\\d+$/.test(m3[1])&&m3[1]!=='null'&&reserved.indexOf(m3[1].toLowerCase())===-1){" +
                    "return m3[1];" +
                "}" +
            "}" +
        "}" +
        "if(roleLinks&&roleLinks.length>7&&roleLinks[7]){" +
            "var h7=roleLinks[7].href||roleLinks[7].getAttribute('href')||'';" +
            "if(h7)return h7;" +
        "}" +
    "}catch(e){}return '';})()"

/**
 * Logs an Instagram account in through a real WebView, then hands the resulting session cookies
 * back to the caller.
 *
 * @param onSessionCaptured receives the handle (or the numeric user id when the page does not
 *   expose one) and the full cookie header. Fires exactly once per successful login.
 * @param errorMessage set by the caller when storing the captured session failed, so the user is
 *   told instead of being left on the progress overlay forever.
 */
// Instagram's login page is a JavaScript app; the WebView cannot render or authenticate without
// scripting, so JavaScript is required here by design.
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(
    onBack: () -> Unit,
    onSessionCaptured: (username: String, sessionCookies: String) -> Unit,
    errorMessage: String? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var captured by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showSuggestionPanel by remember { mutableStateOf(false) }
    val reset = remember { ResetState() }

    // The client below is built once inside the AndroidView factory, so it would otherwise close
    // over the callback from the first composition and keep calling a stale one.
    val onCaptured by rememberUpdatedState(onSessionCaptured)

    val deviceFingerprint = remember { InstagramCrypto.getDeviceFingerprint(context) }
    val instagramWebClient = remember(context) { InstagramWebClient(okhttp3.OkHttpClient(), context) }

    // Last resort: the session reset below issues the initial load itself, with its own backstop
    // for WebView builds that never deliver the cookie-clear callback. If even that produced no
    // navigation, load here rather than leaving the user on a blank page.
    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        delay(1_500)
        if (view.url == null && loadError == null) view.loadUrlWhenSized(LOGIN_URL)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LoginColors.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            PurpleTopBar(
                title = "Log in via Web",
                onBack = onBack,
                trailingContent = {
                    CircleIconButton(onClick = { showSuggestionPanel = true }) {
                        Icon(
                            Icons.Filled.Badge,
                            contentDescription = "Pick a suggested username",
                            tint = AppTheme.brand.headerContentColor
                        )
                    }
                }
            )

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF4CB5F9),
                    trackColor = LoginColors.border
                )
            }

            AnimatedVisibility(visible = showSuggestionPanel) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Pick a Username",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(
                            onClick = { showSuggestionPanel = false },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Close Panel",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    UsernameSuggestionPanel(
                        instagramWebClient = instagramWebClient,
                        onUsernamePicked = { picked ->
                            coroutineScope.launch {
                                val identity = com.feedpilot.client.common.DeviceIdentity(context)
                                instagramWebClient.pickUsername(picked, identity.hardwareDeviceId, identity.appId)
                                webView?.fillUsernameField(picked)
                            }
                        }
                    )
                }
            }

            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webView = this
                        // Fill the AndroidView slot with a definite size. Without explicit
                        // match-parent params the WebView can measure itself at zero height, and
                        // Instagram's login page — laid out against the viewport with 100vh/100%
                        // rules — then collapses to a blank page even though its form is in the DOM.
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

                        WebViewNetworkLogging.install(this)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                WebViewNetworkLogging.onPageStarted(view)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                onPageSettled(view)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                // Keep every navigation inside this WebView; logging in bounces
                                // through a few Instagram URLs before the session cookie is set.
                                return false
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                // Instagram finishes login by replacing history rather than loading
                                // a fresh page, so onPageFinished alone can miss the moment.
                                onPageSettled(view)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                // Sub-resource failures are routine and must not blank the page —
                                // only report a failure of the document itself.
                                if (request?.isForMainFrame == true) {
                                    isLoading = false
                                    loadError = "Could not load Instagram. Check your connection and try again."
                                }
                            }

                            /**
                             * www.instagram.com is where this whole login happens and is queried
                             * first; every Instagram auth cookie is domain-wide (`Domain=
                             * .instagram.com`) so it alone normally already carries everything.
                             * The other hosts are only consulted to fill in a name this device
                             * genuinely never saw from www — first-writer-wins, deliberately,
                             * rather than the old last-write-wins merge. That order let
                             * i.instagram.com or accounts.instagram.com — hosts this WebView never
                             * actually navigated to, queried only because CookieManager answers
                             * for any domain-matching URL regardless of visit history — silently
                             * overwrite a correct www-scoped value with a blank or stale one for
                             * the same cookie name, which is exactly the kind of corruption that
                             * made a freshly captured session read as already logged out.
                             */
                            private fun getAllInstagramCookies(): Map<String, String> {
                                val cm = CookieManager.getInstance()
                                val map = mutableMapOf<String, String>()
                                listOf(
                                    "https://www.instagram.com",
                                    "https://instagram.com",
                                    "https://i.instagram.com",
                                    "https://accounts.instagram.com"
                                ).forEach { url ->
                                    cm.getCookie(url)?.let { cookieStr ->
                                        parseCookieString(cookieStr).forEach { (k, v) ->
                                            map.putIfAbsent(k, v)
                                        }
                                    }
                                }
                                return map
                            }

                            /**
                             * Decides what the page that just settled means: the login form we
                             * are waiting on, a login that has just completed, or a session that
                             * outlived the reset and has to be cleared before anything else.
                             */
                            private fun onPageSettled(view: WebView?) {
                                if (captured) return

                                val cookies = getAllInstagramCookies()

                                // ds_user_id is only issued once Instagram has authenticated the
                                // user, so requiring it — not just a sessionid, which exists for
                                // logged-out visitors too — is what makes this "logged in".
                                val userId = cookies["ds_user_id"]?.takeIf { it.isNotBlank() }
                                val signedIn = userId != null && !cookies["sessionid"].isNullOrBlank()

                                if (!reset.startedLoggedOut) {
                                    // The jar is empty, so the login form is up and this screen
                                    // now waits for the user. Everything after this point is a
                                    // login they performed here.
                                    if (!signedIn) {
                                        reset.startedLoggedOut = true
                                        return
                                    }

                                    // Instagram answered as an already-signed-in user: the reset
                                    // did not take, so there is no form to fill in. Wipe harder
                                    // and reload instead of silently linking whoever was signed
                                    // in last — that link is what looked like an account logging
                                    // itself in from the login screen.
                                    if (view != null && reset.attempts < MAX_RESET_ATTEMPTS) {
                                        reset.attempts++
                                        isLoading = true
                                        resetBrowserSession(view) { view.loadUrl(LOGIN_URL) }
                                    } else {
                                        isLoading = false
                                        loadError = "Instagram is still signed in from an earlier " +
                                            "session on this device. Tap Try again to reset it."
                                    }
                                    return
                                }

                                // A session that appeared after the form was up is a login the
                                // user just performed here, so it is safe to hand back.
                                if (!signedIn) return

                                // The login POST/redirect only issues ds_user_id + sessionid.
                                // Cookies the write APIs actually check — rur (used to derive the
                                // actor id), ig_did, a rotated csrftoken — are only set once the
                                // real home feed itself loads, so capture cannot happen right
                                // here. This used to force a navigation straight to
                                // https://www.instagram.com/, which also skipped past whatever
                                // interstitial Instagram itself wanted to show first — most
                                // commonly "Save Your Login Info?" (One Tap). A real browser never
                                // skips that, so forcing past it was as much an automation tell as
                                // it was a correctness risk. Instead, just let Instagram's own
                                // redirect chain run: if it has not landed on the real home feed
                                // yet, do nothing and wait for the next settle callback — a tap on
                                // "Save Info"/"Not Now" in the visible WebView, or Instagram's own
                                // auto-advance, will fire one.
                                reset.awaitingHomeLoad = true
                                if (!isHomeFeedUrl(view?.url)) return

                                // Reached only once the URL has actually settled on the real home
                                // feed. That still is not necessarily done loading in the
                                // background: Instagram's web app is a client-side-routed SPA, so
                                // doUpdateVisitedHistory (one of the two triggers for this method)
                                // fires the instant the URL bar updates via pushState — which
                                // happens close to immediately, well before the background
                                // requests that actually rotate rur/csrftoken/ig_did have
                                // finished. Give the page a beat to actually finish loading in the
                                // background before taking the real snapshot, and only take it once.
                                if (!reset.captureScheduled) {
                                    reset.captureScheduled = true
                                    view?.postDelayed({ finalizeCapture(view) }, HOME_FEED_SETTLE_DELAY_MS)
                                        ?: finalizeCapture(null)
                                }
                            }

                            /**
                             * Whether [url] is the real Instagram home feed root, as opposed to
                             * an interstitial along the post-login redirect chain (most commonly
                             * "Save Your Login Info?" at /accounts/onetap/, but also things like a
                             * notifications prompt). Those are left alone to render and, in the
                             * One Tap case, are meant to be seen and tapped through in this
                             * visible WebView rather than silently bypassed.
                             */
                            private fun isHomeFeedUrl(url: String?): Boolean {
                                if (url.isNullOrBlank()) return false
                                val uri = android.net.Uri.parse(url)
                                val host = uri.host?.lowercase() ?: return false
                                if (host != "instagram.com" && !host.endsWith(".instagram.com")) return false
                                val path = uri.path
                                return path.isNullOrBlank() || path == "/"
                            }

                            /**
                             * Takes the real, final cookie snapshot — called once, after the home
                             * feed has had time to finish its own background requests (see the
                             * comment above the call site). Reads cookies fresh rather than reusing
                             * whatever [onPageSettled] saw when it scheduled this, since the whole
                             * point of the delay is that more may have arrived since then.
                             */
                            private fun finalizeCapture(view: WebView?) {
                                if (captured) return
                                val cookies = getAllInstagramCookies()
                                val resolvedUserId = cookies["ds_user_id"]?.takeIf { it.isNotBlank() } ?: return
                                if (cookies["sessionid"].isNullOrBlank()) return

                                captured = true
                                CookieManager.getInstance().flush()
                                val fullCookies = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

                                if (view == null) {
                                    onCaptured(resolvedUserId, fullCookies)
                                    return
                                }
                                view.evaluateJavascript(VIEWER_USERNAME_JS) { raw ->
                                    val cleaned = raw
                                        ?.trim('"')
                                        ?.replace("\\\"", "\"")
                                        ?.trim()
                                        ?.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }

                                    val handle = when {
                                        cleaned == null -> resolvedUserId
                                        cleaned.contains("instagram.com/") || cleaned.startsWith("/") -> {
                                            val extracted = cleaned.trimEnd('/').substringAfterLast('/')
                                            if (extracted.isNotBlank() && !extracted.all { char -> char.isDigit() }) extracted else resolvedUserId
                                        }
                                        cleaned.all { char -> char.isDigit() } -> resolvedUserId
                                        else -> cleaned
                                    }
                                    onCaptured(handle, fullCookies)
                                }
                            }
                        }

                        // Start from a clean jar so Instagram serves the login form rather than
                        // the feed of whoever was signed in last. Defer the load until the view has
                        // a real height (see loadUrlWhenSized) so the page never lays out blank.
                        resetBrowserSession(this) { loadUrlWhenSized(LOGIN_URL) }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

        }

        loadError?.let { message ->
            StatusOverlay(
                title = "Instagram did not load",
                detail = message,
                actionLabel = "Try again",
                onAction = {
                    loadError = null
                    isLoading = true
                    // Retry from scratch: a retry that only reloads cannot recover the case where
                    // the failure was a session that refused to clear.
                    reset.clear()
                    webView?.let { view -> resetBrowserSession(view) { view.loadUrl(LOGIN_URL) } }
                },
                onDismiss = onBack
            )
        }

        if (errorMessage != null) {
            StatusOverlay(
                title = "Could not link the account",
                detail = errorMessage,
                actionLabel = "Back",
                onAction = onBack,
                onDismiss = onBack
            )
        } else if (captured) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = LoginColors.surface,
                    tonalElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = Color(0xFF4CB5F9))
                        Column {
                            Text(
                                "Session Captured!",
                                fontWeight = FontWeight.Bold,
                                color = LoginColors.text
                            )
                            Text(
                                "Linking Instagram account...",
                                fontSize = 12.sp,
                                color = LoginColors.muted
                            )
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun StatusOverlay(
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = LoginColors.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.padding(32.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(title, fontWeight = FontWeight.Bold, color = LoginColors.text)
                Text(
                    detail,
                    fontSize = 13.sp,
                    color = LoginColors.muted,
                    textAlign = TextAlign.Center
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = LoginColors.muted)
                    }
                    Button(
                        onClick = onAction,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CB5F9),
                            contentColor = Color.White
                        )
                    ) {
                        Text(actionLabel, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun parseCookieString(cookieString: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    val pairs = cookieString.split(";")
    for (pair in pairs) {
        val parts = pair.trim().split("=", limit = 2)
        if (parts.size == 2) {
            map[parts[0].trim()] = parts[1].trim()
        }
    }
    return map
}
