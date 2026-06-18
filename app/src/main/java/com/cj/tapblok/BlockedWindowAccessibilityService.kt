package com.cj.tapblok

import app.untethered.BuildConfig
import app.untethered.R

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.cj.tapblok.database.AppDatabase
import com.cj.tapblok.database.BlockMode
import com.cj.tapblok.database.BlockedApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class BlockedWindowAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var blockedApps: List<BlockedApp> = emptyList()

    @Volatile
    private var lastBlockedPackage: String? = null

    @Volatile
    private var lastBlockAtMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentService = this
        serviceScope.launch {
            AppDatabase.getDatabase(this@BlockedWindowAccessibilityService)
                .blockedAppDao()
                .getAllBlockedApps()
                .distinctUntilChanged()
                .collect { blockedApps = it }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !AppMonitoringService.isRunning) return

        val packageName = detectedBlockedPackage(event) ?: return
        if (LocationRuleManager.isTemporarilyAllowedAtHome(this, packageName)) return

        enforceWindowBlock(packageName)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        if (currentService === this) currentService = null
        BlockingOverlayController.hide(this)
        serviceScope.cancel()
    }

    private fun detectedBlockedPackage(event: AccessibilityEvent): String? {
        val blockedNow = activeBlockedPackages()
        if (blockedNow.isEmpty()) return null

        val candidatePackages = buildSet {
            event.packageName?.toString()?.let(::add)
            rootInActiveWindow?.packageName?.toString()?.let(::add)
            windows.forEach { window ->
                window.root?.packageName?.toString()?.let(::add)
            }
        }

        return candidatePackages.firstOrNull { packageName ->
            packageName != this.packageName &&
                !ProtectedPackages.isProtected(packageName, this.packageName) &&
                packageName in blockedNow
        }
    }

    private fun activeBlockedPackages(): Set<String> {
        val pausedAtHome = LocationRuleManager.shouldAllowBlockedAppsAtHome(this)
        return blockedApps
            .filter { !pausedAtHome || BlockMode.isAlways(it.blockMode) }
            .map { it.packageName }
            .toSet()
    }

    private fun enforceWindowBlock(packageName: String) {
        val now = SystemClock.elapsedRealtime()
        if (packageName == lastBlockedPackage && now - lastBlockAtMs < BLOCK_THROTTLE_MS) return

        lastBlockedPackage = packageName
        lastBlockAtMs = now

        val attempts = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getInt("blocked_app_attempts", 0)
        getSharedPreferences("app_prefs", MODE_PRIVATE)
            .edit()
            .putInt("blocked_app_attempts", attempts + 1)
            .apply()

        val overlayShown = BlockingOverlayController.show(this, packageName)
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (overlayShown) return

        mainHandler.postDelayed({
            val blockIntent = Intent(this, BlockingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("BLOCKED_APP_PACKAGE_NAME", packageName)
            }
            startActivity(blockIntent)
        }, BLOCK_SCREEN_DELAY_MS)
    }

    private fun visiblePackages(): Set<String> =
        buildSet {
            rootInActiveWindow?.packageName?.toString()?.let(::add)
            windows.forEach { window ->
                window.root?.packageName?.toString()?.let(::add)
            }
        }

    companion object {
        private const val BLOCK_THROTTLE_MS = 1500L
        private const val BLOCK_SCREEN_DELAY_MS = 120L

        @Volatile
        private var currentService: BlockedWindowAccessibilityService? = null

        fun isPackageWindowVisible(packageName: String): Boolean =
            currentService?.visiblePackages()?.contains(packageName) == true

        fun performHomeAction() {
            currentService?.performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }
}
