package com.example.viewmodel

import androidx.lifecycle.ViewModel
import com.example.audio.PeerAudioTelemetry
import com.example.data.ConnectionStatus
import com.example.data.DeviceApprovalStatus
import com.example.data.DeviceSlot
import com.example.data.IceServerConfig
import com.example.data.LocalRadioPreferences
import com.example.data.NetworkTransport
import com.example.data.PrivateSession
import com.example.data.RadioChannel
import com.example.data.RadioMode
import com.example.data.WalkieDevice
import com.example.service.WalkieSessionRuntime
import kotlinx.coroutines.flow.StateFlow

data class WalkieUiState(
    val isPoweredOn: Boolean = true,
    val isAdmin: Boolean = false,
    val myDeviceId: String = "",
    val myDeviceName: String = "",
    val myDevice: WalkieDevice? = null,
    val approvalStatus: DeviceApprovalStatus = DeviceApprovalStatus.PENDING,
    val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTING,
    val activeChannel: Int = 1,
    val radioMode: RadioMode = RadioMode.GROUP,
    val privateTargetDeviceId: String = "",
    val privateTargetName: String = "",
    val isTransmitting: Boolean = false,
    val activeSpeakerName: String = "",
    val activeSpeakerDeviceId: String = "",
    val isChannelBusyByOther: Boolean = false,
    val isCallInterrupted: Boolean = false,
    val micPermissionGranted: Boolean = false,
    val batteryOptimizationIgnored: Boolean = true,
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val signalBars: Int = 0,
    val networkTransport: NetworkTransport = NetworkTransport.OFFLINE,
    val volumePercent: Int = 75,
    val devices: List<WalkieDevice> = emptyList(),
    val slots: List<DeviceSlot> = emptyList(),
    val channels: List<RadioChannel> = emptyList(),
    val activePrivateSession: PrivateSession? = null,
    val iceConfig: IceServerConfig = IceServerConfig(),
    val audioTelemetry: PeerAudioTelemetry = PeerAudioTelemetry(),
    val preferences: LocalRadioPreferences = LocalRadioPreferences(),
    val snackbarMessage: String? = null,
    val isBusySaving: Boolean = false
) {
    val approvedDevices: List<WalkieDevice>
        get() = devices.filter { it.approvalStatus == DeviceApprovalStatus.APPROVED }

    val pendingDevices: List<WalkieDevice>
        get() = devices.filter { it.approvalStatus == DeviceApprovalStatus.PENDING }

    val onlineInActiveChannelCount: Int
        get() = if (radioMode == RadioMode.GROUP) {
            approvedDevices.count {
                it.online && it.radioMode == RadioMode.GROUP && it.currentChannel.toInt() == activeChannel
            }.coerceAtLeast(if (connectionStatus == ConnectionStatus.CONNECTED) 1 else 0)
        } else {
            val peerOnline = approvedDevices.any { it.id == privateTargetDeviceId && it.online }
            val selfOnline = connectionStatus == ConnectionStatus.CONNECTED
            (if (selfOnline) 1 else 0) + (if (peerOnline) 1 else 0)
        }

    val activeChannelTitle: String
        get() = channels.firstOrNull { it.channelNumber.toInt() == activeChannel }?.name
            ?: "Channel $activeChannel"
}

/**
 * UI-scoped facade delegating to the Application-scoped [WalkieSessionRuntime].
 *
 * Because the radio engine lives in [WalkieSessionRuntime] + [com.example.service.WalkieForegroundService],
 * closing or minimizing the Activity does NOT disconnect WebRTC or Firestore signaling.
 */
class WalkieViewModel(
    private val runtime: WalkieSessionRuntime,
    currentUserId: String
) : ViewModel() {

    val uiState: StateFlow<WalkieUiState> = runtime.uiState

    init {
        runtime.ensureStartedForUser(currentUserId)
    }

    fun refreshSystemPermissionAndPowerStatus() = runtime.refreshSystemPermissionAndPowerStatus()

    fun setMicPermissionGranted(granted: Boolean) = runtime.setMicPermissionGranted(granted)

    fun togglePower() = runtime.togglePower()

    fun channelUp() = runtime.channelUp()

    fun channelDown() = runtime.channelDown()

    fun selectGroupChannel(channel: Int) = runtime.selectGroupChannel(channel)

    fun startPrivateModeWith(targetDevice: WalkieDevice) = runtime.startPrivateModeWith(targetDevice)

    fun adjustVolume(percent: Int) = runtime.adjustVolume(percent)

    fun stepVolume(deltaPercent: Int) = runtime.stepVolume(deltaPercent)

    fun syncSystemVolume() = runtime.syncSystemVolume()

    fun notifyUser(message: String) = runtime.notifyUser(message)

    fun onPttPressed() = runtime.onPttPressed()

    fun onPttReleased() = runtime.onPttReleased()

    fun approveDevice(device: WalkieDevice, requestedSlot: Int? = null) =
        runtime.approveDevice(device, requestedSlot)

    fun removeDevice(device: WalkieDevice) = runtime.removeDevice(device)

    fun renameDevice(device: WalkieDevice, newName: String) = runtime.renameDevice(device, newName)

    fun renameChannel(channelNumber: Int, newName: String) =
        runtime.renameChannel(channelNumber, newName)

    fun clearChannelFloorAsAdmin(channelNumber: Int) =
        runtime.clearChannelFloorAsAdmin(channelNumber)

    fun saveIceServerConfig(
        stunText: String,
        turnUrl: String,
        turnUsername: String,
        turnCredential: String
    ) = runtime.saveIceServerConfig(
        stunText = stunText,
        turnUrl = turnUrl,
        turnUsername = turnUsername,
        turnCredential = turnCredential
    )

    fun setKeepActiveInBackground(enabled: Boolean) = runtime.setKeepActiveInBackground(enabled)

    fun setAutoStartOnBoot(enabled: Boolean) = runtime.setAutoStartOnBoot(enabled)

    fun setRogerBeepEnabled(enabled: Boolean) = runtime.setRogerBeepEnabled(enabled)

    fun setHapticsEnabled(enabled: Boolean) = runtime.setHapticsEnabled(enabled)

    fun setSpeakerphoneEnabled(enabled: Boolean) = runtime.setSpeakerphoneEnabled(enabled)

    fun clearSnackbar() = runtime.clearSnackbar()

    suspend fun prepareForSignOut() = runtime.prepareForSignOut()
}
