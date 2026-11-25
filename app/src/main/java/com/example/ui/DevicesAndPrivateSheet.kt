package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.DeviceApprovalStatus
import com.example.data.MAX_CHANNEL
import com.example.data.MAX_FAMILY_DEVICES
import com.example.data.MIN_CHANNEL
import com.example.data.RadioMode
import com.example.data.WalkieDevice
import com.example.ui.theme.BrandAmber
import com.example.ui.theme.ChassisBevelHighlight
import com.example.ui.theme.ChassisBodyDark
import com.example.ui.theme.ChassisObsidian
import com.example.ui.theme.GunmetalCard
import com.example.ui.theme.LcdInkDark
import com.example.ui.theme.OrbitronFontFamily
import com.example.ui.theme.PrivateCyan
import com.example.ui.theme.PttNeonGreen
import com.example.ui.theme.ShareTechMonoFontFamily
import com.example.ui.theme.StatusOfflineGray
import com.example.ui.theme.StatusOnlineGreen
import com.example.ui.theme.TextPrimaryLight
import com.example.ui.theme.TextSecondaryLight
import com.example.viewmodel.WalkieUiState

@Composable
fun DevicesAndPrivateOverlay(
    state: WalkieUiState,
    onSelectGroupChannel: (Int) -> Unit,
    onStartPrivateMode: (WalkieDevice) -> Unit,
    onCopiedDeviceId: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ChassisObsidian)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ChassisBodyDark)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("devices_back_button")
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
                    text = stringResource(R.string.devices_sheet_title),
                    fontFamily = OrbitronFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = BrandAmber
                )
                Text(
                    text = "${state.approvedDevices.size}/$MAX_FAMILY_DEVICES Approved • ${state.approvedDevices.count { it.online }} Online",
                    fontFamily = ShareTechMonoFontFamily,
                    fontSize = 12.sp,
                    color = TextSecondaryLight
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Own Device ID Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = GunmetalCard),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, ChassisBevelHighlight, RoundedCornerShape(16.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = PttNeonGreen,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "THIS PHONE • ${state.myDeviceName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = BrandAmber
                            )
                            Text(
                                text = state.myDeviceId.ifBlank { "Generating ID..." },
                                fontFamily = ShareTechMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = TextPrimaryLight
                            )
                            Text(
                                text = when (state.approvalStatus) {
                                    DeviceApprovalStatus.APPROVED -> "Status: APPROVED (Slot #${state.myDevice?.slotNumber ?: 0})"
                                    DeviceApprovalStatus.PENDING -> "Status: PENDING ADMIN APPROVAL"
                                    DeviceApprovalStatus.REVOKED -> "Status: REVOKED BY ADMIN"
                                },
                                fontSize = 11.sp,
                                color = if (state.approvalStatus == DeviceApprovalStatus.APPROVED) StatusOnlineGreen else BrandAmber
                            )
                        }
                        IconButton(
                            onClick = {
                                if (state.myDeviceId.isNotBlank()) {
                                    clipboard.setText(AnnotatedString(state.myDeviceId))
                                    onCopiedDeviceId()
                                }
                            },
                            modifier = Modifier.testTag("copy_device_id_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Device ID",
                                tint = TextPrimaryLight
                            )
                        }
                    }
                }
            }

            // Group Mode Channels 1..6 Selector
            item {
                Text(
                    text = "GROUP MODE — SELECT CHANNEL (1–6)",
                    fontFamily = OrbitronFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = PttNeonGreen
                )
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.height(148.dp)) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        userScrollEnabled = false
                    ) {
                        items((MIN_CHANNEL..MAX_CHANNEL).toList()) { ch ->
                            val selected = state.radioMode == RadioMode.GROUP && state.activeChannel == ch
                            val countInCh = state.approvedDevices.count {
                                it.online && it.radioMode == RadioMode.GROUP && it.currentChannel.toInt() == ch
                            }
                            val chMeta = state.channels.firstOrNull { it.channelNumber.toInt() == ch }
                            val speaking = chMeta?.activeSpeakerName?.takeIf { it.isNotBlank() }

                            Box(
                                modifier = Modifier
                                    .height(68.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) PttNeonGreen.copy(alpha = 0.22f) else GunmetalCard)
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) PttNeonGreen else ChassisBevelHighlight,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        onSelectGroupChannel(ch)
                                        onDismiss()
                                    }
                                    .padding(8.dp)
                                    .testTag("select_channel_$ch"),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "CH $ch",
                                        fontFamily = OrbitronFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = if (selected) PttNeonGreen else TextPrimaryLight
                                    )
                                    Text(
                                        text = if (speaking != null) "TX: $speaking" else "$countInCh online",
                                        fontFamily = ShareTechMonoFontFamily,
                                        fontSize = 10.sp,
                                        color = if (speaking != null) BrandAmber else TextSecondaryLight,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Private Mode Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = PrivateCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "PRIVATE MODE (1-TO-1 ISOLATED)",
                            fontFamily = OrbitronFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = PrivateCyan
                        )
                    }
                    if (state.radioMode == RadioMode.PRIVATE) {
                        OutlinedButton(
                            onClick = { onSelectGroupChannel(state.activeChannel) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("exit_private_mode_button")
                        ) {
                            Text("Exit Private", fontSize = 11.sp, color = BrandAmber)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Select an approved family device to open a private 1-to-1 voice link. Other devices cannot hear Private Mode audio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondaryLight
                )
            }

            if (state.approvedDevices.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = GunmetalCard),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Groups,
                                contentDescription = null,
                                tint = TextSecondaryLight,
                                modifier = Modifier.size(34.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "No approved family devices visible yet.",
                                color = TextPrimaryLight,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Ask the Admin to approve your Device ID in the Admin Panel.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondaryLight
                            )
                        }
                    }
                }
            } else {
                items(state.approvedDevices, key = { it.id }) { device ->
                    val isMe = device.id == state.myDeviceId
                    val isActivePrivateTarget =
                        state.radioMode == RadioMode.PRIVATE && state.privateTargetDeviceId == device.id
                    val isWebRtcLinked = device.id in state.audioTelemetry.connectedPeerIds

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActivePrivateTarget) Color(0xFF0E2B36) else GunmetalCard
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = if (isActivePrivateTarget) 1.8.dp else 1.dp,
                                color = if (isActivePrivateTarget) PrivateCyan else ChassisBevelHighlight,
                                shape = RoundedCornerShape(14.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(if (device.online) StatusOnlineGreen else StatusOfflineGray)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Slot #${device.slotNumber} • ${device.deviceName}",
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimaryLight,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isMe) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "(YOU)",
                                            fontFamily = ShareTechMonoFontFamily,
                                            fontSize = 11.sp,
                                            color = BrandAmber
                                        )
                                    }
                                }
                                Text(
                                    text = "ID: ${device.id}",
                                    fontFamily = ShareTechMonoFontFamily,
                                    fontSize = 11.sp,
                                    color = TextSecondaryLight
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = if (device.radioMode == RadioMode.PRIVATE) "Private Mode" else "CH ${device.currentChannel}",
                                        fontSize = 11.sp,
                                        color = if (device.radioMode == RadioMode.PRIVATE) PrivateCyan else PttNeonGreen
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.BatteryStd,
                                            contentDescription = null,
                                            tint = TextSecondaryLight,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = "${device.batteryLevel}%",
                                            fontSize = 11.sp,
                                            color = TextSecondaryLight
                                        )
                                    }
                                    if (isWebRtcLinked) {
                                        Text(
                                            text = "WebRTC LIVE",
                                            fontFamily = ShareTechMonoFontFamily,
                                            fontSize = 10.sp,
                                            color = PttNeonGreen
                                        )
                                    }
                                }
                            }

                            if (!isMe) {
                                Button(
                                    onClick = {
                                        onStartPrivateMode(device)
                                        onDismiss()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isActivePrivateTarget) PrivateCyan else BrandAmber,
                                        contentColor = LcdInkDark
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.testTag("private_connect_${device.id}")
                                ) {
                                    Icon(
                                        imageVector = if (isActivePrivateTarget) Icons.Default.Lock else Icons.Default.Radio,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (isActivePrivateTarget) "Active" else "Private",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
