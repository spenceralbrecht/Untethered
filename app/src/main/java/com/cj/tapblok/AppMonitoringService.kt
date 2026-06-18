package com.cj.tapblok

import app.untethered.BuildConfig
import app.untethered.R

import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.cj.tapblok.database.AppDatabase
import com.cj.tapblok.database.BlockMode
import com.cj.tapblok.database.BlockedApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppMonitoringService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var prefs: android.content.SharedPreferences
    @Volatile private var blockedAppEntries: List<BlockedApp> = emptyList()
    @Volatile private var blockedApps: Set<String> = emptySet()
    @Volatile private var activeBlockedApps: Set<String> = emptySet()
    @Volatile private var hasBlockedWebsites = false
    @Volatile private var hasAlwaysBlockedWebsites = false
    private var isMonitoring = false

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "app_monitoring_channel"
        @Volatile var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("AppMonitoringService", "Service has started.")

        prefs.edit {
            putInt("blocked_app_attempts", 0)
            putBoolean("monitoring_active", true)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Untethered is Active")
            .setContentText("App and website blocking are running.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        LocationRuleManager.refreshCurrentHomeState(this, startWhenAway = false) { homeDistance ->
            val pausedAtHome = homeDistance.insideHome == true && LocationRuleManager.shouldAllowBlockedAppsAtHome(this)
            if (pausedAtHome && !hasAlwaysBlockedWebsites) {
                Log.d("AppMonitoringService", "Already inside home radius; monitoring will allow home-aware blocks.")
                WebsiteBlockVpnService.stop(this)
            } else if (hasBlockedWebsites) {
                WebsiteBlockVpnService.startIfAllowed(this)
            }
        }

        if (isMonitoring) return START_STICKY
        isMonitoring = true

        serviceScope.launch {
            db.blockedAppDao().getAllBlockedApps().collect { list ->
                blockedAppEntries = list
                blockedApps = allBlockedAppPackages()
                val pausedAtHome = LocationRuleManager.shouldAllowBlockedAppsAtHome(this@AppMonitoringService)
                activeBlockedApps = activeBlockedAppPackages(pausedAtHome)

                if (pausedAtHome) {
                    HardModeManager.setBlockedPackagesSuspended(
                        this@AppMonitoringService,
                        homeAwareBlockedAppPackages(),
                        suspended = false
                    )
                }
                HardModeManager.setBlockedPackagesSuspended(
                    this@AppMonitoringService,
                    activeBlockedApps,
                    suspended = true
                )
                if (BuildConfig.DEBUG) {
                    Log.d("AppMonitoringService", "Blocked apps updated from DB: active=$activeBlockedApps all=$blockedApps")
                }
            }
        }

        serviceScope.launch {
            db.blockedWebsiteDao().getAllBlockedWebsites().collect { list ->
                hasBlockedWebsites = list.isNotEmpty()
                hasAlwaysBlockedWebsites = list.any { BlockMode.isAlways(it.blockMode) }
                val pausedAtHome = LocationRuleManager.shouldAllowBlockedAppsAtHome(this@AppMonitoringService)
                if (!hasBlockedWebsites || (pausedAtHome && !hasAlwaysBlockedWebsites)) {
                    WebsiteBlockVpnService.stop(this@AppMonitoringService)
                } else if (!WebsiteBlockVpnService.isRunning) {
                    WebsiteBlockVpnService.startIfAllowed(this@AppMonitoringService)
                }
            }
        }

        serviceScope.launch {
            val localContext = this@AppMonitoringService

            while (isActive) {
                if (!hasUsageStatsPermission(localContext) || !Settings.canDrawOverlays(localContext)) {
                    Log.e("AppMonitoringService", "Permissions revoked. Stopping service.")
                    stopSelf()
                    break
                }

                val pausedAtHome = LocationRuleManager.shouldAllowBlockedAppsAtHome(localContext)
                activeBlockedApps = activeBlockedAppPackages(pausedAtHome)
                if (pausedAtHome && !hasAlwaysBlockedWebsites) {
                    WebsiteBlockVpnService.stop(localContext)
                    if (BuildConfig.DEBUG) {
                        Log.d("AppMonitoringService", "Inside home radius; home-aware blocking is paused.")
                    }
                } else if (hasBlockedWebsites && !WebsiteBlockVpnService.isRunning) {
                    WebsiteBlockVpnService.startIfAllowed(localContext)
                }

                if (pausedAtHome && activeBlockedApps.isEmpty()) {
                    delay(1000)
                    continue
                }

                val foregroundApp = getForegroundApp()
                if (BuildConfig.DEBUG) Log.d("AppMonitoringService", "Current App: $foregroundApp")

                if (foregroundApp != null &&
                    LocationRuleManager.isTemporarilyAllowedAtHome(localContext, foregroundApp)
                ) {
                    if (BuildConfig.DEBUG) {
                        Log.d("AppMonitoringService", "Skipping block during home-confirmed allow window: $foregroundApp")
                    }
                    delay(1000)
                    continue
                }

                if (foregroundApp != null && foregroundApp in activeBlockedApps && foregroundApp != packageName) {
                    val blockIntent = Intent(localContext, BlockingActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("BLOCKED_APP_PACKAGE_NAME", foregroundApp)
                    }
                    startActivity(blockIntent)
                    if (BuildConfig.DEBUG) Log.d("AppMonitoringService", "Blocked app detected: $foregroundApp")

                    val attempts = prefs.getInt("blocked_app_attempts", 0)
                    prefs.edit {
                        putInt("blocked_app_attempts", attempts + 1)
                    }
                }
                delay(1000)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        WebsiteBlockVpnService.stop(this)
        BlockingOverlayController.hide(this)
        HardModeManager.setBlockedPackagesSuspended(this, blockedApps, suspended = false)
        serviceScope.cancel()
        Log.d("AppMonitoringService", "Service has been destroyed.")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        ensureMonitoringDefaultEnabled(this)
        Log.d("AppMonitoringService", "Task removed; requested monitoring recovery.")
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    @Suppress("DEPRECATION")
    private fun getForegroundApp(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val recentForegroundApp = usageStatsManager.queryEvents(time - 3000, time)?.let { events ->
            val event = UsageEvents.Event()
            var latestPackage: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                ) {
                    latestPackage = event.packageName
                }
            }
            latestPackage
        }
        if (recentForegroundApp != null) return recentForegroundApp

        val appList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            time - 1000 * 10,
            time
        )
        return appList?.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    private fun allBlockedAppPackages(): Set<String> =
        blockedAppEntries.map { it.packageName }.toSet()

    private fun homeAwareBlockedAppPackages(): Set<String> =
        blockedAppEntries
            .filterNot { BlockMode.isAlways(it.blockMode) }
            .map { it.packageName }
            .toSet()

    private fun activeBlockedAppPackages(pausedAtHome: Boolean): Set<String> =
        blockedAppEntries
            .filter { !pausedAtHome || BlockMode.isAlways(it.blockMode) }
            .map { it.packageName }
            .toSet()
}
