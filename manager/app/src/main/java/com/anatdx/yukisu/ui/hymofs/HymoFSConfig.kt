package com.anatdx.yukisu.ui.hymofs

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.anatdx.yukisu.R
import com.anatdx.yukisu.ui.hymofs.util.HymoFSManager
import com.anatdx.yukisu.ui.hymofs.util.HymoFSManager.HymoFSStatus
import kotlinx.coroutines.launch

/**
 * Tab enum for HymoFS config screen
 */
enum class HymoFSTab(val displayNameRes: Int) {
    STATUS(R.string.hymofs_tab_status),
    MODULES(R.string.hymofs_tab_modules),
    SETTINGS(R.string.hymofs_tab_settings),
    RULES(R.string.hymofs_tab_rules),
    LOGS(R.string.hymofs_tab_logs)
}

/**
 * HymoFS Configuration Screen
 */
@SuppressLint("SdCardPath")
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun HymoFSConfigScreen(
    navigator: DestinationsNavigator
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // State
    var selectedTab by remember { mutableStateOf(HymoFSTab.STATUS) }
    var isLoading by remember { mutableStateOf(true) }
    
    // Data
    var hymofsStatus by remember { mutableStateOf(HymoFSStatus.NOT_PRESENT) }
    var version by remember { mutableStateOf("Unknown") }
    var config by remember { mutableStateOf(HymoFSManager.HymoConfig()) }
    var modules by remember { mutableStateOf(emptyList<HymoFSManager.ModuleInfo>()) }
    var activeRules by remember { mutableStateOf(emptyList<HymoFSManager.ActiveRule>()) }
    var systemInfo by remember { mutableStateOf(HymoFSManager.SystemInfo("", "", "", emptyList(), emptyList(), false, null)) }
    var storageInfo by remember { mutableStateOf(HymoFSManager.StorageInfo("-", "-", "-", "0%", "unknown")) }
    var logContent by remember { mutableStateOf("") }
    var showKernelLog by remember { mutableStateOf(false) }
    var builtinMountEnabled by remember { mutableStateOf(true) }

    // Load data
    fun loadData() {
        coroutineScope.launch {
            isLoading = true
            try {
                version = HymoFSManager.getVersion()
                hymofsStatus = HymoFSManager.getStatus()
                config = HymoFSManager.loadConfig()
                modules = HymoFSManager.getModules()
                systemInfo = HymoFSManager.getSystemInfo()
                storageInfo = HymoFSManager.getStorageInfo()
                builtinMountEnabled = HymoFSManager.isBuiltinMountEnabled()
                if (hymofsStatus == HymoFSStatus.AVAILABLE) {
                    activeRules = HymoFSManager.getActiveRules()
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Error loading data: ${e.message}")
            }
            isLoading = false
        }
    }

    // Initial load
    LaunchedEffect(Unit) {
        loadData()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.hymofs_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { loadData() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab row
            ScrollableTabRow(
                selectedTabIndex = HymoFSTab.entries.indexOf(selectedTab),
                edgePadding = 16.dp
            ) {
                HymoFSTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(stringResource(tab.displayNameRes)) }
                    )
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                when (selectedTab) {
                    HymoFSTab.STATUS -> StatusTab(
                        hymofsStatus = hymofsStatus,
                        version = version,
                        systemInfo = systemInfo,
                        storageInfo = storageInfo,
                        modules = modules,
                        onRefresh = { loadData() }
                    )
                    HymoFSTab.MODULES -> ModulesTab(
                        modules = modules,
                        hymofsAvailable = hymofsStatus == HymoFSStatus.AVAILABLE,
                        onModeChanged = { moduleId, mode ->
                            coroutineScope.launch {
                                if (HymoFSManager.setModuleMode(moduleId, mode)) {
                                    snackbarHostState.showSnackbar("Mode updated. Reboot to apply.")
                                    loadData()
                                } else {
                                    snackbarHostState.showSnackbar("Failed to update mode")
                                }
                            }
                        }
                    )
                    HymoFSTab.SETTINGS -> SettingsTab(
                        config = config,
                        hymofsStatus = hymofsStatus,
                        onConfigChanged = { newConfig ->
                            coroutineScope.launch {
                                if (HymoFSManager.saveConfig(newConfig)) {
                                    config = newConfig
                                    snackbarHostState.showSnackbar("Settings saved")
                                } else {
                                    snackbarHostState.showSnackbar("Failed to save settings")
                                }
                            }
                        },
                        onSetDebug = { enable ->
                            coroutineScope.launch {
                                if (HymoFSManager.setKernelDebug(enable)) {
                                    snackbarHostState.showSnackbar("Kernel debug ${if (enable) "enabled" else "disabled"}")
                                }
                            }
                        },
                        onSetStealth = { enable ->
                            coroutineScope.launch {
                                if (HymoFSManager.setStealth(enable)) {
                                    snackbarHostState.showSnackbar("Stealth mode ${if (enable) "enabled" else "disabled"}")
                                }
                            }
                        },
                        onFixMounts = {
                            coroutineScope.launch {
                                if (HymoFSManager.fixMounts()) {
                                    snackbarHostState.showSnackbar("Mount IDs reordered")
                                } else {
                                    snackbarHostState.showSnackbar("Failed to fix mounts")
                                }
                            }
                        },
                        builtinMountEnabled = builtinMountEnabled,
                        onBuiltinMountChanged = { enable ->
                            coroutineScope.launch {
                                if (HymoFSManager.setBuiltinMountEnabled(enable)) {
                                    builtinMountEnabled = enable
                                    snackbarHostState.showSnackbar("Built-in mount ${if (enable) "enabled" else "disabled"}. Reboot to apply.")
                                } else {
                                    snackbarHostState.showSnackbar("Failed to update built-in mount setting")
                                }
                            }
                        }
                    )
                    HymoFSTab.RULES -> RulesTab(
                        activeRules = activeRules,
                        hymofsStatus = hymofsStatus,
                        onRefresh = {
                            coroutineScope.launch {
                                activeRules = HymoFSManager.getActiveRules()
                            }
                        },
                        onClearAll = {
                            coroutineScope.launch {
                                if (HymoFSManager.clearAllRules()) {
                                    activeRules = emptyList()
                                    snackbarHostState.showSnackbar("All rules cleared")
                                } else {
                                    snackbarHostState.showSnackbar("Failed to clear rules")
                                }
                            }
                        }
                    )
                    HymoFSTab.LOGS -> LogsTab(
                        showKernelLog = showKernelLog,
                        onToggleLogType = { showKernelLog = !showKernelLog },
                        logContent = logContent,
                        onRefreshLog = {
                            coroutineScope.launch {
                                logContent = if (showKernelLog) {
                                    HymoFSManager.readKernelLog()
                                } else {
                                    HymoFSManager.readLog()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

// ==================== Status Tab ====================
@Composable
private fun StatusTab(
    hymofsStatus: HymoFSStatus,
    version: String,
    systemInfo: HymoFSManager.SystemInfo,
    storageInfo: HymoFSManager.StorageInfo,
    modules: List<HymoFSManager.ModuleInfo>,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // HymoFS Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = when (hymofsStatus) {
                    HymoFSStatus.AVAILABLE -> Color(0xFF1B5E20).copy(alpha = 0.2f)
                    HymoFSStatus.NOT_PRESENT -> MaterialTheme.colorScheme.surfaceVariant
                    else -> Color(0xFFE65100).copy(alpha = 0.2f)
                }
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = when (hymofsStatus) {
                            HymoFSStatus.AVAILABLE -> Icons.Filled.CheckCircle
                            HymoFSStatus.NOT_PRESENT -> Icons.Filled.Info
                            else -> Icons.Filled.Warning
                        },
                        contentDescription = null,
                        tint = when (hymofsStatus) {
                            HymoFSStatus.AVAILABLE -> Color(0xFF4CAF50)
                            HymoFSStatus.NOT_PRESENT -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> Color(0xFFFF9800)
                        },
                        modifier = Modifier.size(32.dp)
                    )
                    Column {
                        Text(
                            text = "HymoFS Kernel",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = hymofsStatus.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                if (hymofsStatus == HymoFSStatus.AVAILABLE) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Version: $version",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        // Storage Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.hymofs_storage),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (storageInfo.type != "unknown") {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (storageInfo.type == "tmpfs" || storageInfo.type == "hymofs")
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = storageInfo.type.uppercase(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                LinearProgressIndicator(
                    progress = { storageInfo.percent.removeSuffix("%").toFloatOrNull()?.div(100) ?: 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = systemInfo.mountBase,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${storageInfo.used} / ${storageInfo.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        // Stats Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                value = modules.size.toString(),
                label = stringResource(R.string.hymofs_modules_count)
            )
            StatCard(
                modifier = Modifier.weight(1f),
                value = if (hymofsStatus == HymoFSStatus.AVAILABLE)
                    systemInfo.hymofsModuleIds.size.toString()
                else "❌",
                label = "HymoFS"
            )
        }
        
        // System Info Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.hymofs_system_info),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                InfoRow(label = "Kernel", value = systemInfo.kernel)
                InfoRow(label = "SELinux", value = systemInfo.selinux)
                InfoRow(label = "Mount Base", value = systemInfo.mountBase)
                
                if (systemInfo.activeMounts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Active Mounts:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    systemInfo.activeMounts.take(5).forEach { mount ->
                        Text(
                            text = "  • $mount",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (systemInfo.activeMounts.size > 5) {
                        Text(
                            text = "  ... and ${systemInfo.activeMounts.size - 5} more",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // Warning for mismatch
        if (systemInfo.hymofsMismatch) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE65100).copy(alpha = 0.2f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF9800)
                    )
                    Text(
                        text = systemInfo.mismatchMessage ?: "Protocol mismatch detected. Please update kernel or module.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    value: String,
    label: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ==================== Modules Tab ====================
@Composable
private fun ModulesTab(
    modules: List<HymoFSManager.ModuleInfo>,
    hymofsAvailable: Boolean,
    onModeChanged: (String, String) -> Unit
) {
    val modes = if (hymofsAvailable) {
        listOf("auto", "hymofs", "overlay", "magic", "none")
    } else {
        listOf("auto", "overlay", "magic", "none")
    }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(modules) { module ->
            ModuleCard(
                module = module,
                modes = modes,
                onModeChanged = { mode -> onModeChanged(module.id, mode) }
            )
        }
        
        if (modules.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No modules found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModuleCard(
    module: HymoFSManager.ModuleInfo,
    modes: List<String>,
    onModeChanged: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = module.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = module.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                // Strategy badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = when (module.strategy) {
                        "hymofs" -> Color(0xFF1B5E20).copy(alpha = 0.3f)
                        "overlay" -> MaterialTheme.colorScheme.primaryContainer
                        "magic" -> Color(0xFF4A148C).copy(alpha = 0.3f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        text = module.strategy.uppercase(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            if (module.version.isNotEmpty() || module.author.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildString {
                        if (module.version.isNotEmpty()) append("v${module.version}")
                        if (module.author.isNotEmpty()) {
                            if (isNotEmpty()) append(" • ")
                            append(module.author)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Mode selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Mode:",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = module.mode,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        modes.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode) },
                                onClick = {
                                    expanded = false
                                    if (mode != module.mode) {
                                        onModeChanged(mode)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== Settings Tab ====================
@Composable
private fun SettingsTab(
    config: HymoFSManager.HymoConfig,
    hymofsStatus: HymoFSStatus,
    onConfigChanged: (HymoFSManager.HymoConfig) -> Unit,
    onSetDebug: (Boolean) -> Unit,
    onSetStealth: (Boolean) -> Unit,
    onFixMounts: () -> Unit,
    builtinMountEnabled: Boolean,
    onBuiltinMountChanged: (Boolean) -> Unit
) {
    // Helper to update config and save immediately
    fun updateAndSave(newConfig: HymoFSManager.HymoConfig) {
        onConfigChanged(newConfig)
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // General Settings
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "General",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                SettingSwitch(
                    title = stringResource(R.string.hymofs_builtin_mount),
                    subtitle = stringResource(R.string.hymofs_builtin_mount_desc),
                    checked = builtinMountEnabled,
                    onCheckedChange = onBuiltinMountChanged
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                SettingSwitch(
                    title = stringResource(R.string.hymofs_verbose),
                    subtitle = stringResource(R.string.hymofs_verbose_desc),
                    checked = config.verbose,
                    onCheckedChange = {
                        updateAndSave(config.copy(verbose = it))
                    }
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                SettingSwitch(
                    title = stringResource(R.string.hymofs_force_ext4),
                    subtitle = stringResource(R.string.hymofs_force_ext4_desc),
                    checked = config.forceExt4,
                    onCheckedChange = {
                        updateAndSave(config.copy(forceExt4 = it))
                    }
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                SettingSwitch(
                    title = stringResource(R.string.hymofs_disable_umount),
                    subtitle = stringResource(R.string.hymofs_disable_umount_desc),
                    checked = config.disableUmount,
                    onCheckedChange = {
                        updateAndSave(config.copy(disableUmount = it))
                    }
                )
            }
        }
        
        // Advanced Settings
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Advanced",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                val hymofsAvailable = hymofsStatus == HymoFSStatus.AVAILABLE
                
                SettingSwitch(
                    title = stringResource(R.string.hymofs_kernel_debug),
                    subtitle = stringResource(R.string.hymofs_kernel_debug_desc),
                    checked = config.enableKernelDebug,
                    enabled = hymofsAvailable,
                    onCheckedChange = {
                        onSetDebug(it)
                        updateAndSave(config.copy(enableKernelDebug = it))
                    }
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                SettingSwitch(
                    title = stringResource(R.string.hymofs_stealth),
                    subtitle = stringResource(R.string.hymofs_stealth_desc),
                    checked = config.enableStealth,
                    enabled = hymofsAvailable,
                    onCheckedChange = {
                        onSetStealth(it)
                        updateAndSave(config.copy(enableStealth = it))
                    }
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                SettingSwitch(
                    title = stringResource(R.string.hymofs_avc_spoof),
                    subtitle = stringResource(R.string.hymofs_avc_spoof_desc),
                    checked = config.avcSpoof,
                    enabled = hymofsAvailable,
                    onCheckedChange = {
                        updateAndSave(config.copy(avcSpoof = it))
                    }
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                SettingSwitch(
                    title = stringResource(R.string.hymofs_enable_nuke),
                    subtitle = stringResource(R.string.hymofs_enable_nuke_desc),
                    checked = config.enableNuke,
                    enabled = hymofsAvailable,
                    onCheckedChange = {
                        updateAndSave(config.copy(enableNuke = it))
                    }
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                SettingSwitch(
                    title = stringResource(R.string.hymofs_ignore_protocol),
                    subtitle = stringResource(R.string.hymofs_ignore_protocol_desc),
                    checked = config.ignoreProtocolMismatch,
                    onCheckedChange = {
                        updateAndSave(config.copy(ignoreProtocolMismatch = it))
                    }
                )
                
                if (hymofsAvailable) {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedButton(
                        onClick = onFixMounts,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Build, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Fix Mount IDs")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

// ==================== Rules Tab ====================
@Composable
private fun RulesTab(
    activeRules: List<HymoFSManager.ActiveRule>,
    hymofsStatus: HymoFSStatus,
    onRefresh: () -> Unit,
    onClearAll: () -> Unit
) {
    var showClearDialog by remember { mutableStateOf(false) }
    
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Rules") },
            text = { Text("Are you sure you want to clear all active HymoFS rules? This will take effect immediately.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClearAll()
                    }
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (hymofsStatus != HymoFSStatus.AVAILABLE) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null)
                    Text("HymoFS kernel not available. Rules tab requires HymoFS kernel support.")
                }
            }
            return
        }
        
        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Refresh")
            }
            
            OutlinedButton(
                onClick = { showClearDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clear All")
            }
        }
        
        // Rules list
        Text(
            text = "${activeRules.size} active rules",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(activeRules) { rule ->
                RuleItem(rule)
            }
            
            if (activeRules.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No active rules",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleItem(rule: HymoFSManager.ActiveRule) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = when (rule.type) {
                    "add" -> Color(0xFF1B5E20).copy(alpha = 0.3f)
                    "hide" -> Color(0xFFB71C1C).copy(alpha = 0.3f)
                    "inject" -> Color(0xFF1565C0).copy(alpha = 0.3f)
                    "merge" -> Color(0xFF4A148C).copy(alpha = 0.3f)
                    else -> MaterialTheme.colorScheme.secondaryContainer
                }
            ) {
                Text(
                    text = rule.type.uppercase(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.src,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (rule.target != null) {
                    Text(
                        text = "→ ${rule.target}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ==================== Logs Tab ====================
@Composable
private fun LogsTab(
    showKernelLog: Boolean,
    onToggleLogType: () -> Unit,
    logContent: String,
    onRefreshLog: () -> Unit
) {
    LaunchedEffect(showKernelLog) {
        onRefreshLog()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Toggle buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilterChip(
                selected = !showKernelLog,
                onClick = { if (showKernelLog) onToggleLogType() },
                label = { Text("Daemon Log") }
            )
            FilterChip(
                selected = showKernelLog,
                onClick = { if (!showKernelLog) onToggleLogType() },
                label = { Text("Kernel Log") }
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            IconButton(onClick = onRefreshLog) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }
        
        // Log content
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E1E)
            )
        ) {
            val scrollState = rememberScrollState()
            
            Text(
                text = if (logContent.isEmpty()) "No logs available" else logContent,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFD4D4D4),
                fontSize = 11.sp
            )
        }
    }
}
