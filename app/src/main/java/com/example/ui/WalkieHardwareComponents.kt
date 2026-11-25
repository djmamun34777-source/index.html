package com.example.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ConnectionStatus
import com.example.data.MAX_FAMILY_DEVICES
import com.example.data.NetworkTransport
import com.example.data.RadioMode
import com.example.ui.theme.BrandAmber
import com.example.ui.theme.ChassisBevelHighlight
import com.example.ui.theme.ChassisBevelShadow
import com.example.ui.theme.ChassisBodyDark
import com.example.ui.theme.ChassisBodyMid
import com.example.ui.theme.ChassisObsidian
import com.example.ui.theme.LcdGreenBright
import com.example.ui.theme.LcdGreenDeep
import com.example.ui.theme.LcdGreenMid
import com.example.ui.theme.LcdInkDark
import com.example.ui.theme.LcdInkMuted
import com.example.ui.theme.LcdOffDark
import com.example.ui.theme.LcdOffText
import com.example.ui.theme.OrbitronFontFamily
import com.example.ui.theme.PowerRedDark
import com.example.ui.theme.PowerRedGlow
import com.example.ui.theme.PttBusyAmber
import com.example.ui.theme.PttGreenGlow
import com.example.ui.theme.PttNeonGreen
import com.example.ui.theme.PttTxRed
import com.example.ui.theme.ShareTechMonoFontFamily
import com.example.ui.theme.TextPrimaryLight
import com.example.ui.theme.TextSecondaryLight
import com.example.ui.theme.VolumeBlueDeep
import com.example.ui.theme.VolumeBlueGlow
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Top hardware deck: ribbed antenna on left, metallic center housing cap,
 * rotary red-ring frequency knob on right, and recessed "Mamun Walkie Talkie" brand bezel.
 */
@Composable
fun RuggedWalkieTopHeader(
    isTransmitting: Boolean,
    isPoweredOn: Boolean,
    localAudioLevel: Float,
    onTopKnobClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val txSignalStrength by animateFloatAsState(
        targetValue = if (isPoweredOn && isTransmitting) {
            (0.28f + 0.72f * localAudioLevel.coerceIn(0f, 1f))
        } else {
            0f
        },
        animationSpec = tween(durationMillis = 90),
        label = "real_tx_signal"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // Antenna & Top Rotary Knob Deck
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            // Left: Ribbed Rubber Antenna Silhouette
            Box(
                modifier = Modifier
                    .width(74.dp)
                    .height(48.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    // Tapered antenna body
                    val path = Path().apply {
                        moveTo(w * 0.24f, 0f)
                        lineTo(w * 0.66f, 0f)
                        lineTo(w * 0.80f, h)
                        lineTo(w * 0.10f, h)
                        close()
                    }
                    drawPath(
                        path = path,
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color(0xFF0A0D10), Color(0xFF39414A), Color(0xFF14181D))
                        )
                    )
                    // Rubberized grip ribs
                    val ribCount = 5
                    for (i in 1..ribCount) {
                        val y = h * (i / (ribCount + 1f))
                        drawLine(
                            color = Color(0xFF050608),
                            start = Offset(w * 0.17f, y),
                            end = Offset(w * 0.73f, y),
                            strokeWidth = 3f
                        )
                    }
                    // Active TX RF halo driven by real microphone PCM amplitude
                    if (txSignalStrength > 0.01f) {
                        drawCircle(
                            color = PttTxRed.copy(alpha = 0.55f * txSignalStrength),
                            radius = w * (0.24f + 0.18f * txSignalStrength),
                            center = Offset(w * 0.45f, h * 0.25f)
                        )
                    }
                }
            }

            // Center LED Indicator Dome
            Box(
                modifier = Modifier
                    .padding(bottom = 2.dp)
                    .width(68.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                    .background(
                        Brush.verticalGradient(listOf(ChassisBevelHighlight, ChassisBodyDark))
                    )
                    .border(1.dp, Color(0xFF535D6B), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(26.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                !isPoweredOn -> Color(0xFF2B313A)
                                isTransmitting -> PttTxRed.copy(alpha = (0.55f + 0.45f * txSignalStrength).coerceIn(0.55f, 1f))
                                else -> BrandAmber
                            }
                        )
                )
            }

            // Right: Knurled Rotary Top Channel Knob with Red Accent Collar
            Box(
                modifier = Modifier
                    .width(74.dp)
                    .height(46.dp)
                    .clickable(onClick = onTopKnobClick)
                    .semantics {
                        contentDescription = "Quick Cycle Channel Knob"
                        role = Role.Button
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    // Knurled cylinder
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color(0xFF0D1014), Color(0xFF434C57), Color(0xFF1A1F26), Color(0xFF0A0C0F))
                        ),
                        topLeft = Offset(w * 0.14f, h * 0.08f),
                        size = Size(w * 0.72f, h * 0.68f),
                        cornerRadius = CornerRadius(10f, 10f)
                    )
                    // Vertical grip grooves
                    for (i in 1..6) {
                        val x = w * 0.18f + (w * 0.64f * i / 7f)
                        drawLine(
                            color = Color(0xFF090B0E),
                            start = Offset(x, h * 0.12f),
                            end = Offset(x, h * 0.72f),
                            strokeWidth = 3f
                        )
                    }
                    // Signature Red Accent Ring
                    drawRect(
                        color = Color(0xFFE52222),
                        topLeft = Offset(w * 0.12f, h * 0.76f),
                        size = Size(w * 0.76f, h * 0.11f)
                    )
                    // Base collar
                    drawRect(
                        color = Color(0xFF1A1F26),
                        topLeft = Offset(w * 0.08f, h * 0.87f),
                        size = Size(w * 0.84f, h * 0.13f)
                    )
                }
            }
        }

        // Brand Nameplate Bar with Flanked Chassis Screws
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ChassisScrew()

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF15191F), Color(0xFF0B0D11)))
                    )
                    .border(1.dp, Color(0xFF3A434E), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sensors,
                            contentDescription = null,
                            tint = if (isPoweredOn) BrandAmber else TextSecondaryLight,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.brand_mamun) + " ",
                            fontFamily = OrbitronFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = TextPrimaryLight,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.brand_walkie_talkie),
                            fontFamily = OrbitronFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = BrandAmber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = stringResource(R.string.brand_tagline),
                        fontSize = 10.sp,
                        fontStyle = FontStyle.Italic,
                        color = Color(0xFFCED6E0),
                        letterSpacing = 0.7.sp
                    )
                }
            }

            ChassisScrew()
        }
    }
}

@Composable
private fun ChassisScrew() {
    Canvas(modifier = Modifier.size(20.dp)) {
        val r = size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xFF4A5360), Color(0xFF12161B))),
            radius = r
        )
        drawCircle(
            color = Color(0xFF090B0E),
            radius = r * 0.65f,
            style = Stroke(width = 2f)
        )
        // Hex / slotted screw head
        drawLine(
            color = Color(0xFF080A0D),
            start = Offset(r * 0.45f, r),
            end = Offset(r * 1.55f, r),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFF080A0D),
            start = Offset(r, r * 0.45f),
            end = Offset(r, r * 1.55f),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Recessed Glowing Green Backlit LCD Channel Display matching the iconic hardware design.
 */
@Composable
fun BacklitLcdDisplay(
    isPoweredOn: Boolean,
    connectionStatus: ConnectionStatus,
    radioMode: RadioMode,
    activeChannel: Int,
    privateTargetName: String,
    onlineInChannel: Int,
    batteryPercent: Int,
    isCharging: Boolean,
    signalBars: Int,
    networkTransport: NetworkTransport,
    isTransmitting: Boolean,
    activeSpeakerName: String,
    isChannelBusyByOther: Boolean,
    micPermissionGranted: Boolean,
    localAudioLevel: Float,
    remoteAudioLevel: Float,
    modifier: Modifier = Modifier
) {
    val liveVuLevel by animateFloatAsState(
        targetValue = when {
            !isPoweredOn -> 0f
            isTransmitting -> localAudioLevel.coerceIn(0f, 1f)
            isChannelBusyByOther -> remoteAudioLevel.coerceIn(0f, 1f)
            else -> 0f
        },
        animationSpec = tween(durationMillis = 80),
        label = "real_vu_level"
    )

    val lcdBg = if (isPoweredOn) {
        Brush.verticalGradient(listOf(LcdGreenBright, LcdGreenMid, LcdGreenDeep))
    } else {
        Brush.verticalGradient(listOf(LcdOffDark, Color(0xFF0E130E)))
    }
    val inkColor = if (isPoweredOn) LcdInkDark else LcdOffText
    val mutedInk = if (isPoweredOn) LcdInkMuted else LcdOffText.copy(alpha = 0.6f)

    val topStatusLabel = when {
        !isPoweredOn -> "OFF"
        isTransmitting -> "TX LIVE"
        isChannelBusyByOther -> "RX LIVE"
        connectionStatus == ConnectionStatus.CONNECTED -> stringResource(R.string.lcd_connected)
        connectionStatus == ConnectionStatus.OFFLINE -> "Offline"
        connectionStatus == ConnectionStatus.PENDING_APPROVAL -> "Pending"
        connectionStatus == ConnectionStatus.REVOKED -> "Revoked"
        connectionStatus == ConnectionStatus.CALL_PAUSED -> "Call Paused"
        else -> stringResource(R.string.lcd_connecting)
    }

    val netBadge = when (networkTransport) {
        NetworkTransport.WIFI -> "WiFi"
        NetworkTransport.CELLULAR -> "4G"
        NetworkTransport.OFFLINE -> "NO NET"
    }

    val bottomTicker = when {
        !isPoweredOn -> stringResource(R.string.lcd_powered_off)
        connectionStatus == ConnectionStatus.OFFLINE -> stringResource(R.string.lcd_offline)
        connectionStatus == ConnectionStatus.PENDING_APPROVAL -> stringResource(R.string.lcd_pending_approval)
        connectionStatus == ConnectionStatus.REVOKED -> stringResource(R.string.lcd_revoked)
        connectionStatus == ConnectionStatus.CALL_PAUSED -> stringResource(R.string.lcd_call_paused)
        isTransmitting -> stringResource(R.string.lcd_tx_live)
        isChannelBusyByOther -> "RX: ${activeSpeakerName.ifBlank { "Family Member" }} Speaking"
        !micPermissionGranted -> stringResource(R.string.lcd_mic_permission_needed)
        else -> stringResource(R.string.lcd_press_ptt)
    }

    // Outer metallic recessed bezel
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF090C10))
            .border(2.dp, ChassisBevelHighlight, RoundedCornerShape(16.dp))
            .padding(5.dp)
            .testTag("walkie_lcd_display")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(lcdBg)
                .border(1.5.dp, Color(0xFF1F380E), RoundedCornerShape(11.dp))
        ) {
            // Subtle LCD pixel scanline texture
            Canvas(modifier = Modifier.matchParentSize()) {
                if (isPoweredOn) {
                    var y = 4f
                    while (y < size.height) {
                        drawLine(
                            color = Color.Black.copy(alpha = 0.045f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                        y += 6f
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                // Row 1: Signal Bars + Connection Status | Net + Battery
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SignalMeterBars(
                            bars = if (isPoweredOn) signalBars else 0,
                            activeColor = inkColor,
                            inactiveColor = mutedInk.copy(alpha = 0.35f)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = topStatusLabel,
                            fontFamily = ShareTechMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = inkColor,
                            maxLines = 1
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = netBadge,
                            fontFamily = ShareTechMonoFontFamily,
                            fontSize = 11.sp,
                            color = mutedInk
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                            contentDescription = if (isCharging) "Battery $batteryPercent percent charging" else "Battery $batteryPercent percent",
                            tint = inkColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = "$batteryPercent%",
                            fontFamily = ShareTechMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = inkColor
                        )
                    }
                }

                Spacer(Modifier.height(5.dp))
                HorizontalDivider(color = inkColor.copy(alpha = 0.28f), thickness = 1.dp)
                Spacer(Modifier.height(6.dp))

                // Row 2: Main Channel / Private Readout + (online/6)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (radioMode == RadioMode.PRIVATE) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Private Mode",
                                tint = inkColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "PVT: ${privateTargetName.ifBlank { "SELECT" }}",
                                fontFamily = ShareTechMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                color = inkColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Text(
                            text = "Channel : $activeChannel",
                            fontFamily = ShareTechMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 27.sp,
                            color = inkColor,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = "Devices in channel",
                            tint = inkColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (radioMode == RadioMode.PRIVATE) "(1-1)" else "($onlineInChannel/$MAX_FAMILY_DEVICES)",
                            fontFamily = ShareTechMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = inkColor
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = inkColor.copy(alpha = 0.28f), thickness = 1.dp)
                Spacer(Modifier.height(6.dp))

                // Row 3: Bottom Ticker / Live Audio VU Bars
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isPoweredOn && (isTransmitting || isChannelBusyByOther)) {
                        AudioActivityMiniBars(level = liveVuLevel, color = inkColor)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Box(
                            modifier = Modifier
                                .width(20.dp)
                                .height(1.5.dp)
                                .background(inkColor.copy(alpha = 0.65f))
                        )
                        Spacer(Modifier.width(8.dp))
                    }

                    Text(
                        text = bottomTicker,
                        fontFamily = ShareTechMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = inkColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )

                    if (isPoweredOn && (isTransmitting || isChannelBusyByOther)) {
                        Spacer(Modifier.width(8.dp))
                        AudioActivityMiniBars(level = liveVuLevel, mirrored = true, color = inkColor)
                    } else {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .width(20.dp)
                                .height(1.5.dp)
                                .background(inkColor.copy(alpha = 0.65f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalMeterBars(
    bars: Int,
    activeColor: Color,
    inactiveColor: Color
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val heights = listOf(6.dp, 9.dp, 12.dp, 15.dp)
        heights.forEachIndexed { idx, h ->
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(h)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (idx < bars) activeColor else inactiveColor)
            )
        }
    }
}

@Composable
private fun AudioActivityMiniBars(
    level: Float,
    color: Color,
    mirrored: Boolean = false
) {
    val clamped = level.coerceIn(0f, 1f)
    val rawFactors = listOf(
        (0.20f + 0.80f * (clamped * 1.15f).coerceAtMost(1f)),
        (0.20f + 0.80f * clamped),
        (0.20f + 0.80f * (clamped * 0.72f))
    )
    val factors = if (mirrored) rawFactors.reversed() else rawFactors
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        factors.forEach { f ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((13f * f).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
    }
}

/**
 * Red Illuminated Power ON/OFF Hardware Button.
 */
@Composable
fun PowerHardwareButton(
    isPoweredOn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cd = stringResource(R.string.cd_power_button)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(if (isPoweredOn) 10.dp else 2.dp, RoundedCornerShape(14.dp), ambientColor = PowerRedGlow, spotColor = PowerRedGlow)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (isPoweredOn) {
                        Brush.verticalGradient(listOf(Color(0xFFFF4D4D), PowerRedGlow, PowerRedDark))
                    } else {
                        Brush.verticalGradient(listOf(Color(0xFF3A1A1A), Color(0xFF1F1111)))
                    }
                )
                .border(2.dp, if (isPoweredOn) Color(0xFFFF9E9E) else ChassisBevelHighlight, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = cd
                    role = Role.Button
                }
                .testTag("power_toggle_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = null,
                tint = if (isPoweredOn) Color.White else Color(0xFF996666),
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.power_on_off),
            fontFamily = ShareTechMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = TextPrimaryLight
        )
    }
}

/**
 * Tactile Dual Rocker Channel Switch (▲ CH ▼).
 */
@Composable
fun ChannelRockerSwitch(
    enabled: Boolean,
    onChannelUp: () -> Unit,
    onChannelDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cdUp = stringResource(R.string.cd_channel_up)
    val cdDown = stringResource(R.string.cd_channel_down)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .width(66.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF2E353F), Color(0xFF13171D)))
                )
                .border(1.8.dp, ChassisBevelHighlight, RoundedCornerShape(16.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable(enabled = enabled, onClick = onChannelUp)
                    .semantics {
                        contentDescription = cdUp
                        role = Role.Button
                    }
                    .testTag("channel_up_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    tint = if (enabled) TextPrimaryLight else TextSecondaryLight.copy(alpha = 0.4f),
                    modifier = Modifier.size(30.dp)
                )
            }

            Text(
                text = stringResource(R.string.label_ch_short),
                fontFamily = OrbitronFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (enabled) BrandAmber else TextSecondaryLight.copy(alpha = 0.4f)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable(enabled = enabled, onClick = onChannelDown)
                    .semantics {
                        contentDescription = cdDown
                        role = Role.Button
                    }
                    .testTag("channel_down_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = if (enabled) TextPrimaryLight else TextSecondaryLight.copy(alpha = 0.4f),
                    modifier = Modifier.size(30.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.label_channel),
            fontFamily = ShareTechMonoFontFamily,
            fontSize = 11.sp,
            color = TextPrimaryLight
        )
    }
}

/**
 * Horizontal Recessed Acoustic Speaker Grill (responds directly to real incoming WebRTC RX audio level).
 */
@Composable
fun AcousticSpeakerGrill(
    isReceivingAudio: Boolean,
    remoteAudioLevel: Float,
    isPoweredOn: Boolean,
    modifier: Modifier = Modifier
) {
    val smoothedRx by animateFloatAsState(
        targetValue = if (isPoweredOn && isReceivingAudio) remoteAudioLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 90),
        label = "real_speaker_rx"
    )

    Column(
        modifier = modifier
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val widths = listOf(0.88f, 1f, 1f, 1f, 1f, 0.88f)
        widths.forEachIndexed { idx, frac ->
            val slotWeight = when (idx) {
                2, 3 -> 1f
                1, 4 -> 0.55f
                else -> 0.25f
            }
            val slotGlow = (smoothedRx * slotWeight).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF06080B))
                    .border(
                        width = if (slotGlow > 0.05f) 1.dp else 0.8.dp,
                        color = if (slotGlow > 0.05f) {
                            PttNeonGreen.copy(alpha = (0.22f + 0.78f * slotGlow).coerceIn(0f, 1f))
                        } else {
                            Color(0xFF313944)
                        },
                        shape = RoundedCornerShape(4.dp)
                    )
            )
        }
    }
}

/**
 * Tactile Rotary Volume Knob with Electric Blue LED Ring + `-` / `+` fine step controls.
 */
@Composable
fun RotaryVolumeKnob(
    volumePercent: Int,
    enabled: Boolean,
    onVolumeChange: (Int) -> Unit,
    onStepVolume: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val cdUp = stringResource(R.string.cd_volume_up)
    val cdDown = stringResource(R.string.cd_volume_down)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(102.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures { change, _ ->
                        change.consume()
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val dx = change.position.x - cx
                        val dy = change.position.y - cy
                        // Map -135 deg..+135 deg around top to 0..100%
                        val angleDeg = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI).toFloat()
                        val shifted = (angleDeg + 225f + 360f) % 360f
                        if (shifted <= 270f) {
                            val pct = ((shifted / 270f) * 100f).roundToInt().coerceIn(0, 100)
                            onVolumeChange(pct)
                        }
                    }
                }
                .testTag("volume_rotary_knob"),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeW = 7.dp.toPx()
                val inset = strokeW / 2f + 4.dp.toPx()
                val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)

                // Track background arc
                drawArc(
                    color = Color(0xFF141C28),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )

                // Illuminated electric blue LED progress arc
                val sweep = 270f * (volumePercent / 100f)
                if (enabled && sweep > 1f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(VolumeBlueDeep, VolumeBlueGlow, Color(0xFF66C2FF), VolumeBlueGlow)
                        ),
                        startAngle = 135f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                }

                // Brushed Gunmetal Knob Cap
                val radius = size.minDimension * 0.34f
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF4B5563), Color(0xFF1E242B), Color(0xFF0C0F13)),
                        center = Offset(center.x - radius * 0.2f, center.y - radius * 0.2f),
                        radius = radius * 1.4f
                    ),
                    radius = radius,
                    center = center
                )
                drawCircle(
                    color = Color(0xFF5D6878),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Knob pointer dot
                val pointerAngleRad = Math.toRadians((135.0 + sweep.toDouble()))
                val dotDist = radius * 0.68f
                val dotCenter = Offset(
                    center.x + (cos(pointerAngleRad) * dotDist).toFloat(),
                    center.y + (sin(pointerAngleRad) * dotDist).toFloat()
                )
                drawCircle(
                    color = if (enabled) VolumeBlueGlow else TextSecondaryLight,
                    radius = 3.5.dp.toPx(),
                    center = dotCenter
                )
            }

            Text(
                text = "$volumePercent%",
                fontFamily = ShareTechMonoFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) TextPrimaryLight else TextSecondaryLight.copy(alpha = 0.5f)
            )
        }

        // `-`  [ Volume ]  `+` Control Strip
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 30.dp, height = 34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF181D24))
                    .border(1.dp, ChassisBevelHighlight, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) { onStepVolume(-10) }
                    .semantics { contentDescription = cdDown; role = Role.Button }
                    .testTag("volume_down_button"),
                contentAlignment = Alignment.Center
            ) {
                Text("−", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimaryLight)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (enabled) Brush.verticalGradient(listOf(VolumeBlueGlow, VolumeBlueDeep))
                        else Brush.verticalGradient(listOf(Color(0xFF232A34), Color(0xFF161B22)))
                    )
                    .border(1.dp, if (enabled) Color(0xFF6BB5FF) else ChassisBevelHighlight, RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.label_volume),
                    fontFamily = ShareTechMonoFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryLight
                )
            }

            Box(
                modifier = Modifier
                    .size(width = 30.dp, height = 34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF181D24))
                    .border(1.dp, ChassisBevelHighlight, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) { onStepVolume(10) }
                    .semantics { contentDescription = cdUp; role = Role.Button }
                    .testTag("volume_up_button"),
                contentAlignment = Alignment.Center
            ) {
                Text("+", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimaryLight)
            }
        }
    }
}

/**
 * Massive Circular Hero Push-to-Talk (PTT) Button with glowing Neon Green / TX Red / Busy Amber LED ring.
 * Uses raw pointerInput press-and-hold gesture so mic activates ONLY while pressed and releases immediately.
 */
@Composable
fun HeroPttButton(
    isPoweredOn: Boolean,
    isTransmitting: Boolean,
    isChannelBusyByOther: Boolean,
    canTransmit: Boolean,
    hapticsEnabled: Boolean,
    onPttPressed: () -> Unit,
    onPttReleased: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val cd = stringResource(R.string.cd_ptt_button)
    val scale by animateFloatAsState(
        targetValue = if (isTransmitting) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "ptt_scale"
    )

    val ringColor = when {
        !isPoweredOn -> Color(0xFF323942)
        isTransmitting -> PttTxRed
        isChannelBusyByOther -> PttBusyAmber
        canTransmit -> PttNeonGreen
        else -> Color(0xFF55606E)
    }

    val ringGlow = when {
        !isPoweredOn -> Color.Transparent
        isTransmitting -> PttTxRed.copy(alpha = 0.6f)
        isChannelBusyByOther -> PttBusyAmber.copy(alpha = 0.5f)
        canTransmit -> PttGreenGlow.copy(alpha = 0.55f)
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .size(168.dp)
            .scale(scale)
            .shadow(
                elevation = if (isPoweredOn) 18.dp else 4.dp,
                shape = CircleShape,
                ambientColor = ringColor,
                spotColor = ringColor
            )
            .clip(CircleShape)
            .background(Color(0xFF0A0D11))
            .border(6.dp, ringColor, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = if (isTransmitting) {
                        listOf(Color(0xFF421919), Color(0xFF1F0C0C), Color(0xFF0D0808))
                    } else {
                        listOf(Color(0xFF333B46), Color(0xFF1A2027), Color(0xFF0E1116))
                    }
                )
            )
            .border(1.5.dp, ringGlow.takeIf { it != Color.Transparent } ?: ChassisBevelHighlight, CircleShape)
            .pointerInput(isPoweredOn, hapticsEnabled) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (hapticsEnabled) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    onPttPressed()
                    waitForUpOrCancellation()
                    onPttReleased()
                }
            }
            .semantics {
                contentDescription = cd
                role = Role.Button
            }
            .testTag("ptt_hold_button"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = if (isTransmitting) PttTxRed else TextPrimaryLight,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isTransmitting) "TALKING…" else stringResource(R.string.label_push_to_talk),
                fontFamily = OrbitronFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = if (isTransmitting) Color(0xFFFFB4B0) else TextPrimaryLight,
                textAlign = TextAlign.Center,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(4.dp))
            Icon(
                imageVector = Icons.Default.Sensors,
                contentDescription = null,
                tint = ringColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Bottom Left Hardware Button: "Connect Devices (2-6)" with live status LED dot.
 */
@Composable
fun ConnectDevicesHardwareButton(
    connectedCount: Int,
    pendingCount: Int,
    isPoweredOn: Boolean,
    isPrivateMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cd = stringResource(R.string.cd_connect_devices)
    val ledColor = when {
        !isPoweredOn -> Color(0xFF333B44)
        pendingCount > 0 -> BrandAmber
        isPrivateMode -> Color(0xFF00E5FF)
        connectedCount > 0 -> PttNeonGreen
        else -> Color(0xFF667382)
    }

    Box(
        modifier = modifier
            .height(106.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(listOf(Color(0xFF28303B), Color(0xFF101419)))
            )
            .border(1.5.dp, ChassisBevelHighlight, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = cd
                role = Role.Button
            }
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .testTag("connect_devices_button"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .shadow(6.dp, CircleShape, ambientColor = ledColor, spotColor = ledColor)
                    .clip(CircleShape)
                    .background(ledColor)
            )
            Spacer(Modifier.height(6.dp))
            Icon(
                imageVector = if (isPrivateMode) Icons.Default.Lock else Icons.Default.Groups,
                contentDescription = null,
                tint = TextPrimaryLight,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isPrivateMode) "Private\nMode\n(1-to-1)" else "Connect\nDevices\n($connectedCount/$MAX_FAMILY_DEVICES)",
                fontFamily = ShareTechMonoFontFamily,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = TextPrimaryLight,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Bottom Right Hardware Button: "Settings" / Admin Panel.
 */
@Composable
fun SettingsHardwareButton(
    isAdmin: Boolean,
    pendingCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cd = stringResource(R.string.cd_settings)
    Box(
        modifier = modifier
            .height(106.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(listOf(Color(0xFF28303B), Color(0xFF101419)))
            )
            .border(1.5.dp, ChassisBevelHighlight, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = cd
                role = Role.Button
            }
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .testTag("settings_admin_button"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isAdmin && pendingCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(BrandAmber)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "$pendingCount NEW",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = ChassisObsidian
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = TextPrimaryLight,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (isAdmin) "Admin &\nSettings" else stringResource(R.string.label_settings),
                fontFamily = ShareTechMonoFontFamily,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = TextPrimaryLight,
                textAlign = TextAlign.Center
            )
        }
    }
}
