package com.example.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ConnectionStatus
import com.example.data.DeviceApprovalStatus
import com.example.data.RadioMode
import com.example.ui.theme.BrandAmber
import com.example.ui.theme.ChassisBevelHighlight
import com.example.ui.theme.ChassisBodyDark
import com.example.ui.theme.ChassisBodyMid
import com.example.ui.theme.ChassisObsidian
import com.example.ui.theme.ShareTechMonoFontFamily
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.TextPrimaryLight
import com.example.viewmodel.WalkieViewModel

private enum class WalkieOverlay {
    NONE,
    DEVICES,
    SETTINGS_ADMIN
}

@Composable
fun WalkieTalkieScreen(
    viewModel: WalkieViewModel,
    onRequestSignOut: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var activeOverlay by remember { mutableStateOf(WalkieOverlay.NONE) }
    var hasPromptedPermissionsOnce by remember { mutableStateOf(false) }

    // Request RECORD_AUDIO + POST_NOTIFICATIONS upfront so mic and background notification are always ready
    val multiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        viewModel.setMicPermissionGranted(micGranted)
        viewModel.refreshSystemPermissionAndPowerStatus()
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setMicPermissionGranted(granted)
        if (!granted) {
            openAppSettings(context)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshSystemPermissionAndPowerStatus()
        if (!hasPromptedPermissionsOnce) {
            hasPromptedPermissionsOnce = true
            val toRequest = buildList {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.RECORD_AUDIO)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (toRequest.isNotEmpty()) {
                multiPermissionLauncher.launch(toRequest.toTypedArray())
            }
        }
    }

    // Re-sync mic permission & unrestricted battery status whenever returning from system Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSystemPermissionAndPowerStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = ChassisObsidian,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
        ) { innerPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF0A0D10), Color(0xFF13181F), Color(0xFF07090B))
                        )
                    )
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                val isCompactHeight = maxHeight < 680.dp

                // Full-screen Rugged Walkie-Talkie Body Container
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (isCompactHeight) Modifier.verticalScroll(rememberScrollState())
                                else Modifier
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Top Antenna + Rotary Knob + Brand Plate
                        RuggedWalkieTopHeader(
                            isTransmitting = state.isTransmitting,
                            isPoweredOn = state.isPoweredOn,
                            localAudioLevel = state.audioTelemetry.localAudioLevel,
                            onTopKnobClick = { viewModel.channelUp() }
                        )

                        // Main Molded Rugged Radio Chassis
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = !isCompactHeight)
                                .clip(RoundedCornerShape(32.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(ChassisBodyMid, ChassisBodyDark, Color(0xFF12161B))
                                    )
                                )
                                .border(2.dp, ChassisBevelHighlight, RoundedCornerShape(32.dp))
                        ) {
                            // Molded Side Grip Ridges (Left & Right Tactile Bumpers)
                            Canvas(modifier = Modifier.matchParentSize()) {
                                val gripCount = 14
                                val startY = size.height * 0.24f
                                val endY = size.height * 0.86f
                                val step = (endY - startY) / gripCount
                                for (i in 0..gripCount) {
                                    val y = startY + i * step
                                    drawLine(
                                        color = Color(0xFF07090C),
                                        start = Offset(0f, y),
                                        end = Offset(10.dp.toPx(), y),
                                        strokeWidth = 3.dp.toPx()
                                    )
                                    drawLine(
                                        color = Color(0xFF07090C),
                                        start = Offset(size.width - 10.dp.toPx(), y),
                                        end = Offset(size.width, y),
                                        strokeWidth = 3.dp.toPx()
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // 0. Compact Contextual Hardware Alert Pill (External Call / Mic Permission / Battery Unrestricted)
                                when {
                                    state.isCallInterrupted -> {
                                        HardwareAlertStrip(
                                            icon = Icons.Default.PhoneInTalk,
                                            text = "EXTERNAL CALL ACTIVE • MIC RELEASED (AUTO-RECLAIM)",
                                            accentColor = BrandAmber,
                                            testTag = "external_call_strip",
                                            onClick = null
                                        )
                                        Spacer(Modifier.height(6.dp))
                                    }

                                    !state.micPermissionGranted -> {
                                        HardwareAlertStrip(
                                            icon = Icons.Default.MicOff,
                                            text = "MIC PERMISSION REQUIRED • TAP TO ENABLE PTT",
                                            accentColor = StatusDangerRed,
                                            testTag = "mic_permission_strip",
                                            onClick = {
                                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        )
                                        Spacer(Modifier.height(6.dp))
                                    }

                                    !state.batteryOptimizationIgnored && state.preferences.keepActiveInBackground -> {
                                        HardwareAlertStrip(
                                            icon = Icons.Default.BatteryAlert,
                                            text = "ALLOW UNRESTRICTED BATTERY FOR 24/7 BACKGROUND",
                                            accentColor = BrandAmber,
                                            testTag = "battery_opt_strip",
                                            onClick = { openBatteryOptimizationSettings(context) }
                                        )
                                        Spacer(Modifier.height(6.dp))
                                    }
                                }

                                // 1. Top Row inside Chassis: Power ON/OFF + Green Backlit LCD Display
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    PowerHardwareButton(
                                        isPoweredOn = state.isPoweredOn,
                                        onClick = { viewModel.togglePower() }
                                    )
                                    BacklitLcdDisplay(
                                        isPoweredOn = state.isPoweredOn,
                                        connectionStatus = state.connectionStatus,
                                        radioMode = state.radioMode,
                                        activeChannel = state.activeChannel,
                                        privateTargetName = state.privateTargetName,
                                        onlineInChannel = state.onlineInActiveChannelCount,
                                        batteryPercent = state.batteryPercent,
                                        isCharging = state.isCharging,
                                        signalBars = state.signalBars,
                                        networkTransport = state.networkTransport,
                                        isTransmitting = state.isTransmitting,
                                        activeSpeakerName = state.activeSpeakerName,
                                        isChannelBusyByOther = state.isChannelBusyByOther,
                                        micPermissionGranted = state.micPermissionGranted,
                                        localAudioLevel = state.audioTelemetry.localAudioLevel,
                                        remoteAudioLevel = state.audioTelemetry.remoteAudioLevel,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                // 2. Middle Deck: Channel Rocker | Acoustic Speaker Grill | Rotary Volume Knob
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    ChannelRockerSwitch(
                                        enabled = state.isPoweredOn,
                                        onChannelUp = { viewModel.channelUp() },
                                        onChannelDown = { viewModel.channelDown() }
                                    )

                                    AcousticSpeakerGrill(
                                        isReceivingAudio = state.isChannelBusyByOther || state.audioTelemetry.receivingAudio,
                                        remoteAudioLevel = state.audioTelemetry.remoteAudioLevel,
                                        isPoweredOn = state.isPoweredOn,
                                        modifier = Modifier.weight(1f)
                                    )

                                    RotaryVolumeKnob(
                                        volumePercent = state.volumePercent,
                                        enabled = state.isPoweredOn,
                                        onVolumeChange = { viewModel.adjustVolume(it) },
                                        onStepVolume = { viewModel.stepVolume(it) }
                                    )
                                }

                                Spacer(Modifier.height(10.dp))

                                // 3. Lower Action Deck: Connect Devices (2-6) | HERO PTT | Settings
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    ConnectDevicesHardwareButton(
                                        connectedCount = state.approvedDevices.count { it.online },
                                        pendingCount = if (state.isAdmin) state.pendingDevices.size else 0,
                                        isPoweredOn = state.isPoweredOn,
                                        isPrivateMode = state.radioMode == RadioMode.PRIVATE,
                                        onClick = { activeOverlay = WalkieOverlay.DEVICES },
                                        modifier = Modifier.width(86.dp)
                                    )

                                    val canTransmit = state.isPoweredOn &&
                                        state.approvalStatus == DeviceApprovalStatus.APPROVED &&
                                        state.connectionStatus == ConnectionStatus.CONNECTED &&
                                        !state.isCallInterrupted

                                    HeroPttButton(
                                        isPoweredOn = state.isPoweredOn,
                                        isTransmitting = state.isTransmitting,
                                        isChannelBusyByOther = state.isChannelBusyByOther,
                                        canTransmit = canTransmit,
                                        hapticsEnabled = state.preferences.hapticsEnabled,
                                        onPttPressed = {
                                            val hasMic = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (!hasMic) {
                                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            } else {
                                                viewModel.setMicPermissionGranted(true)
                                                viewModel.onPttPressed()
                                            }
                                        },
                                        onPttReleased = {
                                            viewModel.onPttReleased()
                                        }
                                    )

                                    SettingsHardwareButton(
                                        isAdmin = state.isAdmin,
                                        pendingCount = state.pendingDevices.size,
                                        onClick = { activeOverlay = WalkieOverlay.SETTINGS_ADMIN },
                                        modifier = Modifier.width(86.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Overlay 1: Family Devices & Private Mode
        AnimatedVisibility(
            visible = activeOverlay == WalkieOverlay.DEVICES,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            DevicesAndPrivateOverlay(
                state = state,
                onSelectGroupChannel = { viewModel.selectGroupChannel(it) },
                onStartPrivateMode = { viewModel.startPrivateModeWith(it) },
                onCopiedDeviceId = {
                    viewModel.notifyUser("Device ID (${state.myDeviceId}) copied to clipboard")
                },
                onDismiss = { activeOverlay = WalkieOverlay.NONE }
            )
        }

        // Overlay 2: Settings & Admin Panel
        AnimatedVisibility(
            visible = activeOverlay == WalkieOverlay.SETTINGS_ADMIN,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            AdminAndSettingsOverlay(
                state = state,
                onApproveDevice = { viewModel.approveDevice(it) },
                onRemoveDevice = { viewModel.removeDevice(it) },
                onRenameDevice = { dev, name -> viewModel.renameDevice(dev, name) },
                onRenameChannel = { ch, name -> viewModel.renameChannel(ch, name) },
                onClearChannelFloor = { viewModel.clearChannelFloorAsAdmin(it) },
                onSaveIceConfig = { stun, turn, user, pass ->
                    viewModel.saveIceServerConfig(stun, turn, user, pass)
                },
                onKeepActiveInBackgroundToggle = { viewModel.setKeepActiveInBackground(it) },
                onAutoStartOnBootToggle = { viewModel.setAutoStartOnBoot(it) },
                onRequestMicPermission = {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onOpenBatterySettings = { openBatteryOptimizationSettings(context) },
                onRogerBeepToggle = { viewModel.setRogerBeepEnabled(it) },
                onHapticsToggle = { viewModel.setHapticsEnabled(it) },
                onSpeakerphoneToggle = { viewModel.setSpeakerphoneEnabled(it) },
                onSignOut = {
                    activeOverlay = WalkieOverlay.NONE
                    onRequestSignOut()
                },
                onDismiss = { activeOverlay = WalkieOverlay.NONE }
            )
        }
    }
}

@Composable
private fun HardwareAlertStrip(
    icon: ImageVector,
    text: String,
    accentColor: Color,
    testTag: String,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(accentColor.copy(alpha = 0.16f))
            .border(1.dp, accentColor.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = text,
            fontFamily = ShareTechMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = TextPrimaryLight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun openBatteryOptimizationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        openAppSettings(context)
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
