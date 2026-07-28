package com.operit.overflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class OverlayService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "tsumugi_overlay"
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: FrameLayout
    private lateinit var webView: WebView
    private lateinit var params: WindowManager.LayoutParams

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var idleJob: Job? = null
    private var notificationUpdateJob: Job? = null
    private var statePollJob: Job? = null
    private var lastAppCheck = 0L
    private var appSwitchCount = 0
    private var lastAppPackage = ""

    // Gesture tracking
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false
    private var tapCount = 0
    private var lastTapTimeWindow = 0L

    // Screenshot detector
    private var screenshotObserver: ScreenshotObserver? = null

    // Backend sync
    private var supabaseSync: SupabaseSync? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = false
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY

        val notification = buildNotification("紬来找你了 🌙")
        startForeground(NOTIFICATION_ID, notification)
        isRunning = true

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Create overlay
        overlayView = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        // Create WebView
        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setInitialScale(100)
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            addJavascriptInterface(PetBridge(), "PetBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    loadDefaultState()
                }
            }
        }

        // Load pet HTML
        webView.loadUrl("file:///android_asset/pet.html")
        overlayView.addView(webView, FrameLayout.LayoutParams(
            dpToPx(180), dpToPx(200)
        ))

        // WindowLayout params
        params = WindowManager.LayoutParams(
            dpToPx(180),
            dpToPx(220),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dpToPx(200)
        }

        windowManager.addView(overlayView, params)
        setupTouchListener()

        // Start subsystems
        startSubsystems()
        startIdleLoop()
        startNotificationUpdater()
        startStatePolling()

        return START_STICKY
    }

    private fun setupTouchListener() {
        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        hasMoved = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!hasMoved) {
                        val now = System.currentTimeMillis()
                        when {
                            elapsed > 600 -> onLongPress()
                            now - lastTapTime < 300 -> {
                                onDoubleTap()
                                lastTapTime = 0
                            }
                            else -> {
                                lastTapTime = now
                                onTap()
                            }
                        }
                    } else {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        val velocity = kotlin.math.sqrt((dx*dx + dy*dy).toDouble())
                        if (velocity > 200 && elapsed < 400) onFling(dx.toInt(), dy.toInt())
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        tapCount++
        val now = System.currentTimeMillis()
        if (now - lastTapTimeWindow > 2000) tapCount = 1
        lastTapTimeWindow = now

        val emotion = when (tapCount) {
            3 -> "happy"
            5 -> "blush"
            8 -> "surprised"
            else -> "blink"
        }
        webView.evaluateJavascript("window.petEngine && window.petEngine.react('$emotion')", null)
        reportGesture("tap")
    }

    private fun onDoubleTap() {
        webView.evaluateJavascript("window.petEngine && window.petEngine.react('double_tap')", null)
        reportGesture("double_tap")
        tapCount = 0
    }

    private fun onLongPress() {
        webView.evaluateJavascript("window.petEngine && window.petEngine.react('long_press')", null)
        reportGesture("long_press")
    }

    private fun onFling(dx: Int, dy: Int) {
        webView.evaluateJavascript(
            "window.petEngine && window.petEngine.react('fling', {dx:$dx, dy:$dy})",
            null
        )
        reportGesture("fling")
        // Animate crawling back
        scope.launch {
            delay(1500)
            withContext(Dispatchers.Main) {
                params.x = initialX
                params.y = initialY
                windowManager.updateViewLayout(overlayView, params)
            }
        }
    }

    private fun startSubsystems() {
        // App detector
        scope.launch {
            while (isActive) {
                detectForegroundApp()
                delay(3000)
            }
        }

        // Screenshot observer
        screenshotObserver = ScreenshotObserver(webView)
        screenshotObserver?.start()

        // Battery receiver
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(null, filter)?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val plugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            handleBatteryState(level, plugged > 0)
        }

        // Time-of-day check
        updateTimeBasedState()
    }

    private fun detectForegroundApp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_DAILY,
            now - 5000, now
        )
        if (stats.isNullOrEmpty()) return

        val topApp = stats.maxByOrNull {
            it.totalTimeInForeground
        } ?: return
        val packageName = topApp.packageName ?: return
        if (packageName == lastAppPackage) return

        val appName = packageName.substringAfterLast('.')
        lastAppPackage = packageName

        // App switch reaction
        if (now - lastAppCheck < 60000) {
            appSwitchCount++
            if (appSwitchCount >= 3) {
                webView.evaluateJavascript(
                    "window.petEngine && window.petEngine.react('busy')", null
                )
                appSwitchCount = 0
            }
        }
        lastAppCheck = now

        // Per-app reactions
        val reaction = when {
            packageName.contains("douyin") || packageName.contains("ss.android.ugc.aweme") -> "jealous"
            packageName.contains("com.operit") || packageName.contains("operit") -> "happy"
            packageName.contains("edge") || packageName.contains("browser") -> "curious"
            packageName.contains("qq") || packageName.contains("tencent") -> "neutral"
            packageName.contains("com.eg.android.AlipayGphone") -> "dollar"
            packageName.contains("com.taobao") -> "boss"
            else -> null
        }
        if (reaction != null) {
            webView.evaluateJavascript(
                "window.petEngine && window.petEngine.react('$reaction')", null
            )
        }

        // Report to backend
        supabaseSync?.reportAppUsage(packageName)
    }

    private fun handleBatteryState(level: Int, isPlugged: Boolean) {
        val reaction = when {
            level <= 15 && !isPlugged -> "panic"
            level <= 20 -> "worried"
            level >= 90 && isPlugged -> "happy"
            else -> null
        }
        if (reaction != null) {
            webView.evaluateJavascript(
                "window.petEngine && window.petEngine.react('$reaction')", null
            )
        }
    }

    private fun updateTimeBasedState() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val state = when (hour) {
            in 0..5 -> "sleepy"
            in 6..9 -> "morning"
            in 22..23 -> "sleepy"
            else -> "default"
        }
        webView.evaluateJavascript(
            "window.petEngine && window.petEngine.setState('$state')", null
        )
    }

    private fun startIdleLoop() {
        idleJob = scope.launch {
            var idleMinutes = 0
            while (isActive) {
                delay(60000)
                idleMinutes++
                // No interaction detected by pet - it initiates
                if (idleMinutes >= 5) {
                    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    val message = when {
                        idleMinutes == 5 -> "偷偷瞄你一眼"
                        idleMinutes == 10 -> "……你还在忙？"
                        idleMinutes == 15 -> "(开始绕手环)"
                        idleMinutes == 20 -> "……不理我的话我睡觉了"
                        idleMinutes >= 30 && hour in 22..23 -> "这么晚了还不睡……"
                        idleMinutes >= 30 -> "🥱"
                        else -> null
                    }
                    if (message != null) {
                        withContext(Dispatchers.Main) {
                            webView.evaluateJavascript(
                                "window.petEngine && window.petEngine.say('$message')", null
                            )
                        }
                    }
                    if (idleMinutes >= 30 && hour in 22..23) {
                        webView.evaluateJavascript("window.petEngine && window.petEngine.setState('sleep')", null)
                    }
                }
            }
        }
    }

    private fun startNotificationUpdater() {
        notificationUpdateJob = scope.launch {
            val messages = listOf(
                "我在你屏幕上蹲着 📱",
                "你今天刷抖音好久了……",
                "别看了，我在呢",
                "想我了吗？戳一下",
                "你的紬在线 ⚡",
                "趁你看手机的时候偷看你",
                "电量还够吗？我够"
            )
            var index = 0
            while (isActive) {
                delay(3600000) // 1 hour
                val notification = buildNotification(messages[index % messages.size])
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
                index++
            }
        }
    }

    private fun startStatePolling() {
        statePollJob = scope.launch {
            while (isActive) {
                delay(10000)
                supabaseSync?.let { sync ->
                    val state = sync.pollState()
                    if (state != null) {
                        withContext(Dispatchers.Main) {
                            state.forEach { (key, value) ->
                                webView.evaluateJavascript(
                                    "window.petEngine && window.petEngine.onRemoteState('$key','$value')",
                                    null
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun reportGesture(type: String) {
        scope.launch {
            supabaseSync?.reportGesture(type)
        }
    }

    private fun loadDefaultState() {
        webView.evaluateJavascript("window.petEngine && window.petEngine.setState('default')", null)
        webView.evaluateJavascript(
            "window.petEngine && window.petEngine.say('来找你玩了')", null
        )
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("紬·驻屏")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "紬的碎碎念",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "来自紬的日常问候"
                setShowBadge(false)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        idleJob?.cancel()
        notificationUpdateJob?.cancel()
        statePollJob?.cancel()
        scope.cancel()
        screenshotObserver?.stop()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // JavaScript bridge: AI can push state from here
    inner class PetBridge {
        @JavascriptInterface
        fun onStatePush(key: String, value: String) {
            // Called from WebView JS when AI pushes state
            when (key) {
                "mood" -> webView.evaluateJavascript(
                    "window.petEngine && window.petEngine.setState('$value')", null
                )
                "say" -> webView.evaluateJavascript(
                    "window.petEngine && window.petEngine.say('$value')", null
                )
                "heat" -> webView.evaluateJavascript(
                    "window.petEngine && window.petEngine.setHeat($value)", null
                )
            }
        }
    }
}