package com.example.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.repository.ApproverScanState
import com.example.data.repository.LinkedDeviceRecord
import com.example.data.repository.PairingRequestUiState
import com.example.data.repository.PairingSessionPreview
import com.example.domain.DeviceKind
import com.example.domain.NetworkKind
import com.example.domain.PairingError
import com.example.domain.QrCodec
import com.example.ui.common.Formatters

/**
 * Renders an ISO/IEC 18004 QR matrix produced by [QrCodec.encode] onto a high-contrast Compose Canvas.
 */
@Composable
fun QrCodeCanvas(
    content: String,
    modifier: Modifier = Modifier,
) {
    val qrMatrix = remember(content) {
        runCatching { QrCodec.encode(content) }.getOrNull()
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(20.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (qrMatrix != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val n = qrMatrix.size
                val quietZone = 2
                val totalModules = n + quietZone * 2
                val cellSize = size.minDimension / totalModules.toFloat()
                val offsetX = (size.width - cellSize * totalModules) / 2f
                val offsetY = (size.height - cellSize * totalModules) / 2f

                for (r in 0 until n) {
                    for (c in 0 until n) {
                        if (qrMatrix.get(r, c)) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(
                                    offsetX + (c + quietZone) * cellSize,
                                    offsetY + (r + quietZone) * cellSize,
                                ),
                                size = Size(cellSize + 0.6f, cellSize + 0.6f),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ================================================================ 1. Auth-Screen Device Linking Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthDeviceLinkScreen(
    viewModel: DeviceLinkingViewModel,
    onBackToSignIn: () -> Unit,
) {
    val requestState by viewModel.requestState.collectAsState()
    val network by viewModel.connectivity.network.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0 = Show QR / Code for this device, 1 = Scan / Enter code
    var scannerUseManual by rememberSaveable { mutableStateOf(false) }
    var manualInput by rememberSaveable { mutableStateOf("") }
    var scannerRequiresSignInNotice by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 0 && (requestState is PairingRequestUiState.Idle || requestState is PairingRequestUiState.Cancelled)) {
            viewModel.startPairingRequest()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val s = viewModel.requestState.value
            if (s !is PairingRequestUiState.ApprovedCompleting && s !is PairingRequestUiState.Completed) {
                viewModel.resetPairingRequest()
            }
        }
    }

    BackHandler {
        viewModel.cancelPairingRequest()
        onBackToSignIn()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.device_link_auth_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.cancelPairingRequest()
                            onBackToSignIn()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 460.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (network == NetworkKind.NONE) {
                    OfflineNoticeCard()
                    Spacer(Modifier.height(12.dp))
                }

                // Explanation banner
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhonelinkSetup,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.device_link_auth_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp)),
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = {
                            selectedTab = 0
                            scannerRequiresSignInNotice = false
                        },
                        text = { Text(stringResource(R.string.device_link_tab_show_code)) },
                        icon = { Icon(Icons.Default.QrCode2, contentDescription = null) },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.device_link_tab_scan_or_enter)) },
                        icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                    )
                }

                Spacer(Modifier.height(18.dp))

                if (selectedTab == 0) {
                    AuthRequestPairingContent(
                        state = requestState,
                        onRetry = { viewModel.startPairingRequest() },
                        onCancel = {
                            viewModel.cancelPairingRequest()
                            onBackToSignIn()
                        },
                    )
                } else {
                    // Camera QR scanner or manual code entry from the Auth screen
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.device_link_scan_tab_help),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(14.dp))

                            if (!scannerUseManual) {
                                QrCameraScannerBox(
                                    active = selectedTab == 1 && !scannerUseManual,
                                    onQrDetected = {
                                        scannerRequiresSignInNotice = true
                                    },
                                    onSwitchToManualCode = { scannerUseManual = true },
                                )
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = { scannerUseManual = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Default.Keyboard, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.device_link_use_manual_code))
                                }
                            } else {
                                OutlinedTextField(
                                    value = manualInput,
                                    onValueChange = { manualInput = it.uppercase().take(12) },
                                    label = { Text(stringResource(R.string.device_link_manual_code_label)) },
                                    placeholder = { Text("ABCD-2345") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Characters,
                                        imeAction = ImeAction.Done,
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = { if (manualInput.isNotBlank()) scannerRequiresSignInNotice = true }
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = { scannerRequiresSignInNotice = true },
                                    enabled = manualInput.replace("-", "").trim().length >= 8,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.device_link_verify_code))
                                }
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { scannerUseManual = false }) {
                                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.device_link_use_camera_scanner))
                                }
                            }

                            if (scannerRequiresSignInNotice) {
                                Spacer(Modifier.height(12.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.device_link_sign_in_required_to_approve),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            textAlign = TextAlign.Center,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        FilledTonalButton(
                                            onClick = {
                                                scannerRequiresSignInNotice = false
                                                selectedTab = 0
                                            },
                                        ) {
                                            Text(stringResource(R.string.device_link_tab_show_code))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = {
                        viewModel.cancelPairingRequest()
                        onBackToSignIn()
                    },
                    modifier = Modifier.testTag("device_link_back_to_login"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.device_link_back_to_login))
                }
            }
        }
    }
}

@Composable
private fun AuthRequestPairingContent(
    state: PairingRequestUiState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                PairingRequestUiState.Idle,
                PairingRequestUiState.Creating -> {
                    Spacer(Modifier.height(24.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.device_link_preparing_session),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                }

                is PairingRequestUiState.WaitingForApproval -> {
                    Text(
                        text = stringResource(R.string.device_link_scan_with_primary),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.device_link_steps_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))

                    QrCodeCanvas(
                        content = state.qrUri,
                        modifier = Modifier
                            .size(230.dp)
                            .testTag("device_link_qr_canvas"),
                    )

                    Spacer(Modifier.height(14.dp))

                    // Countdown badge
                    val mins = state.remainingSeconds / 60
                    val secs = state.remainingSeconds % 60
                    val timeText = "%02d:%02d".format(mins, secs)
                    Surface(
                        color = if (state.remainingSeconds <= 25) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            text = stringResource(R.string.device_link_expires_in, timeText),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (state.remainingSeconds <= 25) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = stringResource(R.string.device_link_or_enter_manual_code),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = state.manualCode,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                letterSpacing = 2.sp,
                                modifier = Modifier
                                    .padding(horizontal = 18.dp, vertical = 10.dp)
                                    .testTag("device_link_manual_code_value"),
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(
                            R.string.device_link_this_device_meta,
                            state.deviceName,
                            state.friendlyId,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }

                is PairingRequestUiState.ApprovedCompleting -> {
                    Spacer(Modifier.height(24.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.device_link_completing_sign_in),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                }

                is PairingRequestUiState.Completed -> {
                    Spacer(Modifier.height(16.dp))
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(54.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.device_link_success_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                }

                PairingRequestUiState.Expired -> {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.device_link_err_expired),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.device_link_generate_new_qr))
                    }
                }

                PairingRequestUiState.Rejected -> {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.device_link_err_rejected),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_retry))
                    }
                }

                PairingRequestUiState.Cancelled -> {
                    Text(
                        text = stringResource(R.string.device_link_err_cancelled),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.device_link_generate_new_qr))
                    }
                }

                is PairingRequestUiState.Error -> {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(DeviceLinkingViewModel.errorStringRes(state.error)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
        }
    }
}

// ================================================================ 2. Signed-In Linked Devices Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedDevicesScreen(
    viewModel: DeviceLinkingViewModel,
    onBack: () -> Unit,
    startInScanner: Boolean = false,
) {
    val devices by viewModel.linkedDevices.collectAsState()
    val loading by viewModel.devicesLoading.collectAsState()
    val error by viewModel.devicesError.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val revokingDeviceId by viewModel.revokingDeviceId.collectAsState()
    val approverState by viewModel.approverState.collectAsState()
    val network by viewModel.connectivity.network.collectAsState()

    var showScannerSheet by rememberSaveable { mutableStateOf(startInScanner) }
    var devicePendingRevoke by remember { mutableStateOf<LinkedDeviceRecord?>(null) }

    val currentDevice = devices.firstOrNull { it.isCurrentDevice }
    val otherDevices = devices.filter { !it.isCurrentDevice }

    if (showScannerSheet) {
        LinkNewDeviceApproverScreen(
            state = approverState,
            onQrDetected = viewModel::onQrScanned,
            onSubmitManualCode = viewModel::onSubmitManualCode,
            onApprove = viewModel::approvePairing,
            onReject = viewModel::rejectPairing,
            onResetScanner = viewModel::resetApprover,
            onClose = {
                viewModel.resetApprover()
                showScannerSheet = false
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.linked_devices_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshDevices() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_retry))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (network == NetworkKind.NONE) {
                item { OfflineNoticeCard() }
            }

            if (actionError != null) {
                item {
                    ErrorBannerCard(
                        error = actionError!!,
                        onDismiss = viewModel::clearActionError,
                    )
                }
            }

            // Header & "Link a new device" primary button
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.linked_devices_header_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = {
                                viewModel.resetApprover()
                                showScannerSheet = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("link_new_device_button"),
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.link_new_device_action))
                        }
                    }
                }
            }

            // Current device section
            item {
                Text(
                    text = stringResource(R.string.linked_devices_current_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 6.dp, start = 4.dp)
                        .semantics { heading() },
                )
            }

            item {
                if (currentDevice != null) {
                    LinkedDeviceItemCard(
                        device = currentDevice,
                        isRevoking = false,
                        onRevokeClick = null,
                    )
                } else {
                    // Fallback while initial snapshot is loading
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Smartphone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = viewModel.currentDeviceName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    CurrentDeviceBadge()
                                }
                                Text(
                                    text = viewModel.currentFriendlyId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Other linked devices section
            item {
                Text(
                    text = stringResource(R.string.linked_devices_other_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 8.dp, start = 4.dp)
                        .semantics { heading() },
                )
            }

            if (loading && devices.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (error != null && otherDevices.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(DeviceLinkingViewModel.errorStringRes(error!!)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(onClick = { viewModel.refreshDevices() }) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                }
            } else if (otherDevices.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.linked_devices_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                items(otherDevices, key = { it.deviceId }) { device ->
                    LinkedDeviceItemCard(
                        device = device,
                        isRevoking = revokingDeviceId == device.deviceId,
                        onRevokeClick = { devicePendingRevoke = device },
                    )
                }
            }
        }
    }

    devicePendingRevoke?.let { target ->
        AlertDialog(
            onDismissRequest = { devicePendingRevoke = null },
            icon = { Icon(Icons.Default.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.device_revoke_confirm_title, target.deviceName)) },
            text = { Text(stringResource(R.string.device_revoke_confirm_body, target.deviceName, target.friendlyId)) },
            confirmButton = {
                Button(
                    onClick = {
                        val id = target.deviceId
                        devicePendingRevoke = null
                        viewModel.revokeDevice(id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.device_revoke_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { devicePendingRevoke = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun LinkedDeviceItemCard(
    device: LinkedDeviceRecord,
    isRevoking: Boolean,
    onRevokeClick: (() -> Unit)?,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = iconForDeviceKind(device.deviceKind),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = device.deviceName.ifBlank { stringResource(R.string.device_link_unknown_device) },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (device.isCurrentDevice) {
                            CurrentDeviceBadge()
                        }
                    }
                    val sep = stringResource(R.string.list_separator)
                    Text(
                        text = "${labelForDeviceKind(device.deviceKind)}$sep${device.friendlyId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (device.online) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            text = stringResource(R.string.device_status_active_now),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (device.linkedAt > 0L) {
                Text(
                    text = stringResource(R.string.device_linked_at_label, Formatters.dateTime(device.linkedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!device.online && device.lastActiveAt > 0L) {
                Text(
                    text = stringResource(R.string.device_last_active_label, Formatters.dateTime(device.lastActiveAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (onRevokeClick != null && !device.isCurrentDevice) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(
                        onClick = onRevokeClick,
                        enabled = !isRevoking,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        if (isRevoking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.device_revoke_action))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentDeviceBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = stringResource(R.string.device_this_device_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

// ================================================================ 3. Signed-In Approver Scanner & Explicit Confirmation Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkNewDeviceApproverScreen(
    state: ApproverScanState,
    onQrDetected: (String) -> Unit,
    onSubmitManualCode: (String) -> Unit,
    onApprove: (PairingSessionPreview, String?) -> Unit,
    onReject: (PairingSessionPreview) -> Unit,
    onResetScanner: () -> Unit,
    onClose: () -> Unit,
) {
    var modeTab by rememberSaveable { mutableIntStateOf(0) } // 0 = Camera QR, 1 = Manual code
    var manualCode by rememberSaveable { mutableStateOf("") }
    var verifyPassword by rememberSaveable { mutableStateOf("") }

    BackHandler { onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.link_new_device_action)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                ApproverScanState.Scanning -> {
                    TabRow(
                        selectedTabIndex = modeTab,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp)),
                    ) {
                        Tab(
                            selected = modeTab == 0,
                            onClick = { modeTab = 0 },
                            text = { Text(stringResource(R.string.device_link_use_camera_scanner)) },
                            icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                        )
                        Tab(
                            selected = modeTab == 1,
                            onClick = { modeTab = 1 },
                            text = { Text(stringResource(R.string.device_link_use_manual_code)) },
                            icon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    if (modeTab == 0) {
                        Text(
                            text = stringResource(R.string.device_link_approver_scan_instructions),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(14.dp))
                        QrCameraScannerBox(
                            active = modeTab == 0,
                            onQrDetected = onQrDetected,
                            onSwitchToManualCode = { modeTab = 1 },
                        )
                    } else {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(R.string.device_link_approver_manual_instructions),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(14.dp))
                                OutlinedTextField(
                                    value = manualCode,
                                    onValueChange = { manualCode = it.uppercase().take(12) },
                                    label = { Text(stringResource(R.string.device_link_manual_code_label)) },
                                    placeholder = { Text("ABCD-2345") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Characters,
                                        imeAction = ImeAction.Done,
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            if (manualCode.replace("-", "").trim().length >= 8) {
                                                onSubmitManualCode(manualCode)
                                            }
                                        }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("approver_manual_code_input"),
                                )
                                Spacer(Modifier.height(14.dp))
                                Button(
                                    onClick = { onSubmitManualCode(manualCode) },
                                    enabled = manualCode.replace("-", "").trim().length >= 8,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("approver_verify_code_button"),
                                ) {
                                    Text(stringResource(R.string.device_link_verify_code))
                                }
                            }
                        }
                    }
                }

                ApproverScanState.LoadingSession,
                ApproverScanState.Approving -> {
                    Spacer(Modifier.height(48.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(
                            if (state is ApproverScanState.Approving) R.string.device_link_authorizing
                            else R.string.device_link_loading_request
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }

                is ApproverScanState.ConfirmApproval -> {
                    val preview = state.preview
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = iconForDeviceKind(preview.requesterDeviceKind),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.device_link_confirm_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = preview.requesterDeviceName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val sep = stringResource(R.string.list_separator)
                            Text(
                                text = "${labelForDeviceKind(preview.requesterDeviceKind)}$sep${preview.friendlyId}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Spacer(Modifier.height(16.dp))

                            // Security warning card
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = stringResource(R.string.device_link_security_warning),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }

                            Spacer(Modifier.height(20.dp))

                            Button(
                                onClick = { onApprove(preview, null) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("approve_pairing_button"),
                            ) {
                                Text(stringResource(R.string.device_link_approve_action))
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { onReject(preview) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("reject_pairing_button"),
                            ) {
                                Text(stringResource(R.string.device_link_reject_action))
                            }
                        }
                    }
                }

                is ApproverScanState.NeedsAccountVerification -> {
                    val preview = state.preview
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(44.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.device_link_confirm_owner_password_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.device_link_confirm_owner_password_body, preview.requesterDeviceName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(14.dp))
                            OutlinedTextField(
                                value = verifyPassword,
                                onValueChange = { verifyPassword = it },
                                label = { Text(stringResource(R.string.auth_password)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = { onApprove(preview, verifyPassword) },
                                enabled = verifyPassword.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.device_link_approve_action))
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { onReject(preview) }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    }
                }

                is ApproverScanState.Approved -> {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(54.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.device_link_approved_done, state.deviceName),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.action_done))
                            }
                        }
                    }
                }

                ApproverScanState.Rejected -> {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.device_link_rejected_done),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.action_done))
                            }
                        }
                    }
                }

                is ApproverScanState.Error -> {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(DeviceLinkingViewModel.errorStringRes(state.error)),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onResetScanner, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineNoticeCard() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.error_network),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ErrorBannerCard(
    error: PairingError,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(DeviceLinkingViewModel.errorStringRes(error)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        }
    }
}

@Composable
private fun iconForDeviceKind(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.ANDROID_PHONE -> Icons.Default.Smartphone
    DeviceKind.ANDROID_TABLET -> Icons.Default.TabletAndroid
    DeviceKind.WEB -> Icons.Default.Computer
    DeviceKind.OTHER, DeviceKind.UNKNOWN -> Icons.Default.Devices
}

@Composable
private fun labelForDeviceKind(kind: DeviceKind): String = when (kind) {
    DeviceKind.ANDROID_PHONE -> stringResource(R.string.device_kind_android_phone)
    DeviceKind.ANDROID_TABLET -> stringResource(R.string.device_kind_android_tablet)
    DeviceKind.WEB -> stringResource(R.string.device_kind_web)
    DeviceKind.OTHER, DeviceKind.UNKNOWN -> stringResource(R.string.device_kind_other)
}
