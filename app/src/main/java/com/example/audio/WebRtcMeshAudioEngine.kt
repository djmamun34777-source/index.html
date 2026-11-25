package com.example.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import com.example.data.DEFAULT_STUN_SERVERS
import com.example.data.DEFAULT_TURN_CREDENTIAL
import com.example.data.DEFAULT_TURN_URL
import com.example.data.DEFAULT_TURN_USERNAME
import com.example.data.IceServerConfig
import com.example.data.SignalType
import com.example.data.WalkieDevice
import com.example.data.WebRtcSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

private const val WEBRTC_TAG = "WebRtcMeshAudioEngine"

/**
 * Full-Mesh Peer-to-Peer WebRTC Audio Engine for up to 6 family walkie-talkie devices.
 *
 * Key Guarantees:
 * 1. Pre-configured multi-region STUN + OpenRelay TURN (UDP/TCP/TLS 443) with live Admin override.
 * 2. Strict Push-to-Talk microphone privacy: mic remains muted/disabled unless PTT is held.
 * 3. Real Live Audio Metering:
 *    - TX level is computed directly from real hardware PCM microphone samples in [JavaAudioDeviceModule.SamplesReadyCallback].
 *    - RX level & active ICE route (`P2P DIRECT` vs `TURN RELAY`) are read directly from WebRTC [PeerConnection.getStats].
 * 4. True Hardware Microphone Release on third-party calls (IMO, WhatsApp, Messenger, SIM):
 *    [suspendHardwareAudioForExternalCall] detaches & disposes local AudioTrack/AudioSource and
 *    mutes speaker/mic hardware so Android completely releases AudioRecord to the calling app.
 * 5. Automatic Hardware Reclaim: [resumeHardwareAudioAfterExternalCall] recreates local audio
 *    capture & re-attaches tracks to active peers seamlessly once the external call ends.
 */
class WebRtcMeshAudioEngine(
    private val context: Context
) : AudioTransportEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _telemetry = MutableStateFlow(PeerAudioTelemetry())
    override val telemetry: StateFlow<PeerAudioTelemetry> = _telemetry.asStateFlow()

    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var currentIceConfig: IceServerConfig = IceServerConfig()
    private var activeRoomId: String = ""
    private var isInitialized = false
    @Volatile
    private var isTransmitting = false
    @Volatile
    private var isHardwareSuspendedForCall = false

    private val lastLocalMeterUpdateMs = AtomicLong(0L)
    private var statsPollerJob: Job? = null

    private data class PeerSession(
        val remoteDeviceId: String,
        val remoteUid: String,
        val peerConnection: PeerConnection,
        val pendingRemoteIce: MutableList<IceCandidate> = mutableListOf(),
        @Volatile var remoteDescriptionSet: Boolean = false
    )

    private val peers = ConcurrentHashMap<String, PeerSession>()

    @Synchronized
    override fun initialize(iceConfig: IceServerConfig) {
        currentIceConfig = normalizeIceConfig(iceConfig)
        if (isInitialized) {
            updateTelemetryForIce(currentIceConfig)
            return
        }

        try {
            val initOptions = PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            val adm = JavaAudioDeviceModule.builder(context.applicationContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setSamplesReadyCallback { audioSamples ->
                    if (!isTransmitting || isHardwareSuspendedForCall) {
                        if (_telemetry.value.localAudioLevel > 0f) {
                            _telemetry.update { it.copy(localAudioLevel = 0f) }
                        }
                        return@setSamplesReadyCallback
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastLocalMeterUpdateMs.get() < 60L) return@setSamplesReadyCallback
                    lastLocalMeterUpdateMs.set(now)

                    val level = computePcm16RmsLevel(audioSamples.data)
                    _telemetry.update { it.copy(localAudioLevel = level) }
                }
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                        Log.e(WEBRTC_TAG, "AudioRecord init error: $errorMessage")
                    }

                    override fun onWebRtcAudioRecordStartError(
                        errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                        errorMessage: String?
                    ) {
                        Log.e(WEBRTC_TAG, "AudioRecord start error ($errorCode): $errorMessage")
                    }

                    override fun onWebRtcAudioRecordError(errorMessage: String?) {
                        Log.e(WEBRTC_TAG, "AudioRecord error: $errorMessage")
                    }
                })
                .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                    override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                        Log.e(WEBRTC_TAG, "AudioTrack init error: $errorMessage")
                    }

                    override fun onWebRtcAudioTrackStartError(
                        errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                        errorMessage: String?
                    ) {
                        Log.e(WEBRTC_TAG, "AudioTrack start error ($errorCode): $errorMessage")
                    }

                    override fun onWebRtcAudioTrackError(errorMessage: String?) {
                        Log.e(WEBRTC_TAG, "AudioTrack error: $errorMessage")
                    }
                })
                .createAudioDeviceModule()

            adm.setMicrophoneMute(true)
            adm.setSpeakerMute(isHardwareSuspendedForCall)

            val pcFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .createPeerConnectionFactory()

            audioDeviceModule = adm
            factory = pcFactory
            isInitialized = true

            // Keep AudioRecord idle until peers or PTT actually need it so standby does not grab mic needlessly
            updateTelemetryForIce(currentIceConfig)
        } catch (t: Throwable) {
            Log.e(WEBRTC_TAG, "Failed to initialize WebRTC PeerConnectionFactory", t)
            _telemetry.update {
                it.copy(iceReady = false, lastError = t.localizedMessage ?: "WebRTC init failed")
            }
        }
    }

    @Synchronized
    private fun createLocalAudioCapture(pcFactory: PeerConnectionFactory) {
        if (localAudioTrack != null && localAudioSource != null) return
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        val source = pcFactory.createAudioSource(audioConstraints)
        val track = pcFactory.createAudioTrack("mamun_ptt_audio_track_${System.currentTimeMillis()}", source)
        track.setEnabled(isTransmitting && !isHardwareSuspendedForCall)
        localAudioSource = source
        localAudioTrack = track
        updateCaptureFlag()
    }

    private fun updateCaptureFlag() {
        val activeCapture = !isHardwareSuspendedForCall &&
            localAudioTrack != null &&
            (isTransmitting || peers.isNotEmpty())
        _telemetry.update { it.copy(hasActiveAudioCapture = activeCapture) }
    }

    override fun updateIceConfig(iceConfig: IceServerConfig) {
        currentIceConfig = normalizeIceConfig(iceConfig)
        updateTelemetryForIce(currentIceConfig)
    }

    private fun updateTelemetryForIce(config: IceServerConfig) {
        val iceServers = buildIceServers(config)
        val stunCount = config.stunUrls.count { it.isNotBlank() }
        val hasTurn = config.turnUrl.isNotBlank()
        _telemetry.update {
            it.copy(
                iceReady = isInitialized && iceServers.isNotEmpty(),
                stunCount = stunCount,
                turnConfigured = hasTurn,
                lastError = null
            )
        }
    }

    @Synchronized
    override fun syncRoomPeers(
        roomId: String,
        myDeviceId: String,
        myUid: String,
        remotePeers: List<WalkieDevice>,
        signalingSender: SignalingSender
    ) {
        if (!isInitialized) initialize(currentIceConfig)
        val pcFactory = factory ?: return

        if (activeRoomId != roomId) {
            disconnectAll(sendBye = true, myDeviceId = myDeviceId, signalingSender = signalingSender)
            activeRoomId = roomId
            _telemetry.update { it.copy(activeRoomId = roomId) }
        }

        val desiredIds = remotePeers.map { it.id }.toSet()

        val staleIds = peers.keys.filter { it !in desiredIds }
        staleIds.forEach { staleId -> closePeer(staleId) }

        remotePeers.forEach { remote ->
            if (remote.id == myDeviceId || peers.containsKey(remote.id)) return@forEach
            val session = createPeerSession(
                pcFactory = pcFactory,
                roomId = roomId,
                myDeviceId = myDeviceId,
                remoteDeviceId = remote.id,
                remoteUid = remote.ownerUid,
                signalingSender = signalingSender
            ) ?: return@forEach

            peers[remote.id] = session

            if (myDeviceId < remote.id) {
                createAndSendOffer(session, roomId, myDeviceId, signalingSender)
            }
        }

        updateCaptureFlag()
        refreshConnectedTelemetry()
        ensureStatsPollerState()
    }

    override fun handleIncomingSignal(
        signal: WebRtcSignal,
        myDeviceId: String,
        myUid: String,
        signalingSender: SignalingSender
    ) {
        if (signal.roomId != activeRoomId) return
        if (!isInitialized) initialize(currentIceConfig)
        val pcFactory = factory ?: return

        when (signal.type) {
            SignalType.BYE -> {
                closePeer(signal.fromDeviceId)
                updateCaptureFlag()
                refreshConnectedTelemetry()
                ensureStatsPollerState()
            }

            SignalType.OFFER -> {
                val session = peers[signal.fromDeviceId] ?: createPeerSession(
                    pcFactory = pcFactory,
                    roomId = activeRoomId,
                    myDeviceId = myDeviceId,
                    remoteDeviceId = signal.fromDeviceId,
                    remoteUid = signal.fromUid,
                    signalingSender = signalingSender
                )?.also { peers[signal.fromDeviceId] = it } ?: return

                updateCaptureFlag()
                ensureStatsPollerState()

                val offerDesc = SessionDescription(SessionDescription.Type.OFFER, signal.sdp)
                session.peerConnection.setRemoteDescription(object : SimpleSdpObserver("setRemoteOffer") {
                    override fun onSetSuccess() {
                        session.remoteDescriptionSet = true
                        flushPendingIce(session)
                        val answerConstraints = MediaConstraints().apply {
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                        }
                        session.peerConnection.createAnswer(object : SimpleSdpObserver("createAnswer") {
                            override fun onCreateSuccess(desc: SessionDescription?) {
                                if (desc == null) return
                                session.peerConnection.setLocalDescription(
                                    object : SimpleSdpObserver("setLocalAnswer") {
                                        override fun onSetSuccess() {
                                            scope.launch {
                                                signalingSender.sendSignal(
                                                    roomId = activeRoomId,
                                                    fromDeviceId = myDeviceId,
                                                    toDeviceId = signal.fromDeviceId,
                                                    toUid = signal.fromUid,
                                                    signalType = SignalType.ANSWER,
                                                    sdp = desc.description
                                                )
                                            }
                                        }
                                    },
                                    desc
                                )
                            }
                        }, answerConstraints)
                    }
                }, offerDesc)
            }

            SignalType.ANSWER -> {
                val session = peers[signal.fromDeviceId] ?: return
                val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, signal.sdp)
                session.peerConnection.setRemoteDescription(object : SimpleSdpObserver("setRemoteAnswer") {
                    override fun onSetSuccess() {
                        session.remoteDescriptionSet = true
                        flushPendingIce(session)
                    }
                }, answerDesc)
            }

            SignalType.ICE -> {
                val session = peers[signal.fromDeviceId] ?: return
                if (signal.iceCandidate.isBlank()) return
                val candidate = IceCandidate(
                    signal.iceSdpMid,
                    signal.iceSdpMLineIndex.toInt(),
                    signal.iceCandidate
                )
                if (session.remoteDescriptionSet) {
                    session.peerConnection.addIceCandidate(candidate)
                } else {
                    session.pendingRemoteIce.add(candidate)
                }
            }
        }
    }

    @Synchronized
    override fun setTransmitting(active: Boolean) {
        if (isHardwareSuspendedForCall) {
            isTransmitting = false
            _telemetry.update { it.copy(transmitting = false, localAudioLevel = 0f) }
            updateCaptureFlag()
            return
        }
        isTransmitting = active
        try {
            if (active) {
                factory?.let { createLocalAudioCapture(it) }
                audioDeviceModule?.setMicrophoneMute(false)
                localAudioTrack?.setEnabled(true)
            } else {
                localAudioTrack?.setEnabled(false)
                audioDeviceModule?.setMicrophoneMute(true)
            }
            _telemetry.update {
                it.copy(
                    transmitting = active,
                    localAudioLevel = if (active) it.localAudioLevel else 0f
                )
            }
            updateCaptureFlag()
        } catch (t: Throwable) {
            Log.e(WEBRTC_TAG, "Error toggling PTT transmission", t)
        }
    }

    /**
     * Releases Android hardware microphone capture (AudioRecord) & mutes remote playback immediately
     * when IMO / WhatsApp / Messenger / SIM call starts so the other app gets 100% unobstructed audio.
     */
    @Synchronized
    override fun suspendHardwareAudioForExternalCall() {
        if (isHardwareSuspendedForCall) return
        isHardwareSuspendedForCall = true
        isTransmitting = false
        Log.i(WEBRTC_TAG, "Releasing WebRTC hardware microphone & speaker for external call")

        runCatching {
            localAudioTrack?.setEnabled(false)
            audioDeviceModule?.setMicrophoneMute(true)
            audioDeviceModule?.setSpeakerMute(true)

            peers.values.forEach { session ->
                session.peerConnection.senders.forEach { sender ->
                    if (sender.track()?.kind() == org.webrtc.MediaStreamTrack.AUDIO_TRACK_KIND) {
                        runCatching { sender.setTrack(null, false) }
                    }
                }
                session.peerConnection.receivers.forEach { receiver ->
                    (receiver.track() as? AudioTrack)?.setEnabled(false)
                }
            }

            localAudioTrack?.dispose()
            localAudioSource?.dispose()
            localAudioTrack = null
            localAudioSource = null
        }.onFailure { err ->
            Log.w(WEBRTC_TAG, "Non-fatal error releasing hardware audio: ${err.message}")
        }

        _telemetry.update {
            it.copy(
                transmitting = false,
                receivingAudio = false,
                localAudioLevel = 0f,
                remoteAudioLevel = 0f,
                hasActiveAudioCapture = false,
                hardwareMicReleasedForCall = true
            )
        }
    }

    /**
     * Automatically reclaims local WebRTC audio capture and re-attaches tracks to active peers
     * the moment the third-party call (IMO / WhatsApp / Messenger / SIM) ends.
     */
    @Synchronized
    override fun resumeHardwareAudioAfterExternalCall() {
        if (!isHardwareSuspendedForCall) return
        isHardwareSuspendedForCall = false
        Log.i(WEBRTC_TAG, "Auto-reclaiming WebRTC hardware microphone & speaker after external call")

        runCatching {
            val pcFactory = factory
            if (pcFactory != null && peers.isNotEmpty()) {
                createLocalAudioCapture(pcFactory)
                val freshTrack = localAudioTrack
                peers.values.forEach { session ->
                    if (freshTrack != null) {
                        var attached = false
                        session.peerConnection.senders.forEach { sender ->
                            if (sender.track() == null || sender.track()?.kind() == org.webrtc.MediaStreamTrack.AUDIO_TRACK_KIND) {
                                runCatching {
                                    sender.setTrack(freshTrack, false)
                                    attached = true
                                }
                            }
                        }
                        if (!attached) {
                            runCatching { session.peerConnection.addTrack(freshTrack, listOf("mamun_walkie_stream")) }
                        }
                    }
                    session.peerConnection.receivers.forEach { receiver ->
                        (receiver.track() as? AudioTrack)?.setEnabled(true)
                    }
                }
            }
            audioDeviceModule?.setMicrophoneMute(true)
            audioDeviceModule?.setSpeakerMute(false)
        }.onFailure { err ->
            Log.w(WEBRTC_TAG, "Non-fatal error resuming hardware audio: ${err.message}")
        }

        _telemetry.update {
            it.copy(hardwareMicReleasedForCall = false)
        }
        updateCaptureFlag()
        ensureStatsPollerState()
    }

    @Synchronized
    override fun disconnectAll(
        sendBye: Boolean,
        myDeviceId: String,
        signalingSender: SignalingSender?
    ) {
        setTransmitting(false)
        val previousRoom = activeRoomId
        val entries = peers.entries.toList()
        peers.clear()
        entries.forEach { (_, session) ->
            if (sendBye && signalingSender != null && previousRoom.isNotBlank()) {
                scope.launch {
                    signalingSender.sendSignal(
                        roomId = previousRoom,
                        fromDeviceId = myDeviceId,
                        toDeviceId = session.remoteDeviceId,
                        toUid = session.remoteUid,
                        signalType = SignalType.BYE
                    )
                }
            }
            try {
                session.peerConnection.close()
                session.peerConnection.dispose()
            } catch (_: Throwable) {
            }
        }
        activeRoomId = ""
        updateCaptureFlag()
        refreshConnectedTelemetry()
        ensureStatsPollerState()
    }

    @Synchronized
    override fun release() {
        statsPollerJob?.cancel()
        statsPollerJob = null
        disconnectAll(sendBye = false, myDeviceId = "", signalingSender = null)
        try {
            localAudioTrack?.dispose()
            localAudioSource?.dispose()
            audioDeviceModule?.release()
            factory?.dispose()
        } catch (_: Throwable) {
        }
        localAudioTrack = null
        localAudioSource = null
        audioDeviceModule = null
        factory = null
        isInitialized = false
        scope.cancel()
    }

    private fun ensureStatsPollerState() {
        if (peers.isEmpty() || isHardwareSuspendedForCall) {
            statsPollerJob?.cancel()
            statsPollerJob = null
            _telemetry.update {
                it.copy(
                    remoteAudioLevel = 0f,
                    receivingAudio = false,
                    iceConnectionLabel = if (peers.isEmpty()) "STANDBY" else it.iceConnectionLabel
                )
            }
            return
        }
        if (statsPollerJob?.isActive == true) return
        statsPollerJob = scope.launch {
            while (isActive && peers.isNotEmpty() && !isHardwareSuspendedForCall) {
                pollRealWebRtcStats()
                delay(220L)
            }
        }
    }

    private fun pollRealWebRtcStats() {
        val activeSessions = peers.values.toList()
        if (activeSessions.isEmpty()) return

        var maxRemoteLevel = 0f
        var sawRelayRoute = false
        var sawDirectRoute = false

        activeSessions.forEach { session ->
            runCatching {
                session.peerConnection.getStats { report ->
                    var peerRemoteLevel = 0f
                    val localCandidateTypes = HashMap<String, String>()
                    var activeLocalCandidateId: String? = null

                    report.statsMap.values.forEach { stat ->
                        when (stat.type) {
                            "inbound-rtp" -> {
                                val kind = stat.members["kind"] as? String ?: stat.members["mediaType"] as? String
                                if (kind == "audio") {
                                    val levelNum = stat.members["audioLevel"] as? Number
                                    if (levelNum != null) {
                                        val lvl = levelNum.toFloat().coerceIn(0f, 1f)
                                        if (lvl > peerRemoteLevel) peerRemoteLevel = lvl
                                    }
                                }
                            }

                            "local-candidate" -> {
                                val cType = stat.members["candidateType"] as? String
                                if (cType != null) {
                                    localCandidateTypes[stat.id] = cType
                                }
                            }

                            "candidate-pair" -> {
                                val state = stat.members["state"] as? String
                                val nominated = stat.members["nominated"] as? Boolean ?: false
                                if (state == "succeeded" && nominated) {
                                    activeLocalCandidateId = stat.members["localCandidateId"] as? String
                                }
                            }
                        }
                    }

                    val selectedType = activeLocalCandidateId?.let { localCandidateTypes[it] }
                    if (selectedType.equals("relay", ignoreCase = true)) {
                        sawRelayRoute = true
                    } else if (!selectedType.isNullOrBlank()) {
                        sawDirectRoute = true
                    }

                    if (peerRemoteLevel > maxRemoteLevel) {
                        maxRemoteLevel = peerRemoteLevel
                    }

                    val currentConnected = _telemetry.value.connectedPeerIds
                    val routeLabel = when {
                        sawRelayRoute -> "TURN RELAY"
                        sawDirectRoute -> "P2P DIRECT"
                        currentConnected.isNotEmpty() -> "ICE LIVE"
                        peers.isNotEmpty() -> "ICE CHECKING"
                        else -> "STANDBY"
                    }

                    _telemetry.update {
                        it.copy(
                            remoteAudioLevel = maxRemoteLevel,
                            receivingAudio = maxRemoteLevel > 0.015f && !isHardwareSuspendedForCall,
                            iceConnectionLabel = routeLabel
                        )
                    }
                }
            }
        }
    }

    private fun computePcm16RmsLevel(pcmBytes: ByteArray): Float {
        if (pcmBytes.size < 2) return 0f
        val shortCount = pcmBytes.size / 2
        val buf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        var sumSq = 0.0
        for (i in 0 until shortCount) {
            val sample = buf.short.toDouble() / 32768.0
            sumSq += sample * sample
        }
        val rms = sqrt(sumSq / shortCount.coerceAtLeast(1))
        // Amplify human speech RMS (~0.02..0.28) cleanly into 0.0..1.0 meter scale
        return ((rms * 4.2).toFloat()).coerceIn(0f, 1f)
    }

    private fun createPeerSession(
        pcFactory: PeerConnectionFactory,
        roomId: String,
        myDeviceId: String,
        remoteDeviceId: String,
        remoteUid: String,
        signalingSender: SignalingSender
    ): PeerSession? {
        val iceServers = buildIceServers(currentIceConfig)
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        val pc = pcFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                refreshConnectedTelemetry()
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                if (!receiving) {
                    _telemetry.update { it.copy(receivingAudio = false, remoteAudioLevel = 0f) }
                }
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                scope.launch {
                    signalingSender.sendSignal(
                        roomId = roomId,
                        fromDeviceId = myDeviceId,
                        toDeviceId = remoteDeviceId,
                        toUid = remoteUid,
                        signalType = SignalType.ICE,
                        iceCandidate = candidate.sdp,
                        iceSdpMid = candidate.sdpMid ?: "0",
                        iceSdpMLineIndex = candidate.sdpMLineIndex
                    )
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is AudioTrack) {
                    track.setEnabled(!isHardwareSuspendedForCall)
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is AudioTrack) {
                    track.setEnabled(!isHardwareSuspendedForCall)
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                refreshConnectedTelemetry()
            }
        }) ?: return null

        if (!isHardwareSuspendedForCall) {
            createLocalAudioCapture(pcFactory)
            localAudioTrack?.let { track ->
                pc.addTrack(track, listOf("mamun_walkie_stream"))
            }
        }

        return PeerSession(
            remoteDeviceId = remoteDeviceId,
            remoteUid = remoteUid,
            peerConnection = pc
        )
    }

    private fun createAndSendOffer(
        session: PeerSession,
        roomId: String,
        myDeviceId: String,
        signalingSender: SignalingSender
    ) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        session.peerConnection.createOffer(object : SimpleSdpObserver("createOffer") {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                session.peerConnection.setLocalDescription(object : SimpleSdpObserver("setLocalOffer") {
                    override fun onSetSuccess() {
                        scope.launch {
                            signalingSender.sendSignal(
                                roomId = roomId,
                                fromDeviceId = myDeviceId,
                                toDeviceId = session.remoteDeviceId,
                                toUid = session.remoteUid,
                                signalType = SignalType.OFFER,
                                sdp = desc.description
                            )
                        }
                    }
                }, desc)
            }
        }, constraints)
    }

    private fun flushPendingIce(session: PeerSession) {
        val queued = session.pendingRemoteIce.toList()
        session.pendingRemoteIce.clear()
        queued.forEach { session.peerConnection.addIceCandidate(it) }
    }

    private fun closePeer(deviceId: String) {
        val removed = peers.remove(deviceId) ?: return
        try {
            removed.peerConnection.close()
            removed.peerConnection.dispose()
        } catch (_: Throwable) {
        }
    }

    private fun refreshConnectedTelemetry() {
        val connected = peers.values
            .filter {
                val state = it.peerConnection.iceConnectionState()
                state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED
            }
            .map { it.remoteDeviceId }
            .toSet()

        val label = when {
            connected.isNotEmpty() -> {
                val prev = _telemetry.value.iceConnectionLabel
                if (prev == "TURN RELAY" || prev == "P2P DIRECT") prev else "ICE LIVE"
            }
            peers.isNotEmpty() -> "ICE CHECKING"
            else -> "STANDBY"
        }

        _telemetry.update {
            it.copy(
                connectedPeerIds = connected,
                activeRoomId = activeRoomId,
                iceConnectionLabel = label
            )
        }
    }

    private fun normalizeIceConfig(config: IceServerConfig): IceServerConfig {
        val stuns = config.stunUrls.filter { it.isNotBlank() }.ifEmpty { DEFAULT_STUN_SERVERS }
        val turnUrl = config.turnUrl.ifBlank { DEFAULT_TURN_URL }
        val turnUser = if (config.turnUrl.isBlank()) DEFAULT_TURN_USERNAME else config.turnUsername
        val turnPass = if (config.turnUrl.isBlank()) DEFAULT_TURN_CREDENTIAL else config.turnCredential
        return config.copy(
            stunUrls = stuns,
            turnUrl = turnUrl,
            turnUsername = turnUser,
            turnCredential = turnPass
        )
    }

    private fun buildIceServers(config: IceServerConfig): List<PeerConnection.IceServer> {
        val list = mutableListOf<PeerConnection.IceServer>()
        config.stunUrls.filter { it.isNotBlank() }.forEach { url ->
            list.add(PeerConnection.IceServer.builder(url).createIceServer())
        }
        if (config.turnUrl.isNotBlank()) {
            val urls = mutableListOf(config.turnUrl)
            if (config.turnUrl.contains("openrelay.metered.ca", ignoreCase = true)) {
                val extra = listOf(
                    "turn:openrelay.metered.ca:80",
                    "turn:openrelay.metered.ca:443?transport=tcp",
                    "turns:openrelay.metered.ca:443?transport=tcp"
                )
                extra.forEach { if (it !in urls) urls.add(it) }
            }
            val builder = PeerConnection.IceServer.builder(urls)
            if (config.turnUsername.isNotBlank()) builder.setUsername(config.turnUsername)
            if (config.turnCredential.isNotBlank()) builder.setPassword(config.turnCredential)
            list.add(builder.createIceServer())
        }
        return list
    }

    private open class SimpleSdpObserver(private val label: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) {
            Log.w(WEBRTC_TAG, "$label create failure: $error")
        }

        override fun onSetFailure(error: String?) {
            Log.w(WEBRTC_TAG, "$label set failure: $error")
        }
    }
}

/**
 * Tactile Walkie-Talkie Radio Tone Generator (Key-Up chirp, Roger Beep, Channel click, Busy tone).
 */
class RadioTonePlayer {
    private val toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 65)
    } catch (_: Throwable) {
        null
    }

    fun playPttKeyUp() {
        runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 85) }
    }

    fun playRogerBeep() {
        runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 120) }
    }

    fun playChannelClick() {
        runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_PROMPT, 45) }
    }

    fun playChannelBusy() {
        runCatching { toneGenerator?.startTone(ToneGenerator.TONE_SUP_BUSY, 220) }
    }

    fun release() {
        runCatching { toneGenerator?.release() }
    }
}
