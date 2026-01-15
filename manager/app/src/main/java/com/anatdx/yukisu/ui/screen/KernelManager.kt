package com.anatdx.yukisu.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.InstallScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch
import com.anatdx.yukisu.R
import com.anatdx.yukisu.ui.util.LocalSnackbarHost
import com.anatdx.yukisu.ui.viewmodel.KernelManagerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun KernelManagerScreen(navigator: DestinationsNavigator) {
    val viewModel = viewModel<KernelManagerViewModel>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current

    var showAvbDialog by remember { mutableStateOf(false) }
    var showLoadingDialog by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }

    val fileLauncherForInstall = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            navigator.navigate(InstallScreenDestination(preselectedKernelUri = it.toString()))
        }
    }

    val kernelImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                showLoadingDialog = true
                loadingMessage = context.getString(R.string.kernel_flashing_progress)
                val result = viewModel.flashKernelImage(context, it)
                showLoadingDialog = false

                result.onSuccess { msg ->
                    snackbarHost.showSnackbar(msg)
                }.onFailure { error ->
                    snackbarHost.showSnackbar(
                        context.getString(R.string.kernel_flash_failed, error.message)
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadKernelInfo()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.kernel_manager)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.loadKernelInfo() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.kernel_refresh)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            // Current Slot Info
            SlotInfoCard(
                title = "${stringResource(R.string.kernel_slot_info)} (${viewModel.currentSlot})",
                kernelVersion = viewModel.currentKernelVersion,
                avbStatus = viewModel.avbStatus,
                isLoading = viewModel.isLoading
            )

            // Other Slot Info (if A/B device)
            if (viewModel.hasOtherSlot) {
                Spacer(Modifier.height(16.dp))
                SlotInfoCard(
                    title = "${stringResource(R.string.kernel_slot_info)} (${viewModel.otherSlot})",
                    kernelVersion = viewModel.otherKernelVersion,
                    avbStatus = null, // Only show for current slot
                    isLoading = viewModel.isLoading
                )
            }

            Spacer(Modifier.height(24.dp))

            // Actions Section
            Text(
                text = stringResource(R.string.kernel_actions),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(12.dp))

            // Flash AnyKernel3 -> Generic Flash File (redirects)
            ActionCard(
                title = stringResource(R.string.kernel_flash_file),
                description = stringResource(R.string.kernel_flash_file_desc),
                icon = Icons.Default.FileUpload,
                onClick = { fileLauncherForInstall.launch("*/*") }
            )

            Spacer(Modifier.height(8.dp))

            // Flash Kernel Image (Kept for direct flashing without going to Install screen)
            ActionCard(
                title = stringResource(R.string.kernel_flash_image),
                description = "Directly flash kernel image (boot.img/Image/Image.gz)",
                icon = Icons.Default.SystemUpdate,
                onClick = { kernelImageLauncher.launch("*/*") }
            )

            Spacer(Modifier.height(8.dp))

            // Extract Kernel
            ActionCard(
                title = stringResource(R.string.kernel_extract),
                description = "Extract kernel from boot partition",
                icon = Icons.Default.DownloadForOffline,
                onClick = {
                    scope.launch {
                        showLoadingDialog = true
                        loadingMessage = context.getString(
                            R.string.kernel_extracting,
                            viewModel.currentSlot
                        )
                        val result = viewModel.extractKernel(context)
                        showLoadingDialog = false

                        result.onSuccess { path ->
                            snackbarHost.showSnackbar(
                                context.getString(R.string.kernel_extract_success, path)
                            )
                        }.onFailure { error ->
                            snackbarHost.showSnackbar(
                                context.getString(R.string.kernel_extract_failed, error.message)
                            )
                        }
                    }
                }
            )

            Spacer(Modifier.height(8.dp))

            // Disable AVB
            if (viewModel.avbStatus == "enabled") {
                ActionCard(
                    title = stringResource(R.string.kernel_avb_disable),
                    description = "Disable Android Verified Boot (requires reboot)",
                    icon = Icons.Default.SecurityUpdate,
                    onClick = { showAvbDialog = true },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // AVB Disable Confirmation Dialog
    if (showAvbDialog) {
        AlertDialog(
            onDismissRequest = { showAvbDialog = false },
            title = { Text(stringResource(R.string.kernel_avb_disable)) },
            text = { Text(stringResource(R.string.kernel_avb_disable_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAvbDialog = false
                        scope.launch {
                            showLoadingDialog = true
                            loadingMessage = context.getString(R.string.kernel_avb_disabling)
                            val result = viewModel.disableAvb()
                            showLoadingDialog = false

                            result.onSuccess {
                                snackbarHost.showSnackbar(
                                    context.getString(R.string.kernel_avb_disable_success)
                                )
                                viewModel.loadKernelInfo() // Refresh status
                            }.onFailure { error ->
                                snackbarHost.showSnackbar(
                                    context.getString(R.string.kernel_avb_disable_failed, error.message)
                                )
                            }
                        }
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAvbDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // Loading Dialog
    if (showLoadingDialog) {
        AlertDialog(
            onDismissRequest = { },
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
            title = null,
            text = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(loadingMessage)
                }
            },
            confirmButton = { }
        )
    }
}

@Composable
private fun SlotInfoCard(
    title: String,
    kernelVersion: String,
    avbStatus: String?,
    isLoading: Boolean
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(12.dp))

            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.kernel_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Kernel Version
                InfoRow(
                    label = stringResource(R.string.kernel_version),
                    value = kernelVersion.ifEmpty { stringResource(R.string.partition_unknown) }
                )

                // AVB Status (only for current slot)
                if (avbStatus != null) {
                    Spacer(Modifier.height(8.dp))
                    InfoRow(
                        label = stringResource(R.string.kernel_avb_status),
                        value = when (avbStatus) {
                            "enabled" -> stringResource(R.string.kernel_avb_enabled)
                            "disabled" -> stringResource(R.string.kernel_avb_disabled)
                            else -> avbStatus
                        },
                        valueColor = when (avbStatus) {
                            "enabled" -> MaterialTheme.colorScheme.primary
                            "disabled" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = if (label.contains("Version")) FontFamily.Monospace else null
            ),
            color = valueColor,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    colors: CardColors = CardDefaults.elevatedCardColors()
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = colors
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
