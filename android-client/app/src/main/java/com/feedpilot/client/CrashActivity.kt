package com.feedpilot.client

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.feedpilot.client.common.CrashReporter

/**
 * Displays a clean, user-readable error screen when an uncaught exception occurs.
 * Features a friendly error summary, Telegram report submission, and collapsible technical logs.
 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = intent?.getStringExtra(EXTRA_REPORT) ?: "No report was captured."
        val userTitle = intent?.getStringExtra(EXTRA_TITLE) ?: "Application Notice"
        val userSummary = intent?.getStringExtra(EXTRA_SUMMARY) ?: "An unexpected application notice occurred."
        val path = intent?.getStringExtra(EXTRA_PATH)

        setContentView(buildView(userTitle, userSummary, report, path))
    }

    private fun buildView(userTitle: String, userSummary: String, report: String, path: String?): View {
        val pad = dp(16)

        // Top Alert Banner Container
        val alertCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1F1B16"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }

        val appName = TextView(this).apply {
            text = "⚠️ FeedPilot Notice"
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#FF9800"))
        }

        val titleView = TextView(this).apply {
            text = userTitle
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, dp(4))
        }

        val summaryView = TextView(this).apply {
            text = userSummary
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(Color.parseColor("#E0E0E0"))
        }

        alertCard.addView(appName)
        alertCard.addView(titleView)
        alertCard.addView(summaryView)

        val subtitle = TextView(this).apply {
            text = if (path != null) {
                "Saved report file: $path"
            } else {
                "The details could not be saved to local storage."
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#808080"))
            setPadding(0, 0, 0, dp(10))
        }

        // Technical Log View (Hidden by default)
        val trace = TextView(this).apply {
            text = report
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.parseColor("#CCCCCC"))
            setTextIsSelectable(true)
        }

        val scroll = ScrollView(this).apply {
            addView(trace)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }

        // Toggle Technical Log Button
        var isTraceVisible = false
        lateinit var toggleButton: Button
        toggleButton = button("Show Technical Log") {
            isTraceVisible = !isTraceVisible
            scroll.visibility = if (isTraceVisible) View.VISIBLE else View.GONE
            toggleButton.text = if (isTraceVisible) "Hide Technical Log" else "Show Technical Log"
        }

        val actionButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(button("Report to Telegram") {
                CrashReporter.sendCrashToTelegram(this@CrashActivity, userTitle, userSummary, report)
                Toast.makeText(this@CrashActivity, "Crash log sent to Telegram log", Toast.LENGTH_SHORT).show()
            })
            addView(button("Copy Log") { copy(report) })
            addView(button("Share") { share(report) })
        }

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
            addView(toggleButton)
            addView(button("Close") { finishAffinity() })
        }

        actionButtons.addView(row1)
        actionButtons.addView(row2)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
            addView(alertCard)
            addView(subtitle)
            addView(scroll)
            addView(actionButtons)
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            .apply { marginStart = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun copy(report: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("FeedPilot crash", report))
        Toast.makeText(this, "Crash report copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun share(report: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "FeedPilot crash report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        runCatching { startActivity(Intent.createChooser(send, "Send crash report")) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val PROCESS_SUFFIX = ":crash"

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUMMARY = "summary"
        private const val EXTRA_REPORT = "report"
        private const val EXTRA_PATH = "path"
        private const val MAX_REPORT_CHARS = 100_000

        fun show(context: Context, title: String, summary: String, report: String, path: String?) {
            val payload =
                if (report.length > MAX_REPORT_CHARS) {
                    report.take(MAX_REPORT_CHARS) + "\n\n[truncated - see the saved file]"
                } else {
                    report
                }
            val intent = Intent(context, CrashActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SUMMARY, summary)
                .putExtra(EXTRA_REPORT, payload)
                .putExtra(EXTRA_PATH, path)
            context.startActivity(intent)
        }

        fun show(context: Context, report: String, path: String?) {
            show(context, "Application Notice", "An unexpected error occurred.", report, path)
        }
    }
}
