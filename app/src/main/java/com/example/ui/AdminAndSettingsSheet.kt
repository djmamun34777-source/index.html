package com.example.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.DeviceApprovalStatus
import com.example.data.MAX_FAMILY_DEVICES
import com.example.data.WalkieDevice
import com.example.ui.theme.BrandAmber
import com.example.ui.theme.ChassisBevelHighlight
import com.example.ui.theme.ChassisBodyDark
import com.example.ui.theme.ChassisObsidian
import com.example.ui.theme.GunmetalCard
import com.example.ui.theme.LcdInkDark
import com.example.ui.theme.OrbitronFontFamily
import com.example.ui.theme.PttNeonGreen
import com.example.ui.theme.ShareTechMonoFontFamily
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusOfflineGray
import com.example.ui.theme.StatusOnlineGreen
import com.example.ui.theme.TextPrimaryLight
import com.example.ui.theme.TextSecondaryLight
import com.example.viewmodel.WalkieUiState

@Composable
fun AdminAndSettingsOverlay(
    state: WalkieUiState,
    onApproveDevice: (WalkieDevice) -> Unit,
    onRemoveDevice: (WalkieDevice) -> Unit,
    onRenameDevice: (WalkieDevice, String) -> Unit,
    onRenameChannel: (Int, String) -> Unit,
    onClearChannelFloor: (Int) -> Unit,
    onSaveIceConfig: (String, String, String, String) -> Unit,
    onKeepActiveInBackgroundToggle: (Boolean) -> Unit,
    onAutoStartOnBootToggle: (Boolean) -> Unit,
    onRequestMicPermission: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRogerBeepToggle: (Boolean) -> Unit,
    onHapticsToggle: (Boolean) -> Unit,
    onSpeakerphoneToggle: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    var renameTargetDevice by remember { mutableStateOf<WalkieDevice?>(null) }
    var renameDeviceInput by remember { mutableStateOf("") }

    var renameChannelNumber by remember { mutableStateOf<Int?>(null) }
    var renameChannelInput by remember { mutableStateOf("") }

    var stunText by remember(state.iceConfig.stunUrls) {
        mutableStateOf(state.iceConfig.stunUrls.joinToString("\n"))
    }
    var turnUrl by remember(state.iceConfig.turnUrl) { mutableStateOf(state.iceConfig.turnUrl) }
    var turnUser by remember(state.iceConfig.turnUsername) { mutableStateOf(state.iceConfig.turnUsername) }
    var turnPass by remember(state.iceConfig.turnCredential) { mutableStateOf(state.iceConfig.turnCredential) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ChassisObsidian)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ChassisBodyDark)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("settings_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_close),
                    tint = TextPrimaryLight
                )
            }
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.isAdmin) stringResource(R.string.admin_panel_title) else stringResource(R.string.label_settings),
                    fontFamily = OrbitronFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = BrandAmber
                )
                Text(
                    text = if (state.isAdmin) "Admin Access • Max $MAX_FAMILY_DEVICES Family Phones" else "Device ID: ${state.myDeviceId}",
                    fontFamily = ShareTechMonoFontFamily,
                    fontSize = 11.sp,
                    color = TextSecondaryLight
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // --- ALWAYS-ACTIVE BACKGROUND & CALL COEXISTENCE ---
            item {
                val stunCount = state.audioTelemetry.stunCount.coerceAtLeast(state.iceConfig.stunUrls.size)
                val turnActive = state.audioTelemetry.turnConfigured || state.iceConfig.turnUrl.isNotBlank()

                Card(
                    colors = CardDefaults.cardColors(containerColor = GunmetalCard),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, ChassisBevelHighlight, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Shield, null, tint = PttNeonGreen, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "24/7 BACKGROUND & CALL COEXISTENCE",
                                fontFamily = OrbitronFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = PttNeonGreen
                            )
                        }
                        Spacer(Modifier.height(10.dp))

                        // Live STUN / TURN + Mic + Real WebRTC Peer/ICE Telemetry Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusMiniChip(
                                label = "STUN: $stunCount • TURN: ${if (turnActive) "ON" else "OFF"}",
                                color = if (turnActive) StatusOnlineGreen else BrandAmber,
                                modifier = Modifier.weight(1f)
                            )
                            StatusMiniChip(
                                label = when {
                                    state.isCallInterrupted -> "MIC: CALL RELEASED"
                                    state.micPermissionGranted -> "MIC: READY"
                                    else -> "MIC: DENIED"
                                },
                                color = when {
                                    state.isCallInterrupted -> BrandAmber
                                    state.micPermissionGranted -> StatusOnlineGreen
                                    else -> StatusDangerRed
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val livePeers = state.audioTelemetry.connectedPeerIds.size
                            StatusMiniChip(
                                label = "WEBRTC PEERS: $livePeers",
                                color = if (livePeers > 0) StatusOnlineGreen else TextSecondaryLight,
                                modifier = Modifier.weight(1f)
                            )
                            StatusMiniChip(
                                label = "ICE: ${state.audioTelemetry.iceConnectionLabel}",
                                color = when (state.audioTelemetry.iceConnectionLabel) {
                                    "P2P DIRECT", "TURN RELAY", "ICE LIVE" -> StatusOnlineGreen
                                    "ICE CHECKING" -> BrandAmber
                                    else -> TextSecondaryLight
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        PreferenceSwitchRow(
                            title = "Always Active in Background",
                            subtitle = "Keep foreground service, WakeLock & Wi-Fi Lock alive when minimized",
                            checked = state.preferences.keepActiveInBackground,
                            onCheckedChange = onKeepActiveInBackgroundToggle
                        )
                        HorizontalDivider(color = ChassisBevelHighlight.copy(alpha = 0.4f))
                        PreferenceSwitchRow(
                            title = "Auto-Start After Phone Reboot",
                            subtitle = "Reconnect walkie-talkie automatically on boot",
                            checked = state.preferences.autoStartOnBoot,
                            onCheckedChange = onAutoStartOnBootToggle
                        )
                        HorizontalDivider(color = ChassisBevelHighlight.copy(alpha = 0.4f))

                        // Battery Unrestricted Status
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.BatteryChargingFull,
                                contentDescription = null,
                                tint = if (state.batteryOptimizationIgnored) StatusOnlineGreen else BrandAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Battery Unrestricted Mode",
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimaryLight,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = if (state.batteryOptimizationIgnored) {
                                        "Unrestricted — Android will not sleep background voice"
                                    } else {
                                        "Tap Settings -> Select Mamun Walkie Talkie -> Unrestricted"
                                    },
                                    color = TextSecondaryLight,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = onOpenBatterySettings,
                                modifier = Modifier.testTag("open_battery_settings_button")
                            ) {
                                Text(
                                    text = if (state.batteryOptimizationIgnored) "Check" else "Fix",
                                    fontSize = 12.sp
                                )
                            }
                        }

                        HorizontalDivider(color = ChassisBevelHighlight.copy(alpha = 0.4f))

                        // Microphone Permission Readiness Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = if (state.micPermissionGranted) StatusOnlineGreen else StatusDangerRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Microphone Permission",
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimaryLight,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = if (state.micPermissionGranted) {
                                        "Granted & ready for instant PTT"
                                    } else {
                                        "Required — tap Grant to enable Push-to-Talk"
                                    },
                                    color = TextSecondaryLight,
                                    fontSize = 12.sp
                                )
                            }
                            if (!state.micPermissionGranted) {
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = onRequestMicPermission,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PttNeonGreen,
                                        contentColor = LcdInkDark
                                    ),
                                    modifier = Modifier.testTag("grant_mic_button")
                                ) {
                                    Text("Grant", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        HorizontalDivider(color = ChassisBevelHighlight.copy(alpha = 0.4f))
                        Spacer(Modifier.height(10.dp))

                        // IMO / WhatsApp / Messenger / SIM Auto Mic Handover Info
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(ChassisBodyDark)
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneInTalk,
                                contentDescription = null,
                                tint = BrandAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Auto Call Handover (IMO, WhatsApp, Messenger & SIM): Hardware microphone is released instantly when any third-party call arrives and automatically reclaimed as soon as the call finishes.",
                                color = TextSecondaryLight,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            // --- RADIO AUDIO SETTINGS ---
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = GunmetalCard),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, ChassisBevelHighlight, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VolumeUp, null, tint = PttNeonGreen, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "RADIO AUDIO & PTT PREFERENCES",
                                fontFamily = OrbitronFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = PttNeonGreen
                            )
                        }
                        Spacer(Modifier.height(10.dp))

                        PreferenceSwitchRow(
                            title = "Roger Beep & PTT Chirp",
                            subtitle = "Play tactile radio tones on press/release",
                            checked = state.preferences.rogerBeepEnabled,
                            onCheckedChange = onRogerBeepToggle
                        )
                        HorizontalDivider(color = ChassisBevelHighlight.copy(alpha = 0.4f))
                        PreferenceSwitchRow(
                            title = "Haptic Vibration Feedback",
                            subtitle = "Vibrate when pressing PTT or switching channels",
                            checked = state.preferences.hapticsEnabled,
                            onCheckedChange = onHapticsToggle
                        )
                        HorizontalDivider(color = ChassisBevelHighlight.copy(alpha = 0.4f))
                        PreferenceSwitchRow(
                            title = "Loudspeaker Mode",
                            subtitle = "Route live incoming voice to walkie-talkie speaker",
                            checked = state.preferences.speakerphoneEnabled,
                            onCheckedChange = onSpeakerphoneToggle
                        )

                        // Self rename option
                        state.myDevice?.let { selfDevice ->
                            HorizontalDivider(color = ChassisBevelHighlight.copy(alpha = 0.4f))
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("This Device Name", fontWeight = FontWeight.SemiBold, color = TextPrimaryLight)
                                    Text(
                                        text = "${selfDevice.deviceName} (${selfDevice.id})",
                                        fontFamily = ShareTechMonoFontFamily,
                                        fontSize = 11.sp,
                                        color = TextSecondaryLight
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        renameTargetDevice = selfDevice
                                        renameDeviceInput = selfDevice.deviceName
                                    }
                                ) {
                                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Rename", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // --- ADMIN PANEL SECTION ---
            if (state.isAdmin) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AdminPanelSettings, null, tint = BrandAmber, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "ADMIN PANEL • DEVICE SLOTS (${state.approvedDevices.size}/$MAX_FAMILY_DEVICES)",
                            fontFamily = OrbitronFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = BrandAmber
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Approve up to 6 family Android devices. A 7th device is blocked until an approved device is removed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondaryLight
                    )
                }

                // Pending Devices Waiting Approval
                if (state.pendingDevices.isNotEmpty()) {
                    item {
                        Text(
                            text = "PENDING APPROVAL (${state.pendingDevices.size})",
                            fontFamily = ShareTechMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = BrandAmber
                        )
                    }
                    items(state.pendingDevices, key = { "pending_${it.id}" }) { device ->
                        AdminDeviceRow(
                            device = device,
                            canApprove = state.approvedDevices.size < MAX_FAMILY_DEVICES && !state.isBusySaving,
                            onApprove = { onApproveDevice(device) },
                            onRemove = { onRemoveDevice(device) },
                            onRename = {
                                renameTargetDevice = device
                                renameDeviceInput = device.deviceName
                            }
                        )
                    }
                }

                // Approved 1..6 Devices
                item {
                    Text(
                        text = "APPROVED FAMILY DEVICES (${state.approvedDevices.size}/$MAX_FAMILY_DEVICES)",
                        fontFamily = ShareTechMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = PttNeonGreen
                    )
                }
                items(state.approvedDevices, key = { "approved_${it.id}" }) { device ->
                    AdminDeviceRow(
                        device = device,
                        canApprove = false,
                        onApprove = {},
                        onRemove = { onRemoveDevice(device) },
                        onRename = {
                            renameTargetDevice = device
                            renameDeviceInput = device.deviceName
                        }
                    )
                }

                // Channel Floor & Names Management
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = GunmetalCard),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, ChassisBevelHighlight, RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "CHANNELS 1–6 & FLOOR LOCK CONTROL",
                                fontFamily = OrbitronFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = BrandAmber
                            )
                            state.channels.forEach { ch ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = ch.name.ifBlank { "Channel ${ch.channelNumber}" },
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimaryLight,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (ch.activeSpeakerName.isNotBlank()) {
                                                "Talking now: ${ch.activeSpeakerName}"
                                            } else {
                                                "Idle • Ready"
                                            },
                                            fontFamily = ShareTechMonoFontFamily,
                                            fontSize = 11.sp,
                                            color = if (ch.activeSpeakerName.isNotBlank()) BrandAmber else TextSecondaryLight
                                        )
                                    }
                                    if (ch.activeSpeakerDeviceId.isNotBlank()) {
                                        TextButton(onClick = { onClearChannelFloor(ch.channelNumber.toInt()) }) {
                                            Text("Unlock", color = StatusDangerRed, fontSize = 12.sp)
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            renameChannelNumber = ch.channelNumber.toInt()
                                            renameChannelInput = ch.name
                                        }
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Rename Channel", tint = TextPrimaryLight)
                                    }
                                }
                            }
                        }
                    }
                }

                // WebRTC STUN / TURN Configuration
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = GunmetalCard),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, ChassisBevelHighlight, RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Router, null, tint = PttNeonGreen, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "WEBRTC STUN / TURN NETWORK CONFIG",
                                    fontFamily = OrbitronFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = PttNeonGreen
                                )
                            }
                            Text(
                                text = "Pre-configured out-of-the-box with 5 multi-region STUN servers + multi-transport OpenRelay TURN (UDP/TCP/TLS 443) for Wi-Fi & Mobile Data NAT traversal. You can override with your own TURN credentials below anytime.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondaryLight
                            )

                            OutlinedTextField(
                                value = stunText,
                                onValueChange = { stunText = it },
                                label = { Text("STUN Servers (one per line)") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2
                            )
                            OutlinedTextField(
                                value = turnUrl,
                                onValueChange = { turnUrl = it },
                                label = { Text("TURN Server URL (e.g. turn:openrelay.metered.ca:443)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = turnUser,
                                onValueChange = { turnUser = it },
                                label = { Text("TURN Username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = turnPass,
                                onValueChange = { turnPass = it },
                                label = { Text("TURN Credential") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    onSaveIceConfig(stunText, turnUrl, turnUser, turnPass)
                                },
                                enabled = !state.isBusySaving,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PttNeonGreen,
                                    contentColor = LcdInkDark
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("save_ice_config_button")
                            ) {
                                Text("Save STUN / TURN Config", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // --- SIGN OUT ---
            item {
                OutlinedButton(
                    onClick = onSignOut,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusDangerRed),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("sign_out_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sign_out), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // Rename Device Dialog
    renameTargetDevice?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTargetDevice = null },
            title = { Text("Rename Family Device") },
            text = {
                OutlinedTextField(
                    value = renameDeviceInput,
                    onValueChange = { renameDeviceInput = it.take(40) },
                    label = { Text("Device Callsign / Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameDevice(target, renameDeviceInput)
                        renameTargetDevice = null
                    },
                    enabled = renameDeviceInput.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetDevice = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Rename Channel Dialog
    renameChannelNumber?.let { chNum ->
        AlertDialog(
            onDismissRequest = { renameChannelNumber = null },
            title = { Text("Rename Channel $chNum") },
            text = {
                OutlinedTextField(
                    value = renameChannelInput,
                    onValueChange = { renameChannelInput = it.take(40) },
                    label = { Text("Channel Label") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameChannel(chNum, renameChannelInput)
                        renameChannelNumber = null
                    },
                    enabled = renameChannelInput.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameChannelNumber = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StatusMiniChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontFamily = ShareTechMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = TextPrimaryLight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AdminDeviceRow(
    device: WalkieDevice,
    canApprove: Boolean,
    onApprove: () -> Unit,
    onRemove: () -> Unit,
    onRename: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GunmetalCard),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ChassisBevelHighlight, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (device.online) StatusOnlineGreen else StatusOfflineGray)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (device.slotNumber in 1..MAX_FAMILY_DEVICES) {
                            "Slot #${device.slotNumber} • ${device.deviceName}"
                        } else {
                            device.deviceName
                        },
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryLight
                    )
                    Text(
                        text = "ID: ${device.id} • ${if (device.online) "ONLINE" else "OFFLINE"} • CH ${device.currentChannel}",
                        fontFamily = ShareTechMonoFontFamily,
                        fontSize = 11.sp,
                        color = TextSecondaryLight
                    )
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename Device", tint = TextPrimaryLight)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (device.approvalStatus != DeviceApprovalStatus.APPROVED) {
                    Button(
                        onClick = onApprove,
                        enabled = canApprove,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PttNeonGreen,
                            contentColor = LcdInkDark
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("approve_device_${device.id}")
                    ) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (canApprove) "Approve Slot" else "All 6 Slots Full", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusDangerRed),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("remove_device_${device.id}")
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Remove", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PreferenceSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.SemiBold, color = TextPrimaryLight, fontSize = 14.sp)
            Text(text = subtitle, color = TextSecondaryLight, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
