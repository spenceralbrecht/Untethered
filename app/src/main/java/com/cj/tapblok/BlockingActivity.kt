package com.cj.tapblok

import app.olauncher.BuildConfig
import app.olauncher.R

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.cj.tapblok.ui.theme.TapBlokTheme

class BlockingActivity : ComponentActivity() {
    private var blockedPackageName by mutableStateOf("An app")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        blockedPackageName = intent.blockedPackageName
        currentActivity = this

        val goHome = {
            BlockingOverlayController.goHome(this, blockedPackageName)
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goHome() }
        })

        setContent {
            TapBlokTheme {
                BlockingScreen(
                    packageName = blockedPackageName,
                    onGoHomeClick = goHome,
                    onAllowedHome = { allowedPackageName ->
                        BlockingOverlayController.hideIfShowing(allowedPackageName)
                        if (blockedPackageName == allowedPackageName) {
                            finish()
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        blockedPackageName = intent.blockedPackageName
    }

    override fun onDestroy() {
        if (currentActivity === this) currentActivity = null
        super.onDestroy()
    }

    private val Intent.blockedPackageName: String
        get() = getStringExtra("BLOCKED_APP_PACKAGE_NAME") ?: "An app"

    companion object {
        @Volatile
        private var currentActivity: BlockingActivity? = null

        fun finishIfShowing(packageName: String) {
            currentActivity?.runOnUiThread {
                val activity = currentActivity ?: return@runOnUiThread
                if (activity.blockedPackageName == packageName) {
                    activity.finish()
                }
            }
        }
    }
}

@Composable
fun BlockingScreen(
    packageName: String,
    onGoHomeClick: () -> Unit,
    onAllowedHome: (String) -> Unit
) {
    val context = LocalContext.current

    var appName by remember { mutableStateOf(packageName) }
    var appIcon by remember { mutableStateOf<Drawable?>(null) }
    var homeDistanceText by remember { mutableStateOf<String?>(null) }
    var isCheckingHome by remember { mutableStateOf(false) }
    var hasLocationPermission by remember {
        mutableStateOf(LocationRuleManager.hasFineLocationPermission(context))
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasLocationPermission = granted
        homeDistanceText = if (granted) {
            "Distance from home: checking..."
        } else {
            "Distance from home: location permission needed"
        }
    }

    LaunchedEffect(key1 = packageName) {
        val pm = context.packageManager
        appName = packageName
        appIcon = null
        try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            appName = pm.getApplicationLabel(appInfo).toString()
            appIcon = pm.getApplicationIcon(appInfo)
        } catch (_: PackageManager.NameNotFoundException) {
            appName = packageName
        }
    }

    LaunchedEffect(key1 = packageName, key2 = hasLocationPermission) {
        val homeState = LocationRuleManager.state(context)
        val canCheckHome = homeState.enabled &&
            homeState.latitude != null &&
            homeState.longitude != null &&
            hasLocationPermission

        if (canCheckHome) {
            isCheckingHome = true
            homeDistanceText = "Checking distance to home..."
            LocationRuleManager.refreshCurrentHomeState(context, startWhenAway = false) { distanceState ->
                if (distanceState.insideHome == true && LocationRuleManager.shouldAllowBlockedAppsAtHome(context)) {
                    LocationRuleManager.rememberAllowedHomeUnlock(context, packageName)
                    onAllowedHome(packageName)
                } else {
                    isCheckingHome = false
                    homeDistanceText = formatHomeDistance(distanceState)
                }
            }
        } else {
            isCheckingHome = false
            homeDistanceText = when {
                !hasLocationPermission -> "Distance from home: location permission needed"
                homeState.latitude == null || homeState.longitude == null -> "Distance from home: no home saved"
                !homeState.enabled -> "Distance from home: home rule off"
                else -> "Distance from home: unavailable"
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isCheckingHome) {
                HomeDistanceCheckAnimation()
            } else {
                BlockedAppIcon(appName = appName, appIcon = appIcon)
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "BLOCKED",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = appName,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (isCheckingHome) {
                Text(
                    text = "Checking distance to home",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Confirming whether you're inside your home radius.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = "Tap your NFC tag or scan your QR code to unlock.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            if (!isCheckingHome) homeDistanceText?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            if (!isCheckingHome && !hasLocationPermission) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Grant Location")
                }
            }

            if (isCheckingHome) return@Column

            Spacer(modifier = Modifier.height(if (hasLocationPermission) 48.dp else 12.dp))

            Button(
                onClick = onGoHomeClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Go Home")
            }
        }
    }
}

@Composable
private fun BlockedAppIcon(appName: String, appIcon: Drawable?) {
    Box(contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(20.dp))
        ) {
            Image(
                painter = rememberAsyncImagePainter(model = appIcon),
                contentDescription = "$appName icon",
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
private fun HomeDistanceCheckAnimation() {
    val transition = rememberInfiniteTransition(label = "home-distance-check")
    val pulseScale by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "home-distance-pulse-scale"
    )
    val outerPulseScale by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "home-distance-outer-pulse-scale"
    )

    Box(
        modifier = Modifier.size(112.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .scale(outerPulseScale)
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    shape = CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(88.dp)
                .scale(pulseScale)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape
                )
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MyLocation,
                contentDescription = null,
                modifier = Modifier.size(38.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatHomeDistance(distanceState: HomeDistanceState): String =
    when {
        !distanceState.hasHome -> "Distance from home: no home saved"
        distanceState.distanceMeters == null -> "Distance from home: unavailable"
        distanceState.distanceMeters < 1000f ->
            "Distance from home: ${distanceState.distanceMeters.toInt()} m (radius ${distanceState.radiusMeters.toInt()} m)"
        else ->
            "Distance from home: %.1f km (radius ${distanceState.radiusMeters.toInt()} m)"
                .format(distanceState.distanceMeters / 1000f)
    }
