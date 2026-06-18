package com.cj.tapblok

import app.untethered.BuildConfig
import app.untethered.R

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.cj.tapblok.database.AppDatabase
import com.cj.tapblok.database.BlockMode
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Calendar
import kotlin.coroutines.resume

data class HomeRuleState(
    val enabled: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val radiusMeters: Float,
    val insideHome: Boolean,
    val label: String?,
    val lastDistanceMeters: Float?,
    val sleepBlockEnabled: Boolean,
    val sleepBlockStartMinutes: Int,
    val sleepBlockEndMinutes: Int
)

data class HomeDistanceState(
    val hasHome: Boolean,
    val distanceMeters: Float?,
    val radiusMeters: Float,
    val insideHome: Boolean?
)

object LocationRuleManager {
    private const val TAG = "LocationRuleManager"
    private const val PREFS = "app_prefs"
    private const val KEY_HOME_RULE_ENABLED = "home_rule_enabled"
    private const val KEY_HOME_LATITUDE = "home_latitude"
    private const val KEY_HOME_LONGITUDE = "home_longitude"
    private const val KEY_HOME_RADIUS_METERS = "home_radius_meters"
    private const val KEY_INSIDE_HOME = "inside_home"
    private const val KEY_HOME_LABEL = "home_label"
    private const val KEY_LAST_DISTANCE_METERS = "last_home_distance_meters"
    private const val KEY_HOME_ALLOWED_PACKAGE = "home_allowed_package"
    private const val KEY_HOME_ALLOWED_UNTIL_MS = "home_allowed_until_ms"
    private const val KEY_HOME_SLEEP_BLOCK_ENABLED = "home_sleep_block_enabled"
    private const val KEY_HOME_SLEEP_BLOCK_START_MINUTES = "home_sleep_block_start_minutes"
    private const val KEY_HOME_SLEEP_BLOCK_END_MINUTES = "home_sleep_block_end_minutes"
    private const val HOME_GEOFENCE_ID = "home"
    private const val DEFAULT_RADIUS_METERS = 250f
    private const val HOME_ALLOWED_GRACE_MS = 15000L
    private const val MINUTES_PER_DAY = 24 * 60
    const val DEFAULT_SLEEP_BLOCK_START_MINUTES = 22 * 60
    const val DEFAULT_SLEEP_BLOCK_END_MINUTES = 5 * 60

    fun state(context: Context): HomeRuleState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hasHome = prefs.contains(KEY_HOME_LATITUDE) && prefs.contains(KEY_HOME_LONGITUDE)
        return HomeRuleState(
            enabled = prefs.getBoolean(KEY_HOME_RULE_ENABLED, false),
            latitude = if (hasHome) Double.fromBits(prefs.getLong(KEY_HOME_LATITUDE, 0L)) else null,
            longitude = if (hasHome) Double.fromBits(prefs.getLong(KEY_HOME_LONGITUDE, 0L)) else null,
            radiusMeters = prefs.getFloat(KEY_HOME_RADIUS_METERS, DEFAULT_RADIUS_METERS),
            insideHome = prefs.getBoolean(KEY_INSIDE_HOME, false),
            label = prefs.getString(KEY_HOME_LABEL, null),
            lastDistanceMeters = if (prefs.contains(KEY_LAST_DISTANCE_METERS)) {
                prefs.getFloat(KEY_LAST_DISTANCE_METERS, 0f)
            } else {
                null
            },
            sleepBlockEnabled = prefs.getBoolean(KEY_HOME_SLEEP_BLOCK_ENABLED, false),
            sleepBlockStartMinutes = prefs.getInt(
                KEY_HOME_SLEEP_BLOCK_START_MINUTES,
                DEFAULT_SLEEP_BLOCK_START_MINUTES
            ).coerceIn(0, MINUTES_PER_DAY - 1),
            sleepBlockEndMinutes = prefs.getInt(
                KEY_HOME_SLEEP_BLOCK_END_MINUTES,
                DEFAULT_SLEEP_BLOCK_END_MINUTES
            ).coerceIn(0, MINUTES_PER_DAY - 1)
        )
    }

    fun hasFineLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    fun saveHome(
        context: Context,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        label: String? = null
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong(KEY_HOME_LATITUDE, latitude.toBits())
            putLong(KEY_HOME_LONGITUDE, longitude.toBits())
            putFloat(KEY_HOME_RADIUS_METERS, radiusMeters)
            label?.takeIf { it.isNotBlank() }?.let { putString(KEY_HOME_LABEL, it) }
        }
    }

    fun setHomeRuleEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_HOME_RULE_ENABLED, enabled)
        }

        if (enabled) {
            registerHomeGeofence(context)
            refreshCurrentHomeState(context, startWhenAway = true)
        } else {
            unregisterHomeGeofence(context)
        }
    }

    fun setHomeSleepBlockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_HOME_SLEEP_BLOCK_ENABLED, enabled)
        }
        refreshCurrentHomeState(context, startWhenAway = true)
        enforceBlockedPackageSuspension(context)
    }

    fun setHomeSleepBlockWindow(context: Context, startMinutes: Int, endMinutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_HOME_SLEEP_BLOCK_START_MINUTES, normalizeMinutes(startMinutes))
            putInt(KEY_HOME_SLEEP_BLOCK_END_MINUTES, normalizeMinutes(endMinutes))
        }
        refreshCurrentHomeState(context, startWhenAway = true)
        enforceBlockedPackageSuspension(context)
    }

    fun registerHomeGeofence(context: Context) {
        val currentState = state(context)
        val latitude = currentState.latitude ?: return
        val longitude = currentState.longitude ?: return

        if (!hasFineLocationPermission(context) || !hasBackgroundLocationPermission(context)) {
            Log.w(TAG, "Location permission missing; cannot register home geofence.")
            return
        }

        val geofence = Geofence.Builder()
            .setRequestId(HOME_GEOFENCE_ID)
            .setCircularRegion(latitude, longitude, currentState.radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofence(geofence)
            .build()

        LocationServices.getGeofencingClient(context)
            .addGeofences(request, geofencePendingIntent(context))
            .addOnFailureListener { Log.w(TAG, "Unable to register home geofence", it) }
    }

    fun unregisterHomeGeofence(context: Context) {
        LocationServices.getGeofencingClient(context)
            .removeGeofences(geofencePendingIntent(context))
            .addOnFailureListener { Log.w(TAG, "Unable to remove home geofence", it) }
    }

    fun applyHomePolicy(context: Context, insideHome: Boolean) {
        val currentState = state(context)
        if (!currentState.enabled) return

        applyPresence(context, insideHome, startWhenAway = true)
    }

    fun refreshCurrentHomeState(
        context: Context,
        startWhenAway: Boolean,
        onComplete: ((HomeDistanceState) -> Unit)? = null
    ) {
        val currentState = state(context)
        val latitude = currentState.latitude
        val longitude = currentState.longitude
        if (latitude == null || longitude == null || !hasFineLocationPermission(context)) {
            onComplete?.invoke(
                HomeDistanceState(
                    hasHome = latitude != null && longitude != null,
                    distanceMeters = currentState.lastDistanceMeters,
                    radiusMeters = currentState.radiusMeters,
                    insideHome = if (currentState.latitude != null) currentState.insideHome else null
                )
            )
            return
        }

        val client = LocationServices.getFusedLocationProviderClient(context)
        client.getCurrentLocation(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            CancellationTokenSource().token
        ).addOnSuccessListener { location ->
            val distanceMeters = if (location == null) {
                currentState.lastDistanceMeters
            } else {
                distanceToHomeMeters(currentState, location.latitude, location.longitude)
            }
            val insideHome = distanceMeters?.let { it <= currentState.radiusMeters }

            if (distanceMeters != null && insideHome != null) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                    putFloat(KEY_LAST_DISTANCE_METERS, distanceMeters)
                    putBoolean(KEY_INSIDE_HOME, insideHome)
                }
                if (currentState.enabled) {
                    applyPresence(context, insideHome, startWhenAway)
                }
            }

            onComplete?.invoke(
                HomeDistanceState(
                    hasHome = true,
                    distanceMeters = distanceMeters,
                    radiusMeters = currentState.radiusMeters,
                    insideHome = insideHome
                )
            )
        }.addOnFailureListener {
            Log.w(TAG, "Unable to refresh current home distance", it)
            onComplete?.invoke(
                HomeDistanceState(
                    hasHome = true,
                    distanceMeters = currentState.lastDistanceMeters,
                    radiusMeters = currentState.radiusMeters,
                    insideHome = currentState.insideHome
                )
            )
        }
    }

    fun rememberAllowedHomeUnlock(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_HOME_ALLOWED_PACKAGE, packageName)
            putLong(KEY_HOME_ALLOWED_UNTIL_MS, System.currentTimeMillis() + HOME_ALLOWED_GRACE_MS)
        }
    }

    fun isTemporarilyAllowedAtHome(context: Context, packageName: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val allowedPackage = prefs.getString(KEY_HOME_ALLOWED_PACKAGE, null)
        val allowedUntilMs = prefs.getLong(KEY_HOME_ALLOWED_UNTIL_MS, 0L)
        return shouldAllowBlockedAppsAtHome(context) &&
            allowedPackage == packageName &&
            System.currentTimeMillis() < allowedUntilMs
    }

    fun shouldAllowBlockedAppsAtHome(context: Context): Boolean {
        val currentState = state(context)
        return currentState.enabled &&
            currentState.insideHome &&
            !isHomeSleepBlockActive(currentState)
    }

    fun canRemoveBlockedItems(context: Context): Boolean = canRemoveBlockedItems(state(context))

    internal fun canRemoveBlockedItems(state: HomeRuleState): Boolean =
        state.enabled &&
            state.latitude != null &&
            state.longitude != null &&
            state.insideHome

    fun shouldSuspendBlockedPackages(context: Context): Boolean = !shouldAllowBlockedAppsAtHome(context)

    fun isHomeSleepBlockActive(context: Context): Boolean = isHomeSleepBlockActive(state(context))

    fun formatMinutes(minutes: Int): String {
        val normalizedMinutes = normalizeMinutes(minutes)
        val hour24 = normalizedMinutes / 60
        val minute = normalizedMinutes % 60
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        val suffix = if (hour24 < 12) "AM" else "PM"
        return "%d:%02d %s".format(hour12, minute, suffix)
    }

    private fun isHomeSleepBlockActive(state: HomeRuleState): Boolean {
        if (!state.sleepBlockEnabled) return false
        val startMinutes = normalizeMinutes(state.sleepBlockStartMinutes)
        val endMinutes = normalizeMinutes(state.sleepBlockEndMinutes)
        if (startMinutes == endMinutes) return false

        val calendar = Calendar.getInstance()
        val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        return isMinuteInSleepBlockWindow(currentMinutes, startMinutes, endMinutes)
    }

    internal fun isMinuteInSleepBlockWindow(currentMinutes: Int, startMinutes: Int, endMinutes: Int): Boolean {
        val normalizedCurrentMinutes = normalizeMinutes(currentMinutes)
        val normalizedStartMinutes = normalizeMinutes(startMinutes)
        val normalizedEndMinutes = normalizeMinutes(endMinutes)
        if (normalizedStartMinutes == normalizedEndMinutes) return false

        return if (normalizedStartMinutes < normalizedEndMinutes) {
            normalizedCurrentMinutes >= normalizedStartMinutes &&
                normalizedCurrentMinutes < normalizedEndMinutes
        } else {
            normalizedCurrentMinutes >= normalizedStartMinutes ||
                normalizedCurrentMinutes < normalizedEndMinutes
        }
    }

    internal fun normalizeMinutes(minutes: Int): Int {
        val remainder = minutes % MINUTES_PER_DAY
        return if (remainder < 0) remainder + MINUTES_PER_DAY else remainder
    }

    suspend fun refreshCurrentHomeStateNow(
        context: Context,
        startWhenAway: Boolean
    ): HomeDistanceState = suspendCancellableCoroutine { continuation ->
        refreshCurrentHomeState(context, startWhenAway) { distanceState ->
            if (continuation.isActive) {
                continuation.resume(distanceState)
            }
        }
    }

    private fun applyPresence(context: Context, insideHome: Boolean, startWhenAway: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_INSIDE_HOME, insideHome)
        }

        if (startWhenAway) {
            startMonitoringService(context)
        }

        enforceBlockedPackageSuspension(context)
    }

    private fun enforceBlockedPackageSuspension(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val blockedPackages = AppDatabase.getDatabase(context)
                .blockedAppDao()
                .getAllBlockedApps()
                .first()
            val pausedAtHome = shouldAllowBlockedAppsAtHome(context)
            val homeAwarePackages = blockedPackages
                .filterNot { BlockMode.isAlways(it.blockMode) }
                .map { it.packageName }
                .toSet()
            val activePackages = blockedPackages
                .filter { !pausedAtHome || BlockMode.isAlways(it.blockMode) }
                .map { it.packageName }
                .toSet()
            if (pausedAtHome) {
                HardModeManager.setBlockedPackagesSuspended(
                    context,
                    homeAwarePackages,
                    suspended = false
                )
            }
            HardModeManager.setBlockedPackagesSuspended(
                context,
                activePackages,
                suspended = true
            )
        }
    }

    private fun distanceToHomeMeters(state: HomeRuleState, latitude: Double, longitude: Double): Float? {
        val homeLatitude = state.latitude ?: return null
        val homeLongitude = state.longitude ?: return null
        val results = FloatArray(1)
        Location.distanceBetween(latitude, longitude, homeLatitude, homeLongitude, results)
        return results[0]
    }

    private fun geofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, LocationTransitionReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, 3001, intent, flags)
    }
}
