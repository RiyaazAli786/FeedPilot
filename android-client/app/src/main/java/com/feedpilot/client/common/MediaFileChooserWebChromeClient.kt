package com.feedpilot.client.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.result.ActivityResultLauncher

/**
 * A [WebChromeClient] that intercepts file input clicks from WebViews (e.g. Instagram's
 * photo/video/reel upload or avatar change buttons) and dispatches them to the system media picker.
 *
 * It automatically cancels any previous pending callback if a new file chooser request arrives,
 * preventing the WebView's internal file upload lock from hanging.
 */
class MediaFileChooserWebChromeClient(
    private val context: Context,
    private val fileChooserLauncher: ActivityResultLauncher<Intent>,
    private val onCallbackUpdate: (ValueCallback<Array<Uri>>?) -> Unit,
    private val getPendingCallback: () -> ValueCallback<Array<Uri>>?
) : WebChromeClient() {

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        // Cancel any existing pending callback so the WebView file upload lock does not get stuck
        getPendingCallback()?.onReceiveValue(null)
        onCallbackUpdate(filePathCallback)

        val intent = try {
            fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        } catch (e: Exception) {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        }

        val title = fileChooserParams?.title?.takeIf { it.isNotBlank() } ?: "Select Media"
        val chooserIntent = Intent.createChooser(intent, title)

        return try {
            fileChooserLauncher.launch(chooserIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cannot launch media picker chooser", e)
            onCallbackUpdate(null)
            filePathCallback?.onReceiveValue(null)
            false
        }
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        return WebViewNetworkLogging.handleConsoleMessage(context, consoleMessage) || super.onConsoleMessage(consoleMessage)
    }

    companion object {
        private const val TAG = "MediaFileChooser"
    }
}
