package com.feedpilot.client.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.feedpilot.client.MainActivity
import com.feedpilot.client.R
import com.feedpilot.client.bridge.BridgeConnectionState
import com.feedpilot.client.bridge.BridgeJobNotifier
import com.feedpilot.client.bridge.BridgeStatus
import com.feedpilot.client.bridge.BridgeStatusStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Floating system overlay service that displays live bridge status in a draggable bubble
 * that can sit over any app. When clicked, it expands into a translucent overlay card
 * showing real-time bridge messages and target/account details.
 */
class FloatingBridgeBubbleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusJob: Job? = null

    private var windowManager: WindowManager? = null
    private var rootContainer: FrameLayout? = null
    private var bubbleContainer: FrameLayout? = null
    private var overlayCard: LinearLayout? = null

    // Bubble components
    private var bubbleStatusDot: View? = null
    private var bubbleBackground: GradientDrawable? = null

    // Overlay components
    private var overlayCardBackground: GradientDrawable? = null
    private var overlayPillDot: View? = null
    private var overlayPillText: TextView? = null
    private var overlayMessageText: TextView? = null
    private var overlayDetailsContainer: LinearLayout? = null
    private var overlayTargetText: TextView? = null
    private var overlayAccountText: TextView? = null
    private var overlayJobText: TextView? = null

    private lateinit var windowParams: WindowManager.LayoutParams
    private var isExpanded = false
    private var currentStatusColor = Color.parseColor("#70E3AB")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!isOverlayPermissionGranted(this)) {
            Log.w(TAG, "Cannot start floating bubble: overlay permission not granted")
            stopSelf()
            return
        }
        setupWindowManager()
        createViews()
        observeBridgeStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isOverlayPermissionGranted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        statusJob?.cancel()
        scope.cancel()
        removeOverlayView()
        super.onDestroy()
    }

    private fun setupWindowManager() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val initialX = displayMetrics.widthPixels - dp(70)
        val initialY = displayMetrics.heightPixels / 4

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }
    }

    private fun createViews() {
        val root = FrameLayout(this)
        rootContainer = root

        createBubbleView(root)
        createOverlayCardView(root)

        runCatching {
            windowManager?.addView(root, windowParams)
        }.onFailure { e ->
            Log.e(TAG, "Failed to add floating bubble to WindowManager", e)
            stopSelf()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubbleView(root: FrameLayout) {
        val size = dp(56)
        val bubble = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            isClickable = true
            isFocusable = false
        }
        bubbleContainer = bubble

        bubbleBackground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E6181528")) // ~90% translucent dark purple
            setStroke(dp(2), currentStatusColor)
        }
        bubble.background = bubbleBackground

        // Center FeedPilot Icon
        val icon = ImageView(this).apply {
            val iconSize = dp(28)
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(R.drawable.ic_notification)
        }
        bubble.addView(icon)

        // Status Indicator Dot (bottom-right)
        val dotSize = dp(12)
        val statusDot = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = dp(4)
                rightMargin = dp(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(currentStatusColor)
                setStroke(dp(1), Color.WHITE)
            }
        }
        bubbleStatusDot = statusDot
        bubble.addView(statusDot)

        // Drag and click gesture detection on the bubble
        setupBubbleTouchListener(bubble)

        root.addView(bubble)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleTouchListener(bubble: View) {
        var initialX = 0
        var initialY = 0
        var touchDownX = 0f
        var touchDownY = 0f
        var isDragging = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowParams.x
                    initialY = windowParams.y
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchDownX).toInt()
                    val dy = (event.rawY - touchDownY).toInt()
                    if (!isDragging && (abs(event.rawX - touchDownX) > touchSlop || abs(event.rawY - touchDownY) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        windowParams.x = initialX + dx
                        windowParams.y = initialY + dy
                        runCatching { windowManager?.updateViewLayout(rootContainer, windowParams) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        expandOverlay()
                    } else {
                        snapBubbleToEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayCardView(root: FrameLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(dp(310), FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        overlayCard = card

        overlayCardBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor("#F012101E")) // ~94% dark translucent frosted card
            setStroke(dp(1), currentStatusColor)
        }
        card.background = overlayCardBackground

        // 1. Header Row (Status Badge + Minimize Button)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Status Pill inside Header
        val statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(50).toFloat()
                setColor(Color.parseColor("#252038"))
            }
        }

        overlayPillDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                rightMargin = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(currentStatusColor)
            }
        }
        statusPill.addView(overlayPillDot)

        overlayPillText = TextView(this).apply {
            text = "Bridge starting"
            textSize = 11f
            setTextColor(currentStatusColor)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        statusPill.addView(overlayPillText)
        headerRow.addView(statusPill)

        // Spacer
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        headerRow.addView(spacer)

        // Minimize Button
        val minimizeBtn = TextView(this).apply {
            text = "—"
            textSize = 16f
            setTextColor(Color.parseColor("#A39BC2"))
            gravity = Gravity.CENTER
            val btnSize = dp(28)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#252038"))
            }
            setOnClickListener { collapseOverlay() }
        }
        headerRow.addView(minimizeBtn)

        // Header draggable
        setupOverlayDragListener(headerRow)
        card.addView(headerRow)

        // 2. Bridge Message Text
        overlayMessageText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
            text = "Waiting for bridge requests"
            textSize = 13f
            setTextColor(Color.parseColor("#F5F3FA"))
            typeface = Typeface.DEFAULT
            maxLines = 4
        }
        card.addView(overlayMessageText)

        // 3. Details Container (Target, Session Account, Job ID)
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1B1728"))
            }
            visibility = View.GONE
        }
        overlayDetailsContainer = details

        overlayTargetText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#BDB5D8"))
            visibility = View.GONE
        }
        details.addView(overlayTargetText)

        overlayAccountText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#BDB5D8"))
            visibility = View.GONE
        }
        details.addView(overlayAccountText)

        overlayJobText = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#8E84AA"))
            visibility = View.GONE
        }
        details.addView(overlayJobText)

        card.addView(details)

        // 4. Action Buttons Row (Open FeedPilot, Bridge Logs, Collapse)
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }

        // Open App Button
        val openAppBtn = TextView(this).apply {
            text = "Open App"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#6A3BB8"))
            }
            setOnClickListener {
                openMainActivity(null)
                collapseOverlay()
            }
        }
        actionsRow.addView(openAppBtn)

        val actionSpacer1 = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 0) }
        actionsRow.addView(actionSpacer1)

        // Logs Button
        val logsBtn = TextView(this).apply {
            text = "Logs"
            textSize = 11f
            setTextColor(Color.parseColor("#D5CEEB"))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#252038"))
                setStroke(dp(1), Color.parseColor("#3D3558"))
            }
            setOnClickListener {
                openMainActivity(BridgeJobNotifier.ACTION_OPEN_LOGS)
                collapseOverlay()
            }
        }
        actionsRow.addView(logsBtn)

        val actionSpacer2 = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }
        actionsRow.addView(actionSpacer2)

        // Collapse Button
        val collapseBtn = TextView(this).apply {
            text = "Collapse"
            textSize = 11f
            setTextColor(Color.parseColor("#A39BC2"))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { collapseOverlay() }
        }
        actionsRow.addView(collapseBtn)

        card.addView(actionsRow)
        root.addView(card)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlayDragListener(header: View) {
        var initialX = 0
        var initialY = 0
        var touchDownX = 0f
        var touchDownY = 0f
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var isDragging = false

        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowParams.x
                    initialY = windowParams.y
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchDownX).toInt()
                    val dy = (event.rawY - touchDownY).toInt()
                    if (!isDragging && (abs(event.rawX - touchDownX) > touchSlop || abs(event.rawY - touchDownY) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        windowParams.x = initialX + dx
                        windowParams.y = initialY + dy
                        runCatching { windowManager?.updateViewLayout(rootContainer, windowParams) }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun expandOverlay() {
        if (isExpanded) return
        isExpanded = true
        bubbleContainer?.visibility = View.GONE
        overlayCard?.visibility = View.VISIBLE

        // Adjust position so overlay fits comfortably on screen
        val displayMetrics = resources.displayMetrics
        val maxX = displayMetrics.widthPixels - dp(320)
        if (windowParams.x > maxX) {
            windowParams.x = maxOf(dp(16), maxX)
        }
        runCatching { windowManager?.updateViewLayout(rootContainer, windowParams) }
    }

    private fun collapseOverlay() {
        if (!isExpanded) return
        isExpanded = false
        overlayCard?.visibility = View.GONE
        bubbleContainer?.visibility = View.VISIBLE
        snapBubbleToEdge()
    }

    private fun snapBubbleToEdge() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val bubbleSize = dp(56)

        // Snap to left or right edge with small margin
        val targetX = if (windowParams.x + bubbleSize / 2 < screenWidth / 2) {
            dp(8)
        } else {
            screenWidth - bubbleSize - dp(8)
        }
        windowParams.x = targetX

        // Clamp Y within screen
        val minY = dp(24)
        val maxY = displayMetrics.heightPixels - bubbleSize - dp(40)
        windowParams.y = windowParams.y.coerceIn(minY, maxY)

        runCatching { windowManager?.updateViewLayout(rootContainer, windowParams) }
    }

    private fun observeBridgeStatus() {
        statusJob = scope.launch {
            BridgeStatusStore.status.collect { status ->
                updateUi(status)
            }
        }
    }

    private fun updateUi(status: BridgeStatus) {
        currentStatusColor = resolveStatusColor(status.state)

        // 1. Update Bubble UI
        bubbleBackground?.setStroke(dp(2), currentStatusColor)
        (bubbleStatusDot?.background as? GradientDrawable)?.setColor(currentStatusColor)

        // 2. Update Overlay Card UI
        overlayCardBackground?.setStroke(dp(1), currentStatusColor)
        (overlayPillDot?.background as? GradientDrawable)?.setColor(currentStatusColor)
        overlayPillText?.apply {
            text = status.pillLabel
            setTextColor(currentStatusColor)
        }

        overlayMessageText?.text = status.message

        // 3. Update Details Container
        val hasTarget = !status.currentUsername.isNullOrBlank()
        val hasAccount = !status.accountUsername.isNullOrBlank()
        val hasJobId = !status.jobId.isNullOrBlank()

        if (hasTarget || hasAccount || hasJobId) {
            overlayDetailsContainer?.visibility = View.VISIBLE

            overlayTargetText?.apply {
                if (hasTarget) {
                    text = "Target: @${status.currentUsername?.removePrefix("@")}"
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }

            overlayAccountText?.apply {
                if (hasAccount) {
                    text = "Session: @${status.accountUsername?.removePrefix("@")}"
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }

            overlayJobText?.apply {
                if (hasJobId) {
                    text = "Job ID: ${status.jobId}"
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }
        } else {
            overlayDetailsContainer?.visibility = View.GONE
        }
    }

    private fun resolveStatusColor(state: BridgeConnectionState): Int = when (state) {
        BridgeConnectionState.Starting,
        BridgeConnectionState.RunningJob,
        BridgeConnectionState.Finished,
        BridgeConnectionState.SendingResult -> Color.parseColor("#FFA726") // Warm Orange
        BridgeConnectionState.Disabled -> Color.parseColor("#8E8E93") // Neutral Gray
        BridgeConnectionState.JobFailed,
        BridgeConnectionState.ResultFailed,
        BridgeConnectionState.Error,
        BridgeConnectionState.Offline -> Color.parseColor("#EF5350") // Error Red
        else -> Color.parseColor("#70E3AB") // Active Emerald Green
    }

    private fun openMainActivity(action: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            action?.let { this.action = it }
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "Could not open MainActivity from floating bubble", it) }
    }

    private fun removeOverlayView() {
        rootContainer?.let { root ->
            runCatching { windowManager?.removeView(root) }
                .onFailure { Log.w(TAG, "Could not remove floating bubble view", it) }
        }
        rootContainer = null
        bubbleContainer = null
        overlayCard = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "FloatingBridgeBubble"

        fun isOverlayPermissionGranted(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        fun start(context: Context) {
            if (!isOverlayPermissionGranted(context)) {
                Log.d(TAG, "Overlay permission not granted; skipping service start")
                return
            }
            val intent = Intent(context, FloatingBridgeBubbleService::class.java)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Could not start FloatingBridgeBubbleService", it) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingBridgeBubbleService::class.java)
            runCatching { context.stopService(intent) }
        }
    }
}

