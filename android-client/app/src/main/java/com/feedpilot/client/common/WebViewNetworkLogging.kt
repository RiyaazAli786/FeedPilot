package com.feedpilot.client.common

import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.feedpilot.client.BuildConfig
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.dto.InstagramCallLogRequest
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface WebViewTelemetryEntryPoint {
    fun apiService(): ApiService
}

/**
 * Best-effort visibility into the JSON API calls a login/session WebView's own page JavaScript
 * makes — Android's WebView APIs only let native code substitute a response *before* a request
 * runs ([android.webkit.WebViewClient.shouldInterceptRequest]), not read the real one after it
 * completes, so this instead patches `fetch`/`XMLHttpRequest` inside the page itself and relays
 * each JSON response — already decoded (gzip, charset) by the browser engine, not re-derived here
 * — to the backend's `/api/log/instagram-call` relay (see [InstagramCallTelemetry], which does the
 * same for this app's own OkHttp-based Instagram calls; this is the WebView-side counterpart for
 * traffic that never goes through OkHttp at all — the WebView login/session flows use the platform
 * WebView's own network stack). That endpoint forwards to the Telegram monitoring chat
 * (`TelegramRequestLogger.LogClientApiCallAsync`), redacting credentials server-side same as any
 * other relayed call. Non-JSON responses (images, scripts, the page's own HTML) are not reported —
 * this is meant to surface the same kind of API traffic already visible for the OkHttp-based
 * client, not a full page capture.
 *
 * Gated on [BuildConfig.LOG_HTTP_BODY] (the `LOG_HTTP_RESPONSES` env var at build time — see
 * build.gradle.kts) and a complete no-op when it's off: these are login/session WebViews whose
 * traffic includes Instagram's session cookies, so this must never run unnoticed in a shipped
 * build.
 */
object WebViewNetworkLogging {
    private const val TAG = "WebViewNet"
    private const val MARKER = "[TF_NET]"
    private const val MAX_BODY_CHARS = 700

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Call once right after constructing the WebView. Sets its [WebChromeClient]. */
    fun install(webView: WebView) {
        if (!BuildConfig.LOG_HTTP_BODY) return
        val appContext = webView.context.applicationContext
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val msg = consoleMessage.message()
                if (!msg.startsWith(MARKER)) return false
                relay(appContext, msg.removePrefix(MARKER))
                return true
            }
        }
    }

    /**
     * Call from [android.webkit.WebViewClient.onPageStarted]. Re-injected on every navigation —
     * `fetch`/`XMLHttpRequest` live on `window`, which is torn down and rebuilt fresh for each
     * new document, so a patch installed for one page does not carry over to the next.
     */
    fun onPageStarted(view: WebView?) {
        if (!BuildConfig.LOG_HTTP_BODY) return
        view?.evaluateJavascript(PATCH_SCRIPT, null)
    }

    /** Parses one `{method,url,status,body}` record the page JS reported and relays it. */
    private fun relay(context: Context, jsonText: String) {
        val record = runCatching { JSONObject(jsonText) }.getOrNull() ?: return
        val method = record.optString("method").ifBlank { "GET" }
        val url = record.optString("url")
        if (url.isBlank()) return
        val status = record.optInt("status")
        val body = record.optString("body").ifBlank { null }

        scope.launch {
            runCatching {
                val apiService = EntryPointAccessors.fromApplication(context, WebViewTelemetryEntryPoint::class.java)
                    .apiService()
                apiService.logInstagramCall(InstagramCallLogRequest(method, url, status, body))
            }.onFailure { Log.w(TAG, "Failed to relay WebView call log for $url", it) }
        }
    }

    private val PATCH_SCRIPT = """
        (function(){
          if (window.__tfNetLogInstalled) return;
          window.__tfNetLogInstalled = true;
          var MAX = $MAX_BODY_CHARS;
          function trunc(s) {
            try { return (typeof s === 'string' && s.length > MAX) ? (s.slice(0, MAX) + '…[truncated]') : s; }
            catch (e) { return s; }
          }
          function isJson(contentType) {
            return !!contentType && contentType.toLowerCase().indexOf('json') !== -1;
          }
          function report(method, url, status, body) {
            try {
              console.log('$MARKER' + JSON.stringify({ method: method, url: url, status: status, body: trunc(body) }));
            } catch (e) {}
          }
          var origFetch = window.fetch;
          if (origFetch) {
            window.fetch = function() {
              var args = arguments;
              var method = (args[1] && args[1].method) || 'GET';
              var url = (args[0] && args[0].url) || args[0];
              return origFetch.apply(this, args).then(function(res) {
                try {
                  var ct = res.headers.get('content-type');
                  if (isJson(ct)) {
                    res.clone().text().then(function(body) {
                      report(method, String(url), res.status, body);
                    }).catch(function() {});
                  }
                } catch (e) {}
                return res;
              });
            };
          }
          var OrigXHR = window.XMLHttpRequest;
          if (OrigXHR) {
            var origOpen = OrigXHR.prototype.open;
            var origSend = OrigXHR.prototype.send;
            OrigXHR.prototype.open = function(method, url) {
              this.__tfMethod = method;
              this.__tfUrl = url;
              return origOpen.apply(this, arguments);
            };
            OrigXHR.prototype.send = function() {
              var xhr = this;
              xhr.addEventListener('loadend', function() {
                try {
                  if (isJson(xhr.getResponseHeader('Content-Type'))) {
                    report(xhr.__tfMethod, xhr.__tfUrl, xhr.status, xhr.responseText);
                  }
                } catch (e) {}
              });
              return origSend.apply(this, arguments);
            };
          }
        })();
    """.trimIndent()
}
