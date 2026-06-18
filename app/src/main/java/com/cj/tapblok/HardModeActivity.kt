package com.cj.tapblok

import app.untethered.BuildConfig
import app.untethered.R

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cj.tapblok.ui.theme.TapBlokTheme

class HardModeActivity : ComponentActivity() {
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
                                    text = "Hard Mode",
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
                    HardModeScreen(modifier = Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun HardModeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var isDeviceOwner by remember { mutableStateOf(HardModeManager.isDeviceOwner(context)) }

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
                Icon(Icons.Default.Lock, contentDescription = null)
                Text(
                    text = if (isDeviceOwner) "Device Owner Active" else "Device Owner Not Active",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = if (isDeviceOwner) {
                        "Untethered can block uninstall and suspend selected apps while a block is active."
                    } else {
                        "Normal Android apps can be uninstalled, force-stopped, or stripped of permissions. Hard Mode requires provisioning Untethered as the device owner with ADB."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Provision Command", style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Text(
                        "adb shell dpm set-device-owner\n${BuildConfig.APPLICATION_ID}/com.cj.tapblok.TapBlokDeviceAdminReceiver",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    "This usually requires removing other device-owner/profile-owner apps first, and may require a freshly reset device depending on Android policy state.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isDeviceOwner) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val blocked = HardModeManager.setSelfUninstallBlocked(context, true)
                    Toast.makeText(
                        context,
                        if (blocked) "Untethered uninstall is blocked." else "Could not block uninstall.",
                        Toast.LENGTH_SHORT
                    ).show()
                    isDeviceOwner = HardModeManager.isDeviceOwner(context)
                }
            ) {
                Text("Block Untethered Uninstall")
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val unblocked = HardModeManager.setSelfUninstallBlocked(context, false)
                    Toast.makeText(
                        context,
                        if (unblocked) "Untethered uninstall is allowed." else "Could not allow uninstall.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            ) {
                Text("Allow Untethered Uninstall")
            }
        }
    }
}
