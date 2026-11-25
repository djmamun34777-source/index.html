package com.example.data

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties

const val MAX_FAMILY_DEVICES = 6
const val MIN_CHANNEL = 1
const val MAX_CHANNEL = 6
const val BOOTSTRAP_ADMIN_EMAIL = "djmamun34777@gmail.com"
const val ICE_CONFIG_DOC_ID = "ice_config"

val DEFAULT_STUN_SERVERS = listOf(
    "stun:stun.l.google.com:19302",
    "stun:stun1.l.google.com:19302",
    "stun:stun2.l.google.com:19302",
    "stun:stun.cloudflare.com:3478",
    "stun:stun.relay.metered.ca:80"
)

const val DEFAULT_TURN_URL = "turn:openrelay.metered.ca:443"
const val DEFAULT_TURN_USERNAME = "openrelayproject"
const val DEFAULT_TURN_CREDENTIAL = "openrelayproject"

enum class DeviceApprovalStatus {
    PENDING, APPROVED, REVOKED;

    companion object {
        fun fromRaw(raw: String?): DeviceApprovalStatus =
            entries.firstOrNull { it.name == raw } ?: PENDING
    }
}

enum class RadioMode {
    GROUP, PRIVATE;

    companion object {
        fun fromRaw(raw: String?): RadioMode =
            entries.firstOrNull { it.name == raw } ?: GROUP
    }
}

enum class NetworkTransport {
    WIFI, CELLULAR, OFFLINE;

    companion object {
        fun fromRaw(raw: String?): NetworkTransport =
            entries.firstOrNull { it.name == raw } ?: OFFLINE
    }
}

enum class SignalType {
    OFFER, ANSWER, ICE, BYE;

    companion object {
        fun fromRaw(raw: String?): SignalType =
            entries.firstOrNull { it.name == raw } ?: ICE
    }
}

enum class ConnectionStatus {
    POWERED_OFF,
    OFFLINE,
    CONNECTING,
    PENDING_APPROVAL,
    REVOKED,
    CONNECTED,
    CALL_PAUSED
}

@Keep
@IgnoreExtraProperties
data class DeviceSlot(
    @DocumentId val id: String = "",
    val deviceId: String = "",
    val ownerUid: String = "",
    val deviceName: String = "",
    val memberUids: List<String> = emptyList(),
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class WalkieDevice(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val deviceName: String = "",
    val slotNumber: Long = 0L,
    val status: String = DeviceApprovalStatus.PENDING.name,
    val online: Boolean = false,
    val currentChannel: Long = 1L,
    val mode: String = RadioMode.GROUP.name,
    val privateTargetDeviceId: String = "",
    val batteryLevel: Long = 100L,
    val networkType: String = NetworkTransport.OFFLINE.name,
    val adminDevice: Boolean = false,
    val memberUids: List<String> = emptyList(),
    val lastSeenAt: Timestamp? = null,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    val approvalStatus: DeviceApprovalStatus
        get() = DeviceApprovalStatus.fromRaw(status)

    val radioMode: RadioMode
        get() = RadioMode.fromRaw(mode)

    val transport: NetworkTransport
        get() = NetworkTransport.fromRaw(networkType)
}

@Keep
@IgnoreExtraProperties
data class RadioChannel(
    @DocumentId val id: String = "",
    val channelNumber: Long = 1L,
    val name: String = "",
    val activeSpeakerDeviceId: String = "",
    val activeSpeakerName: String = "",
    val activeSpeakerUid: String = "",
    val pttLockedAt: Timestamp? = null,
    val memberUids: List<String> = emptyList(),
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PrivateSession(
    @DocumentId val id: String = "",
    val deviceAId: String = "",
    val deviceBId: String = "",
    val participantUids: List<String> = emptyList(),
    val activeSpeakerDeviceId: String = "",
    val activeSpeakerName: String = "",
    val activeSpeakerUid: String = "",
    val pttLockedAt: Timestamp? = null,
    val active: Boolean = true,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class WebRtcSignal(
    @DocumentId val id: String = "",
    val roomId: String = "",
    val fromDeviceId: String = "",
    val toDeviceId: String = "",
    val fromUid: String = "",
    val toUid: String = "",
    val signalType: String = SignalType.ICE.name,
    val sdp: String = "",
    val iceCandidate: String = "",
    val iceSdpMid: String = "",
    val iceSdpMLineIndex: Long = -1L,
    val createdAt: Timestamp? = null
) {
    val type: SignalType
        get() = SignalType.fromRaw(signalType)
}

@Keep
@IgnoreExtraProperties
data class IceServerConfig(
    @DocumentId val id: String = "",
    val stunUrls: List<String> = DEFAULT_STUN_SERVERS,
    val turnUrl: String = DEFAULT_TURN_URL,
    val turnUsername: String = DEFAULT_TURN_USERNAME,
    val turnCredential: String = DEFAULT_TURN_CREDENTIAL,
    val sfuEnabled: Boolean = false,
    val sfuUrl: String = "",
    val memberUids: List<String> = emptyList(),
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

fun buildChannelDocId(channelNumber: Int): String = "channel_${channelNumber.coerceIn(MIN_CHANNEL, MAX_CHANNEL)}"

fun buildPrivateSessionId(deviceA: String, deviceB: String): String {
    val sorted = listOf(deviceA, deviceB).sorted()
    return "priv_${sorted[0]}_${sorted[1]}"
}

fun buildRoomId(mode: RadioMode, channelNumber: Int, myDeviceId: String, targetDeviceId: String): String {
    return if (mode == RadioMode.PRIVATE && targetDeviceId.isNotBlank()) {
        buildPrivateSessionId(myDeviceId, targetDeviceId)
    } else {
        buildChannelDocId(channelNumber)
    }
}
