package com.cj.tapblok

import app.olauncher.BuildConfig
import app.olauncher.R

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.cj.tapblok.database.BlockMode
import com.cj.tapblok.database.BlockedApp
import com.cj.tapblok.database.BlockedAppDao
import com.cj.tapblok.ui.theme.TapBlokTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AppInfo(
    val appName: String,
    val packageName: String,
    val isSelected: Boolean = false,
    val blockMode: String = BlockMode.HOME_AWARE
)

class AppSelectionViewModel(private val blockedAppDao: BlockedAppDao, private val application: Application) : ViewModel() {

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    init {
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = application.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val allApps = pm.queryIntentActivities(intent, 0)

            val baseAppList = allApps.mapNotNull { app ->
                val packageName = app.activityInfo?.packageName ?: return@mapNotNull null
                if (ProtectedPackages.isProtected(packageName, application.packageName)) {
                    return@mapNotNull null
                }
                try {
                    AppInfo(
                        appName = app.loadLabel(pm).toString(),
                        packageName = packageName
                    )
                } catch (e: Exception) {
                    android.util.Log.w("AppSelectionViewModel", "Skipping $packageName: ${e.message}")
                    null
                }
            }.sortedBy { it.appName.lowercase() }

            blockedAppDao.getAllBlockedApps().collect { blockedApps ->
                val blockedAppModes = blockedApps.associate { it.packageName to it.blockMode }
                _apps.value = baseAppList.map { app ->
                    app.copy(
                        isSelected = blockedAppModes.containsKey(app.packageName),
                        blockMode = blockedAppModes[app.packageName] ?: BlockMode.HOME_AWARE
                    )
                }
            }
        }
    }

    fun onAppSelectionChanged(app: AppInfo, isSelected: Boolean): Boolean {
        if (!isSelected && !LocationRuleManager.canRemoveBlockedItems(application)) {
            return false
        }

        viewModelScope.launch(Dispatchers.IO) {
            if (isSelected) {
                blockedAppDao.insert(BlockedApp(packageName = app.packageName))
            } else {
                blockedAppDao.delete(BlockedApp(packageName = app.packageName))
            }
        }
        return true
    }

    fun onAppBlockModeChanged(app: AppInfo, blockMode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            blockedAppDao.updateBlockMode(app.packageName, blockMode)
        }
    }

    fun selectAllApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val allAppPackages = apps.value.map { BlockedApp(it.packageName) }
            blockedAppDao.insertAll(allAppPackages)
        }
    }

    fun unselectAllApps(): Boolean {
        if (!LocationRuleManager.canRemoveBlockedItems(application)) {
            return false
        }

        viewModelScope.launch(Dispatchers.IO) {
            blockedAppDao.deleteAll()
        }
        return true
    }
}

class AppSelectionViewModelFactory(private val application: Application, private val blockedAppDao: BlockedAppDao) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppSelectionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppSelectionViewModel(blockedAppDao, application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class AppSelectionActivity : ComponentActivity() {
    private val viewModel: AppSelectionViewModel by viewModels {
        AppSelectionViewModelFactory(
            application,
            (application as App).database.blockedAppDao()
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TapBlokTheme {
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current
                val appList by viewModel.apps.collectAsState()
                var canRemoveBlockedItems by remember {
                    mutableStateOf(LocationRuleManager.canRemoveBlockedItems(context))
                }

                fun refreshRemovalState() {
                    canRemoveBlockedItems = LocationRuleManager.canRemoveBlockedItems(context)
                    LocationRuleManager.refreshCurrentHomeState(context, startWhenAway = false) {
                        canRemoveBlockedItems = LocationRuleManager.canRemoveBlockedItems(context)
                    }
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
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
                                    text = "Blocked Apps",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            },
                            actions = {
                                IconButton(onClick = { viewModel.selectAllApps() }) {
                                    Icon(Icons.Default.Checklist, contentDescription = "Select all apps")
                                }
                                IconButton(
                                    onClick = {
                                        if (!viewModel.unselectAllApps()) {
                                            Toast.makeText(
                                                context,
                                                "Go home to remove blocked apps.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.RemoveDone, contentDescription = "Clear selected apps")
                                }
                            }
                        )
                    }
                ) { padding ->
                    AppSelectionScreen(
                        apps = appList,
                        canRemoveBlockedItems = canRemoveBlockedItems,
                        onAppCheckedChange = { app, isSelected ->
                            if (!viewModel.onAppSelectionChanged(app, isSelected)) {
                                Toast.makeText(
                                    context,
                                    "Go home to remove blocked apps.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onAppBlockModeChange = { app, blockMode ->
                            viewModel.onAppBlockModeChanged(app, blockMode)
                        },
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }
}

@Composable
fun AppSelectionScreen(
    apps: List<AppInfo>,
    canRemoveBlockedItems: Boolean,
    onAppCheckedChange: (AppInfo, Boolean) -> Unit,
    onAppBlockModeChange: (AppInfo, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var modeEditorApp by remember { mutableStateOf<AppInfo?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (!canRemoveBlockedItems && apps.any { it.isSelected }) {
            item {
                Text(
                    text = "Removal locked until you are home.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                )
            }
        }

        items(apps, key = { it.packageName }) { app ->
            AppListItem(
                app = app,
                canRemoveBlockedItems = canRemoveBlockedItems,
                onCheckedChange = { isSelected ->
                    onAppCheckedChange(app, isSelected)
                },
                onModeClick = {
                    modeEditorApp = app
                }
            )
        }
    }

    modeEditorApp?.let { app ->
        BlockModePickerSheet(
            currentMode = app.blockMode,
            onSelect = { blockMode ->
                onAppBlockModeChange(app, blockMode)
                modeEditorApp = null
            },
            onDismiss = { modeEditorApp = null }
        )
    }
}

@Composable
fun AppListItem(
    app: AppInfo,
    canRemoveBlockedItems: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onModeClick: () -> Unit
) {
    val context = LocalContext.current
    val removalLocked = app.isSelected && !canRemoveBlockedItems
    fun handleCheckedChange(isSelected: Boolean) {
        if (removalLocked && !isSelected) {
            Toast.makeText(context, "Go home to remove blocked apps.", Toast.LENGTH_SHORT).show()
        } else {
            onCheckedChange(isSelected)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { handleCheckedChange(!app.isSelected) }
            .padding(vertical = 8.dp, horizontal = 8.dp)
    ) {
        Image(
            painter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    .data(context.packageManager.getApplicationIcon(app.packageName))
                    .build()
            ),
            contentDescription = "${app.appName} icon",
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (app.isSelected) {
                Spacer(modifier = Modifier.height(6.dp))
                BlockModePill(
                    mode = app.blockMode,
                    onClick = onModeClick
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Checkbox(
            checked = app.isSelected,
            onCheckedChange = ::handleCheckedChange,
        )
    }
}
