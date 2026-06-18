package com.cj.tapblok

import app.untethered.BuildConfig
import app.untethered.R

import android.app.Application
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.cj.tapblok.database.BlockMode
import com.cj.tapblok.database.BlockedWebsite
import com.cj.tapblok.database.BlockedWebsiteDao
import com.cj.tapblok.ui.theme.TapBlokTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class WebsiteRulesViewModel(
    private val blockedWebsiteDao: BlockedWebsiteDao,
    private val application: Application
) : ViewModel() {
    private val _websites = MutableStateFlow<List<BlockedWebsite>>(emptyList())
    val websites: StateFlow<List<BlockedWebsite>> = _websites

    private val _inputError = MutableStateFlow<String?>(null)
    val inputError: StateFlow<String?> = _inputError

    init {
        viewModelScope.launch(Dispatchers.IO) {
            blockedWebsiteDao.getAllBlockedWebsites().collect {
                _websites.value = it
            }
        }
    }

    fun addWebsite(input: String, blockMode: String): Boolean {
        val domain = WebsiteDomainRules.normalizeDomain(input)
        if (domain == null) {
            _inputError.value = "Enter a full domain, like youtube.com"
            return false
        }

        viewModelScope.launch(Dispatchers.IO) {
            blockedWebsiteDao.insert(BlockedWebsite(domain, blockMode))
        }
        _inputError.value = null
        return true
    }

    fun updateWebsiteBlockMode(website: BlockedWebsite, blockMode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            blockedWebsiteDao.updateBlockMode(website.domain, blockMode)
        }
    }

    fun deleteWebsite(website: BlockedWebsite): Boolean {
        if (!LocationRuleManager.canRemoveBlockedItems(application)) {
            return false
        }

        viewModelScope.launch(Dispatchers.IO) {
            blockedWebsiteDao.delete(website)
        }
        return true
    }
}

class WebsiteRulesViewModelFactory(
    private val blockedWebsiteDao: BlockedWebsiteDao,
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WebsiteRulesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WebsiteRulesViewModel(blockedWebsiteDao, application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class WebsiteRulesActivity : ComponentActivity() {
    private val viewModel: WebsiteRulesViewModel by viewModels {
        WebsiteRulesViewModelFactory(
            (application as App).database.blockedWebsiteDao(),
            application
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TapBlokTheme {
                val context = LocalContext.current
                val focusManager = LocalFocusManager.current
                val keyboardController = LocalSoftwareKeyboardController.current
                val lifecycleOwner = LocalLifecycleOwner.current
                val websites by viewModel.websites.collectAsState()
                val inputError by viewModel.inputError.collectAsState()
                var vpnReady by remember { mutableStateOf(VpnService.prepare(context) == null) }
                var websiteBlockingEnabled by remember { mutableStateOf(WebsiteBlockVpnService.isEnabled(context)) }
                var externalVpnActive by remember { mutableStateOf(WebsiteBlockVpnService.hasExternalVpn(context)) }
                var domainInput by remember { mutableStateOf("") }
                var newWebsiteBlockMode by remember { mutableStateOf(BlockMode.HOME_AWARE) }
                var canRemoveBlockedItems by remember {
                    mutableStateOf(LocationRuleManager.canRemoveBlockedItems(context))
                }

                fun refreshRemovalState() {
                    canRemoveBlockedItems = LocationRuleManager.canRemoveBlockedItems(context)
                    LocationRuleManager.refreshCurrentHomeState(context, startWhenAway = false) {
                        canRemoveBlockedItems = LocationRuleManager.canRemoveBlockedItems(context)
                    }
                }

                fun refreshWebsiteVpnState() {
                    vpnReady = VpnService.prepare(context) == null
                    websiteBlockingEnabled = WebsiteBlockVpnService.isEnabled(context)
                    externalVpnActive = WebsiteBlockVpnService.hasExternalVpn(context)
                }

                val vpnPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    refreshWebsiteVpnState()
                    if (vpnReady && websiteBlockingEnabled && AppMonitoringService.isRunning) {
                        WebsiteBlockVpnService.startIfAllowed(context)
                        refreshWebsiteVpnState()
                    }
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            refreshWebsiteVpnState()
                            refreshRemovalState()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = "Blocked Websites",
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
                    WebsiteRulesScreen(
                        websites = websites,
                        domainInput = domainInput,
                        inputError = inputError,
                        newWebsiteBlockMode = newWebsiteBlockMode,
                        canRemoveBlockedItems = canRemoveBlockedItems,
                        websiteBlockingEnabled = websiteBlockingEnabled,
                        externalVpnActive = externalVpnActive,
                        vpnReady = vpnReady,
                        monitoringActive = AppMonitoringService.isRunning,
                        onDomainInputChanged = { domainInput = it },
                        onNewWebsiteBlockModeChanged = { newWebsiteBlockMode = it },
                        onWebsiteBlockingEnabledChanged = { enabled ->
                            WebsiteBlockVpnService.setEnabled(context, enabled)
                            refreshWebsiteVpnState()
                            if (enabled && AppMonitoringService.isRunning) {
                                val status = WebsiteBlockVpnService.startIfAllowed(context)
                                if (status == WebsiteVpnStartStatus.EXTERNAL_VPN_ACTIVE) {
                                    Toast.makeText(
                                        context,
                                        "Another VPN is active. Website blocking will stay off.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                refreshWebsiteVpnState()
                            }
                        },
                        onAddWebsite = {
                            if (viewModel.addWebsite(domainInput, newWebsiteBlockMode)) {
                                domainInput = ""
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                if (websiteBlockingEnabled && vpnReady && AppMonitoringService.isRunning) {
                                    WebsiteBlockVpnService.startIfAllowed(context)
                                    refreshWebsiteVpnState()
                                }
                            }
                        },
                        onDeleteWebsite = { website ->
                            if (!viewModel.deleteWebsite(website)) {
                                Toast.makeText(
                                    context,
                                    "Go home to remove blocked websites.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onWebsiteBlockModeChange = viewModel::updateWebsiteBlockMode,
                        onRequestVpn = {
                            val intent = VpnService.prepare(context)
                            if (intent != null) {
                                vpnPermissionLauncher.launch(intent)
                            } else {
                                vpnReady = true
                                WebsiteBlockVpnService.setEnabled(context, true)
                                refreshWebsiteVpnState()
                                Toast.makeText(context, "Website blocking is ready.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }
}

@Composable
fun WebsiteRulesScreen(
    websites: List<BlockedWebsite>,
    domainInput: String,
    inputError: String?,
    newWebsiteBlockMode: String,
    canRemoveBlockedItems: Boolean,
    websiteBlockingEnabled: Boolean,
    externalVpnActive: Boolean,
    vpnReady: Boolean,
    monitoringActive: Boolean,
    onDomainInputChanged: (String) -> Unit,
    onNewWebsiteBlockModeChanged: (String) -> Unit,
    onWebsiteBlockingEnabledChanged: (Boolean) -> Unit,
    onAddWebsite: () -> Unit,
    onDeleteWebsite: (BlockedWebsite) -> Unit,
    onWebsiteBlockModeChange: (BlockedWebsite, String) -> Unit,
    onRequestVpn: () -> Unit,
    modifier: Modifier = Modifier
) {
    var modeEditorWebsite by remember { mutableStateOf<BlockedWebsite?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    !websiteBlockingEnabled -> "Website blocking off"
                                    externalVpnActive -> "Another VPN is active"
                                    vpnReady -> "Website blocking ready"
                                    else -> "VPN permission needed"
                                },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = when {
                                    !websiteBlockingEnabled -> "Apps still block. Tailscale can keep the VPN slot."
                                    externalVpnActive -> "Turn off Tailscale or keep website blocking off."
                                    !vpnReady -> "Needed before websites can be blocked"
                                    monitoringActive -> "Active during this monitoring session"
                                    else -> "Starts with your next monitoring session"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Website blocking uses Android's VPN slot, so it cannot run with Tailscale.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = websiteBlockingEnabled,
                            onCheckedChange = onWebsiteBlockingEnabledChanged
                        )
                    }
                    if (websiteBlockingEnabled && !vpnReady && !externalVpnActive) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onRequestVpn
                        ) {
                            Text("Allow Website Blocking")
                        }
                    }
                }
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = onDomainInputChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Domain") },
                        placeholder = { Text("youtube.com") },
                        singleLine = true,
                        isError = inputError != null,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            keyboardType = KeyboardType.Uri
                        )
                    )
                    inputError?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        text = "Block mode",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = newWebsiteBlockMode != BlockMode.ALWAYS,
                            onClick = { onNewWebsiteBlockModeChanged(BlockMode.HOME_AWARE) },
                            label = { Text("Home-aware") }
                        )
                        FilterChip(
                            selected = newWebsiteBlockMode == BlockMode.ALWAYS,
                            onClick = { onNewWebsiteBlockModeChanged(BlockMode.ALWAYS) },
                            label = { Text("Always") }
                        )
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onAddWebsite
                    ) {
                        Text("Add Website")
                    }
                }
            }
        }

        if (websites.isEmpty()) {
            item {
                Text(
                    text = "No blocked websites yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
                )
            }
        } else {
            if (!canRemoveBlockedItems) {
                item {
                    Text(
                        text = "Removal locked until you are home.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            }

            items(websites, key = { it.domain }) { website ->
                WebsiteListItem(
                    website = website,
                    canRemoveBlockedItems = canRemoveBlockedItems,
                    onModeClick = { modeEditorWebsite = website },
                    onDelete = { onDeleteWebsite(website) }
                )
            }
        }
    }

    modeEditorWebsite?.let { website ->
        BlockModePickerSheet(
            currentMode = website.blockMode,
            onSelect = { blockMode ->
                onWebsiteBlockModeChange(website, blockMode)
                modeEditorWebsite = null
            },
            onDismiss = { modeEditorWebsite = null }
        )
    }
}

@Composable
private fun WebsiteListItem(
    website: BlockedWebsite,
    canRemoveBlockedItems: Boolean,
    onModeClick: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = false, onClick = {})
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Language,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = website.domain,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            BlockModePill(
                mode = website.blockMode,
                onClick = onModeClick
            )
        }
        IconButton(
            onClick = {
                if (canRemoveBlockedItems) {
                    onDelete()
                } else {
                    Toast.makeText(
                        context,
                        "Go home to remove blocked websites.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        ) {
            Icon(Icons.Default.Delete, contentDescription = "Remove website")
        }
    }
}
