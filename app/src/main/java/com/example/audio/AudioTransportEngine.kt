package com.example.audio

import com.example.data.IceServerConfig
import com.example.data.SignalType
import com.example.data.WalkieDevice
import com.example.data.WebRtcSignal
import kotlinx.coroutines.flow.StateFlow

data class PeerAudioTelemetry(
    val connectedPeerIds: Set<String> = emptySet(),
    val activeRoomId: String = "",
    val transmitting: Boolean = false,
    val receivingAudio: Boolean = false,
    val localAudioLevel: Float = 0f,
    val remoteAudioLevel: Float = 0f,
    val iceConnectionLabel: String = "STANDBY",
    val hasActiveAudioCapture: Boolean = false,
    val hardwareMicReleasedForCall: Boolean = false,
    val iceReady: Boolean = false,
    val stunCount: Int = 0,
    val turnConfigured: Boolean = false,
    val lastError: String? = null
)

interface SignalingSender {
    suspend fun sendSignal(
        roomId: String,
        fromDeviceId: String,
        toDeviceId: String,
        toUid: String,
        signalType: SignalType,
        sdp: String = "",
        iceCandidate: String = "",
        iceSdpMid: String = "",
        iceSdpMLineIndex: Int = -1
    )
}

/**
 * Abstraction for real-time WebRTC mesh audio transport (up to 6 family devices),
 * live PCM/RTP audio-level telemetry, and full hardware mic release/reclaim for external calls.
 */
interface AudioTransportEngine {
    val telemetry: StateFlow<PeerAudioTelemetry>

    fun initialize(iceConfig: IceServerConfig)
    fun updateIceConfig(iceConfig: IceServerConfig)
    fun syncRoomPeers(
        roomId: String,
        myDeviceId: String,
        myUid: String,
        remotePeers: List<WalkieDevice>,
        signalingSender: SignalingSender
    )
    fun handleIncomingSignal(
        signal: WebRtcSignal,
        myDeviceId: String,
        myUid: String,
        signalingSender: SignalingSender
    )
    fun setTransmitting(active: Boolean)

    /**
     * Truly releases Android hardware microphone capture (AudioRecord) & speaker playback
     * so third-party calls (IMO, WhatsApp, Messenger, SIM) get immediate unobstructed mic access.
     */
    fun suspendHardwareAudioForExternalCall()

    /**
     * Automatically re-acquires WebRTC local audio capture/playback after the external call ends.
     */
    fun resumeHardwareAudioAfterExternalCall()

    fun disconnectAll(sendBye: Boolean, myDeviceId: String, signalingSender: SignalingSender?)
    fun release()
}
