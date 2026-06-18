package app.untethered.helper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.cj.tapblok.LocationRuleManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

object UvIndexManager {
    private data class Coordinates(val latitude: Double, val longitude: Double)

    suspend fun currentUvIndex(context: Context): Double? {
        val appContext = context.applicationContext
        return runCatching {
            val coordinates = resolveCoordinates(appContext) ?: return null
            withContext(Dispatchers.IO) {
                fetchUvIndex(coordinates)
            }
        }.getOrNull()
    }

    private suspend fun resolveCoordinates(context: Context): Coordinates? {
        if (hasForegroundLocationPermission(context)) {
            val deviceLocation = withTimeoutOrNull(1_500) {
                lastLocation(context)
            } ?: withTimeoutOrNull(2_500) {
                currentLocation(context)
            }
            if (deviceLocation != null) {
                return Coordinates(deviceLocation.latitude, deviceLocation.longitude)
            }
        }

        val homeState = LocationRuleManager.state(context)
        val latitude = homeState.latitude
        val longitude = homeState.longitude
        if (latitude != null && longitude != null) {
            return Coordinates(latitude, longitude)
        }

        return withContext(Dispatchers.IO) {
            fetchIpCoordinates()
        }
    }

    private fun hasForegroundLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(context: Context): Location? =
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationTokenSource()
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellation.token)
                .addOnSuccessListener { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
                .addOnCanceledListener {
                    if (continuation.isActive) continuation.resume(null)
                }
            continuation.invokeOnCancellation { cancellation.cancel() }
        }

    @SuppressLint("MissingPermission")
    private suspend fun lastLocation(context: Context): Location? =
        suspendCancellableCoroutine { continuation ->
            LocationServices.getFusedLocationProviderClient(context)
                .lastLocation
                .addOnSuccessListener { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
                .addOnCanceledListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        }

    private fun fetchUvIndex(coordinates: Coordinates): Double? {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${coordinates.latitude}" +
                "&longitude=${coordinates.longitude}" +
                "&current=uv_index" +
                "&forecast_days=1" +
                "&timezone=auto"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
        }

        return try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
                .optJSONObject("current")
                ?.optDouble("uv_index", Double.NaN)
                ?.takeIf { it.isFinite() }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchIpCoordinates(): Coordinates? {
        val connection = (URL("https://ipwho.is/").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
        }

        return try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (!json.optBoolean("success", true)) return null
            val latitude = json.optDouble("latitude", Double.NaN)
            val longitude = json.optDouble("longitude", Double.NaN)
            if (latitude.isFinite() && longitude.isFinite()) Coordinates(latitude, longitude) else null
        } finally {
            connection.disconnect()
        }
    }
}
