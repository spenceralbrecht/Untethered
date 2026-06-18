package com.cj.tapblok

import app.untethered.BuildConfig
import app.untethered.R

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.Service
import android.net.VpnService
import android.os.Build
import android.os.Parcelable
import android.provider.Settings
import android.util.Log

fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

fun isServiceRunning(@Suppress("UNUSED_PARAMETER") context: Context, serviceClass: Class<out Service>): Boolean {
    return when (serviceClass) {
        AppMonitoringService::class.java -> AppMonitoringService.isRunning
        WebsiteBlockVpnService::class.java -> WebsiteBlockVpnService.isRunning
        else -> false
    }
}

fun canRunMonitoringService(context: Context): Boolean =
    hasUsageStatsPermission(context) && Settings.canDrawOverlays(context)

fun hasFloatingWindowBlockingPermission(context: Context): Boolean {
    val accessibilityEnabled = Settings.Secure.getInt(
        context.contentResolver,
        Settings.Secure.ACCESSIBILITY_ENABLED,
        0
    ) == 1
    if (!accessibilityEnabled) return false

    val expected = ComponentName(context, BlockedWindowAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()

    return enabledServices.split(':').any { flattenedName ->
        val component = ComponentName.unflattenFromString(flattenedName)
        component?.packageName == expected.packageName &&
            component.className == expected.className
    }
}

fun startMonitoringService(context: Context): Boolean {
    val intent = Intent(context, AppMonitoringService::class.java)
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        true
    } catch (exception: RuntimeException) {
        Log.w("ServiceUtils", "Unable to start monitoring service.", exception)
        false
    }
}

fun ensureMonitoringDefaultEnabled(context: Context): Boolean {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    prefs.edit()
        .putBoolean("monitoring_active", true)
        .apply()

    if (!canRunMonitoringService(context)) return false

    val homeRules = LocationRuleManager.state(context)
    if (homeRules.enabled) {
        LocationRuleManager.registerHomeGeofence(context)
    }

    val monitoringRunning = isServiceRunning(context, AppMonitoringService::class.java) ||
        startMonitoringService(context)

    if (homeRules.enabled) {
        LocationRuleManager.refreshCurrentHomeState(context, startWhenAway = false)
    }

    return monitoringRunning
}

fun canRunWebsiteBlockingService(context: Context): Boolean =
    WebsiteBlockVpnService.isEnabled(context) &&
        VpnService.prepare(context) == null &&
        !WebsiteBlockVpnService.hasExternalVpn(context)

fun startWebsiteBlockingIfReady(context: Context) {
    if (!AppMonitoringService.isRunning || !canRunWebsiteBlockingService(context)) return
    WebsiteBlockVpnService.startIfAllowed(context)
}

@Suppress("DEPRECATION")
inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        getParcelableExtra(key) as? T
    }

@Suppress("DEPRECATION")
inline fun <reified T : Parcelable> Intent.getParcelableArrayExtraCompat(key: String): Array<out Parcelable>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayExtra(key, T::class.java)
    } else {
        getParcelableArrayExtra(key)
    }
