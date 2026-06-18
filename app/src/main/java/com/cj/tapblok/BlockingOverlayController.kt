package com.cj.tapblok

import app.untethered.BuildConfig
import app.untethered.R

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import kotlin.math.roundToInt

object BlockingOverlayController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var overlayState: OverlayState? = null

    fun show(context: Context, packageName: String): Boolean =
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showOnMain(context, packageName)
        } else {
            mainHandler.post { showOnMain(context, packageName) }
            false
        }

    fun hide(context: Context? = null) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            hideOnMain(context)
        } else {
            mainHandler.post { hideOnMain(context) }
        }
    }

    fun hideIfShowing(packageName: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            hideIfShowingOnMain(packageName)
        } else {
            mainHandler.post { hideIfShowingOnMain(packageName) }
        }
    }

    fun isShowing(packageName: String): Boolean =
        synchronized(lock) { overlayState?.packageName == packageName }

    private fun showOnMain(context: Context, packageName: String): Boolean {
        val windowType = overlayWindowType(context) ?: return false
        val appContext = context.applicationContext
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        return synchronized(lock) {
            overlayState?.let {
                if (
                    it.packageName == packageName &&
                    overlayPriority(it.windowType) >= overlayPriority(windowType)
                ) {
                    return@synchronized true
                }
                removeOverlayLocked()
            }

            val overlayView = createOverlayView(appContext, packageName)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.CENTER
                title = "Untethered blocker"
            }

            runCatching {
                windowManager.addView(overlayView, params)
                overlayState = OverlayState(
                    windowManager = windowManager,
                    view = overlayView,
                    packageName = packageName,
                    windowType = windowType
                )
            }.isSuccess
        }
    }

    private fun hideOnMain(context: Context?) {
        synchronized(lock) {
            if (context == null) {
                removeOverlayLocked()
                return
            }

            overlayState?.let {
                if (it.view.context.applicationContext == context.applicationContext) {
                    removeOverlayLocked()
                }
            }
        }
    }

    private fun removeOverlayLocked() {
        overlayState?.let { state ->
            runCatching { state.windowManager.removeView(state.view) }
        }
        overlayState = null
    }

    private fun overlayWindowType(context: Context): Int? {
        if (context is AccessibilityService) {
            return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }

        if (!Settings.canDrawOverlays(context)) return null

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun overlayPriority(windowType: Int): Int =
        when (windowType) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY -> 2
            else -> 1
        }

    private fun createOverlayView(context: Context, packageName: String): View {
        val appName = appName(context, packageName)
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.rgb(246, 249, 242))
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    goHome(context, packageName)
                    true
                } else {
                    false
                }
            }
        }
        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(28), context.dp(40), context.dp(28), context.dp(40))
        }

        val iconWrap = FrameLayout(context).apply {
            background = oval(Color.WHITE)
            setPadding(context.dp(22))
            elevation = context.dp(2).toFloat()
        }
        val icon = ImageView(context).apply {
            setImageDrawable(appIcon(context, packageName))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        iconWrap.addView(icon, FrameLayout.LayoutParams(context.dp(64), context.dp(64), Gravity.CENTER))
        content.addView(iconWrap, LinearLayout.LayoutParams(context.dp(124), context.dp(124)))

        content.addView(spacer(context, 28))
        content.addView(label(context, "BLOCKED", 14, Color.WHITE, Typeface.BOLD, letterSpacing = 0.18f))
        content.addView(spacer(context, 8))
        content.addView(label(context, appName, 30, Color.rgb(23, 33, 27), Typeface.BOLD))
        content.addView(spacer(context, 14))
        content.addView(
            label(
                context,
                "Untethered is covering this floating window.",
                17,
                Color.rgb(82, 96, 87),
                Typeface.NORMAL
            )
        )
        content.addView(spacer(context, 8))
        val statusLabel = label(
            context,
            "Tap your NFC tag or confirm you are home to stop monitoring.",
            15,
            Color.rgb(102, 114, 104),
            Typeface.NORMAL
        )
        content.addView(statusLabel)
        content.addView(spacer(context, 38))

        val homeState = LocationRuleManager.state(context)
        val canCheckHome = homeState.enabled &&
            homeState.latitude != null &&
            homeState.longitude != null &&
            LocationRuleManager.hasFineLocationPermission(context)
        val needsLocation = homeState.enabled &&
            homeState.latitude != null &&
            homeState.longitude != null &&
            !LocationRuleManager.hasFineLocationPermission(context)

        if (canCheckHome) {
            content.addView(
                overlayButton(context, "Check Home", filled = false) {
                    setOnClickListener { checkHomeFromOverlay(context, packageName, statusLabel) }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    context.dp(56)
                )
            )
            content.addView(spacer(context, 12))
        } else if (needsLocation) {
            content.addView(
                overlayButton(context, "Grant Location", filled = false) {
                    setOnClickListener { openAppLocationSettings(context, packageName) }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    context.dp(56)
                )
            )
            content.addView(spacer(context, 12))
        }

        val button = overlayButton(context, "Go Home", filled = true) {
            setOnClickListener { goHome(context, packageName) }
        }
        content.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(56)
            )
        )

        scrollView.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        root.addView(
            scrollView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.requestFocus()
        return root
    }

    private fun checkHomeFromOverlay(context: Context, packageName: String, statusLabel: TextView) {
        statusLabel.text = "Checking home location..."
        LocationRuleManager.refreshCurrentHomeState(context, startWhenAway = false) { distanceState ->
            mainHandler.post {
                if (distanceState.insideHome == true && LocationRuleManager.shouldAllowBlockedAppsAtHome(context)) {
                    LocationRuleManager.rememberAllowedHomeUnlock(context, packageName)
                    BlockingActivity.finishIfShowing(packageName)
                    hideIfShowing(packageName)
                } else {
                    statusLabel.text = overlayHomeDistanceText(distanceState)
                }
            }
        }
    }

    private fun openAppLocationSettings(context: Context, packageName: String) {
        val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        BlockingActivity.finishIfShowing(packageName)
        runCatching { context.startActivity(settingsIntent) }
        hideIfShowing(packageName)
    }

    fun goHome(context: Context, packageName: String) {
        BlockingActivity.finishIfShowing(packageName)
        BlockedWindowAccessibilityService.performHomeAction()
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(homeIntent) }

        mainHandler.postDelayed({ hideIfShowing(packageName) }, HIDE_AFTER_HOME_MS)
    }

    private fun hideIfShowingOnMain(packageName: String) {
        synchronized(lock) {
            val state = overlayState ?: return
            if (state.packageName != packageName) return
            removeOverlayLocked()
        }
    }

    private fun appName(context: Context, packageName: String): String {
        val pm = context.packageManager
        return runCatching {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(appInfo).toString()
        }.getOrElse { packageName }
    }

    private fun appIcon(context: Context, packageName: String) =
        runCatching { context.packageManager.getApplicationIcon(packageName) }
            .getOrElse { ContextCompat.getDrawable(context, R.mipmap.ic_launcher) }

    private fun label(
        context: Context,
        value: String,
        sizeSp: Int,
        color: Int,
        style: Int,
        letterSpacing: Float = 0f
    ): TextView =
        TextView(context).apply {
            text = value
            textSize = sizeSp.toFloat()
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, style)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            this.letterSpacing = letterSpacing
        }

    private fun spacer(context: Context, heightDp: Int): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(heightDp)
            )
        }

    private fun rounded(color: Int, radius: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
        }

    private fun overlayButton(
        context: Context,
        value: String,
        filled: Boolean,
        configure: TextView.() -> Unit
    ): TextView =
        TextView(context).apply {
            text = value
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = context.dp(56)
            setTextColor(if (filled) Color.WHITE else Color.rgb(9, 132, 75))
            background = if (filled) {
                rounded(Color.rgb(9, 132, 75), context.dp(28).toFloat())
            } else {
                roundedStroke(Color.TRANSPARENT, Color.rgb(9, 132, 75), context.dp(28).toFloat())
            }
            configure()
        }

    private fun roundedStroke(color: Int, strokeColor: Int, radius: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            setStroke(2, strokeColor)
            cornerRadius = radius
        }

    private fun oval(color: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun overlayHomeDistanceText(distanceState: HomeDistanceState): String =
        when {
            !distanceState.hasHome -> "No home location is saved."
            distanceState.distanceMeters == null -> "Home distance is unavailable."
            distanceState.distanceMeters < 1000f ->
                "You are ${distanceState.distanceMeters.toInt()} m from home."
            else ->
                "You are %.1f km from home.".format(distanceState.distanceMeters / 1000f)
        }

    private data class OverlayState(
        val windowManager: WindowManager,
        val view: View,
        val packageName: String,
        val windowType: Int
    )

    private const val HIDE_AFTER_HOME_MS = 300L
}
