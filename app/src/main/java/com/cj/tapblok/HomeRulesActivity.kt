package com.cj.tapblok

import app.olauncher.BuildConfig
import app.olauncher.R

import android.Manifest
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cj.tapblok.ui.theme.TapBlokTheme
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import java.util.TimeZone

class HomeRulesActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TapBlokTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = "Home Rules",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        )
                    }
                ) { padding ->
                    HomeRulesScreen(modifier = Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun HomeRulesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(LocationRuleManager.state(context)) }
    var radiusMeters by remember { mutableStateOf(state.radiusMeters) }
    var hasFineLocation by remember { mutableStateOf(LocationRuleManager.hasFineLocationPermission(context)) }
    var hasBackgroundLocation by remember { mutableStateOf(LocationRuleManager.hasBackgroundLocationPermission(context)) }
    var isLocating by remember { mutableStateOf(false) }
    var addressText by remember { mutableStateOf(state.label.orEmpty()) }
    var isSavingAddress by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val fineLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasFineLocation = granted
        hasBackgroundLocation = LocationRuleManager.hasBackgroundLocationPermission(context)
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasBackgroundLocation = granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Home, contentDescription = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Home Unlock", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Selected apps work at home and stay blocked everywhere else.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.enabled,
                        enabled = state.latitude != null && hasFineLocation && hasBackgroundLocation,
                        onCheckedChange = { enabled ->
                            LocationRuleManager.setHomeRuleEnabled(context, enabled)
                            state = LocationRuleManager.state(context)
                        }
                    )
                }

                Text(
                    text = when {
                        state.latitude == null -> "No home location saved."
                        state.insideHome && LocationRuleManager.isHomeSleepBlockActive(context) ->
                            "At home: selected apps are blocked until ${LocationRuleManager.formatMinutes(state.sleepBlockEndMinutes)}."
                        state.insideHome -> "At home: selected apps are available."
                        else -> "Away: selected apps are blocked."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Night Block at Home", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Block selected apps at home during your sleep window.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.sleepBlockEnabled,
                        onCheckedChange = { enabled ->
                            LocationRuleManager.setHomeSleepBlockEnabled(context, enabled)
                            state = LocationRuleManager.state(context)
                        }
                    )
                }

                Text(
                    text = if (state.sleepBlockEnabled) {
                        "Active from ${LocationRuleManager.formatMinutes(state.sleepBlockStartMinutes)} to ${LocationRuleManager.formatMinutes(state.sleepBlockEndMinutes)} using ${TimeZone.getDefault().id}."
                    } else {
                        "Off. Home unlock works all day when you are inside the home radius."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TimeWindowAdjuster(
                    label = "Start",
                    minutes = state.sleepBlockStartMinutes,
                    onChange = { minutes ->
                        LocationRuleManager.setHomeSleepBlockWindow(
                            context,
                            startMinutes = minutes,
                            endMinutes = state.sleepBlockEndMinutes
                        )
                        state = LocationRuleManager.state(context)
                    }
                )

                TimeWindowAdjuster(
                    label = "End",
                    minutes = state.sleepBlockEndMinutes,
                    onChange = { minutes ->
                        LocationRuleManager.setHomeSleepBlockWindow(
                            context,
                            startMinutes = state.sleepBlockStartMinutes,
                            endMinutes = minutes
                        )
                        state = LocationRuleManager.state(context)
                    }
                )
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Home Area", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (state.latitude == null) {
                        "Save your current location or enter a home address."
                    } else {
                        state.label ?: "Saved at %.5f, %.5f".format(state.latitude, state.longitude)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Radius: ${radiusMeters.toInt()} m", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = radiusMeters,
                    onValueChange = { radiusMeters = it },
                    valueRange = 75f..500f,
                    steps = 16
                )

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = hasFineLocation && !isLocating,
                    onClick = {
                        isLocating = true
                        saveCurrentLocationAsHome(
                            context = context,
                            radiusMeters = radiusMeters,
                            onComplete = {
                                isLocating = false
                                state = LocationRuleManager.state(context)
                            }
                        )
                    }
                ) {
                    Text(if (isLocating) "Locating..." else "Use Current Location")
                }

                OutlinedTextField(
                    value = addressText,
                    onValueChange = { addressText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Home address") },
                    singleLine = false,
                    minLines = 2
                )

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = addressText.isNotBlank() && !isSavingAddress,
                    onClick = {
                        isSavingAddress = true
                        scope.launch {
                            saveAddressAsHome(
                                context = context,
                                address = addressText,
                                radiusMeters = radiusMeters
                            )
                            isSavingAddress = false
                            state = LocationRuleManager.state(context)
                        }
                    }
                ) {
                    Text(if (isSavingAddress) "Saving Address..." else "Save Address as Home")
                }

                if (!hasFineLocation) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { fineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                    ) {
                        Text("Grant Location")
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = hasFineLocation,
                        onClick = { backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                    ) {
                        Text("Grant Background Location")
                    }
                }

                if (state.enabled) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            LocationRuleManager.registerHomeGeofence(context)
                            Toast.makeText(context, "Home geofence refreshed.", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Refresh Geofence")
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeWindowAdjuster(
    label: String,
    minutes: Int,
    onChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(onClick = { onChange(minutes - 15) }) {
            Text("-15")
        }
        Text(
            text = LocationRuleManager.formatMinutes(minutes),
            modifier = Modifier.weight(1.4f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1
        )
        OutlinedButton(onClick = { onChange(minutes + 15) }) {
            Text("+15")
        }
    }
}

@RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
private fun saveCurrentLocationAsHome(
    context: android.content.Context,
    radiusMeters: Float,
    onComplete: () -> Unit
) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    client.getCurrentLocation(
        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
        CancellationTokenSource().token
    ).addOnSuccessListener { location ->
        if (location == null) {
            Toast.makeText(context, "Could not get current location.", Toast.LENGTH_SHORT).show()
        } else {
            LocationRuleManager.saveHome(context, location.latitude, location.longitude, radiusMeters)
            LocationRuleManager.registerHomeGeofence(context)
            LocationRuleManager.refreshCurrentHomeState(context, startWhenAway = true)
            Toast.makeText(context, "Home location saved.", Toast.LENGTH_SHORT).show()
        }
        onComplete()
    }.addOnFailureListener {
        Toast.makeText(context, "Location lookup failed.", Toast.LENGTH_SHORT).show()
        onComplete()
    }
}

private suspend fun saveAddressAsHome(
    context: android.content.Context,
    address: String,
    radiusMeters: Float
) {
    val result = withContext(Dispatchers.IO) {
        runCatching {
            val geocoder = Geocoder(context, Locale.US)
            @Suppress("DEPRECATION")
            geocoder.getFromLocationName(address, 1)?.firstOrNull()
        }
    }

    val location = result.getOrNull()
    if (location == null) {
        val message = if (result.exceptionOrNull() is IOException) {
            "Address lookup failed. Check network and try again."
        } else {
            "Could not find that address."
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        return
    }

    LocationRuleManager.saveHome(
        context = context,
        latitude = location.latitude,
        longitude = location.longitude,
        radiusMeters = radiusMeters,
        label = address.trim()
    )
    LocationRuleManager.registerHomeGeofence(context)
    LocationRuleManager.refreshCurrentHomeState(context, startWhenAway = true)
    Toast.makeText(context, "Home address saved.", Toast.LENGTH_SHORT).show()
}
