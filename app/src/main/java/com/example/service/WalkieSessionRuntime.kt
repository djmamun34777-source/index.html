package com.example.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.R
import com.example.audio.AudioTransportEngine
import com.example.audio.RadioTonePlayer
import com.example.audio.SignalingSender
import com.example.audio.WalkieAudioFocusManager
import com.example.audio.WebRtcMeshAudioEngine
import com.example.data.ConnectionStatus
import com.example.data.DeviceApprovalStatus
import com.example.data.DeviceIdentityStore
import com.example.data.IceServerConfig
import com.example.data.MAX_CHANNEL
import com.example.data.MAX_FAMILY_DEVICES
import com.example.data.MIN_CHANNEL
import com.example.data.NetworkTransport
import com.example.data.PttFloorResult
import com.example.data.RadioMode
import com.example.data.SignalType
import com.example.data.WalkieDevice
import com.example.data.WalkieRepository
import com.example.data.buildPrivateSessionId
import com.example.data.buildRoomId
import com.example.viewmodel.WalkieUiState
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val RUNTIME_TAG = "WalkieSessionRuntime"

/**
 * Application-scoped persistent Walkie-Talkie Engine.
 *
 * Lives beyond Activity lifecycle inside the app process + [WalkieForegroundService] so that:
 * - Firestore signaling, presence, and WebRTC mesh audio stay continuously connected in the background.
 * - Incoming family voice plays even when the app is minimized or screen is off.
 * - Third-party calls (IMO, WhatsApp, Messenger, SIM) trigger immediate hardware microphone release,
 *   and automatically reclaim the microphone & audio focus as soon as the call ends.
 */
class WalkieSessionRuntime(context: Context) {

    private val appContext = context.applicationContext
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val databaseId: String = appContext.getString(R.string.firestore_database_id)
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(databaseId)
    private val repository = WalkieRepository(db)
    private val identityStore = DeviceIdentityStore(appContext)
    private val audioEngine: AudioTransportEngine = WebRtcMeshAudioEngine(appContext)
    private val telemetryMonitor = NetworkAndBatteryMonitor(appContext)
    private val tonePlayer = RadioTonePlayer()

    private val audioFocusManager: WalkieAudioFocusManager = WalkieAudioFocusManager(
        context = appContext,
        onExternalCallInterrupted = { reason -> handleExternalCallStarted(reason) },
        onAudioFocusRestored = { handleExternalCallEnded() }
    )

    private val _uiState = MutableStateFlow(WalkieUiState())
    val uiState: StateFlow<WalkieUiState> = _uiState.asStateFlow()

    private var activeUserId: String? = null
    private val processedSignalIds = LinkedHashSet<String>()
    private var sessionJobs = mutableListOf<Job>()
    private var privateSessionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var hwObserversStarted = false
    private var previousTransport: NetworkTransport = NetworkTransport.OFFLINE

    private val signalingSender = object : SignalingSender {
        override suspend fun sendSignal(
            roomId: String,
            fromDeviceId: String,
            toDeviceId: String,
            toUid: String,
            signalType: SignalType,
            sdp: String,
            iceCandidate: String,
            iceSdpMid: String,
            iceSdpMLineIndex: Int
        ) {
            repository.sendWebRtcSignal(
                roomId = roomId,
                fromDeviceId = fromDeviceId,
                toDeviceId = toDeviceId,
                toUid = toUid,
                signalType = signalType,
                sdp = sdp,
                iceCandidate = iceCandidate,
                iceSdpMid = iceSdpMid,
                iceSdpMLineIndex = iceSdpMLineIndex
            )
        }
    }

    fun ensureStartedForUser(uid: String) {
        if (uid.isBlank()) return
        refreshSystemPermissionAndPowerStatus()
        if (activeUserId == uid && sessionJobs.isNotEmpty()) {
            // Already running in background for this user! Just refresh presence & notification.
            recomputeConnectionStatus()
            return
        }
        stopSessionStreamsOnly()
        activeUserId = uid

        if (!hwObserversStarted) {
            hwObserversStarted = true
            telemetryMonitor.start()
            audioFocusManager.startMonitoring()
            observeLocalHardwareStreams()
        }

        bootstrapWalkieSession(uid)
    }

    fun ensureStartedForCurrentAuthUser() {
        val uid = Firebase.auth.currentUser?.uid
        if (!uid.isNullOrBlank()) {
            runtimeScope.launch {
                val prefs = identityStore.readSnapshot()
                if (prefs.verifiedOtpUid == uid) {
                    ensureStartedForUser(uid)
                }
            }
        }
    }

    fun refreshSystemPermissionAndPowerStatus() {
        val hasMic = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringBatteryOpt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(appContext.packageName) ?: true
        } else true
        _uiState.update {
            it.copy(
                micPermissionGranted = hasMic,
                batteryOptimizationIgnored = ignoringBatteryOpt
            )
        }
    }

    private fun observeLocalHardwareStreams() {
        runtimeScope.launch {
            identityStore.preferencesFlow.collect { prefs ->
                _uiState.update {
                    it.copy(
                        preferences = prefs,
                        myDeviceName = prefs.deviceName.ifBlank { it.myDeviceName }
                    )
                }
            }
        }

        runtimeScope.launch {
            telemetryMonitor.telemetry.collect { hw ->
                val wasOffline = previousTransport == NetworkTransport.OFFLINE
                val isNowOnline = hw.networkTransport != NetworkTransport.OFFLINE
                previousTransport = hw.networkTransport

                _uiState.update { state ->
                    state.copy(
                        batteryPercent = hw.batteryPercent,
                        isCharging = hw.isCharging,
                        signalBars = hw.signalBars,
                        networkTransport = hw.networkTransport
                    )
                }
                recomputeConnectionStatus()

                val myId = _uiState.value.myDeviceId
                if (myId.isNotBlank() && _uiState.value.isPoweredOn) {
                    if (wasOffline && isNowOnline) {
                        syncPresenceToServer(online = true)
                        resyncWebRtcMeshPeers()
                    } else if (!isNowOnline) {
                        stopTransmittingInternal(playRogerBeep = false)
                        audioEngine.disconnectAll(
                            sendBye = false,
                            myDeviceId = myId,
                            signalingSender = null
                        )
                    }
                }
            }
        }

        runtimeScope.launch {
            audioFocusManager.volumePercent.collect { vol ->
                _uiState.update { it.copy(volumePercent = vol) }
            }
        }

        runtimeScope.launch {
            audioEngine.telemetry.collect { peerTelemetry ->
                audioFocusManager.ownAudioCaptureActive = peerTelemetry.hasActiveAudioCapture
                _uiState.update { it.copy(audioTelemetry = peerTelemetry) }
            }
        }
    }

    private fun bootstrapWalkieSession(uid: String) {
        val job = runtimeScope.launch {
            val prefs = identityStore.readSnapshot()
            val deviceId = identityStore.getOrCreateDeviceId()
            val deviceName = identityStore.getOrCreateDeviceName()
            val isAdmin = repository.checkIsAdmin(uid)
            val hw = telemetryMonitor.telemetry.value

            _uiState.update {
                it.copy(
                    myDeviceId = deviceId,
                    myDeviceName = deviceName,
                    isAdmin = isAdmin,
                    isPoweredOn = prefs.poweredOn,
                    activeChannel = prefs.lastChannel.coerceIn(MIN_CHANNEL, MAX_CHANNEL),
                    preferences = prefs
                )
            }

            audioEngine.initialize(IceServerConfig())

            val registerResult = repository.registerOrSyncDevice(
                deviceId = deviceId,
                deviceName = deviceName,
                preferredChannel = _uiState.value.activeChannel,
                batteryLevel = hw.batteryPercent,
                networkType = hw.networkTransport
            )

            registerResult.onSuccess { device ->
                _uiState.update {
                    it.copy(
                        myDevice = device,
                        myDeviceName = device.deviceName.ifBlank { deviceName },
                        approvalStatus = device.approvalStatus,
                        activeChannel = device.currentChannel.toInt().coerceIn(MIN_CHANNEL, MAX_CHANNEL),
                        radioMode = device.radioMode,
                        privateTargetDeviceId = device.privateTargetDeviceId
                    )
                }
            }.onFailure { err ->
                showMessage(err.localizedMessage ?: "Connection sync delayed; retrying automatically.")
            }

            startFirestoreStreams(uid, deviceId, isAdmin)
            startPresenceHeartbeat()
            recomputeConnectionStatus()

            if (_uiState.value.isPoweredOn && _uiState.value.approvalStatus == DeviceApprovalStatus.APPROVED) {
                if (!audioFocusManager.isExternalPhoneCallActive()) {
                    audioFocusManager.requestRadioFocus(exclusiveForPtt = false)
                    audioFocusManager.configureCommunicationRouting(_uiState.value.preferences.speakerphoneEnabled)
                } else {
                    handleExternalCallStarted("External call active on startup")
                }
                resyncWebRtcMeshPeers()
            }
        }
        sessionJobs.add(job)
    }

    private fun startFirestoreStreams(uid: String, deviceId: String, isAdmin: Boolean) {
        sessionJobs += runtimeScope.launch {
            repository.observeMyDevice(deviceId)
                .catch { error -> handleStreamError(error, "device profile") }
                .collect { device ->
                    if (device == null) return@collect
                    val prevStatus = _uiState.value.approvalStatus
                    _uiState.update {
                        it.copy(
                            myDevice = device,
                            myDeviceName = device.deviceName.ifBlank { it.myDeviceName },
                            approvalStatus = device.approvalStatus
                        )
                    }
                    if (prevStatus != DeviceApprovalStatus.APPROVED &&
                        device.approvalStatus == DeviceApprovalStatus.APPROVED
                    ) {
                        showMessage("Device approved for Slot #${device.slotNumber}!")
                        if (_uiState.value.isPoweredOn && !_uiState.value.isCallInterrupted) {
                            audioFocusManager.requestRadioFocus(exclusiveForPtt = false)
                            audioFocusManager.configureCommunicationRouting(_uiState.value.preferences.speakerphoneEnabled)
                        }
                    } else if (device.approvalStatus == DeviceApprovalStatus.REVOKED) {
                        stopTransmittingInternal(playRogerBeep = false)
                        audioEngine.disconnectAll(sendBye = true, myDeviceId = deviceId, signalingSender = signalingSender)
                    }
                    recomputeConnectionStatus()
                    resyncWebRtcMeshPeers()
                }
        }

        sessionJobs += runtimeScope.launch {
            repository.observeDevices(isAdmin)
                .catch { error -> handleStreamError(error, "family devices") }
                .collect { list ->
                    val targetId = _uiState.value.privateTargetDeviceId
                    val targetName = list.firstOrNull { it.id == targetId }?.deviceName.orEmpty()
                    _uiState.update { it.copy(devices = list, privateTargetName = targetName) }
                    resyncWebRtcMeshPeers()
                    updatePersistentNotification()
                }
        }

        sessionJobs += runtimeScope.launch {
            repository.observeSlots(isAdmin)
                .catch { error -> handleStreamError(error, "device slots") }
                .collect { slots ->
                    _uiState.update { it.copy(slots = slots) }
                }
        }

        sessionJobs += runtimeScope.launch {
            repository.observeChannels(isAdmin)
                .catch { error -> handleStreamError(error, "radio channels") }
                .collect { channels ->
                    _uiState.update { it.copy(channels = channels) }
                    refreshActiveSpeakerFromRooms()
                }
        }

        sessionJobs += runtimeScope.launch {
            repository.observeIceConfig(isAdmin)
                .catch { error -> handleStreamError(error, "ICE server config") }
                .collect { cfg ->
                    _uiState.update { it.copy(iceConfig = cfg) }
                    audioEngine.updateIceConfig(cfg)
                }
        }

        sessionJobs += runtimeScope.launch {
            repository.observeIncomingSignals(deviceId)
                .catch { error -> handleStreamError(error, "WebRTC signaling") }
                .collect { signals ->
                    val state = _uiState.value
                    if (!state.isPoweredOn || state.approvalStatus != DeviceApprovalStatus.APPROVED) {
                        return@collect
                    }
                    signals.forEach { sig ->
                        if (sig.id.isBlank() || !processedSignalIds.add(sig.id)) return@forEach
                        if (processedSignalIds.size > 400) {
                            processedSignalIds.remove(processedSignalIds.first())
                        }
                        audioEngine.handleIncomingSignal(
                            signal = sig,
                            myDeviceId = deviceId,
                            myUid = uid,
                            signalingSender = signalingSender
                        )
                        runtimeScope.launch { repository.consumeWebRtcSignal(sig.id) }
                    }
                }
        }
    }

    private fun startPresenceHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = runtimeScope.launch {
            while (isActive) {
                delay(20_000L)
                val state = _uiState.value
                if (state.myDeviceId.isNotBlank() && state.isPoweredOn &&
                    state.networkTransport != NetworkTransport.OFFLINE
                ) {
                    syncPresenceToServer(online = true)
                }
            }
        }
    }

    // --- EXTERNAL CALL (IMO / WHATSAPP / MESSENGER / SIM) MIC RELEASE & AUTO-RECLAIM ---

    private fun handleExternalCallStarted(reason: String) {
        Log.i(RUNTIME_TAG, "External call detected ($reason) — releasing hardware mic")
        stopTransmittingInternal(playRogerBeep = false)
        audioEngine.suspendHardwareAudioForExternalCall()
        audioFocusManager.abandonFocusAndRestoreNormalMode()
        _uiState.update {
            it.copy(
                isCallInterrupted = true,
                snackbarMessage = "External call detected — microphone released"
            )
        }
        recomputeConnectionStatus()
    }

    private fun handleExternalCallEnded() {
        Log.i(RUNTIME_TAG, "External call ended — auto-reclaiming hardware mic & audio focus")
        val state = _uiState.value
        _uiState.update { it.copy(isCallInterrupted = false) }
        if (state.isPoweredOn && state.approvalStatus == DeviceApprovalStatus.APPROVED) {
            audioFocusManager.configureCommunicationRouting(state.preferences.speakerphoneEnabled)
            audioEngine.resumeHardwareAudioAfterExternalCall()
            resyncWebRtcMeshPeers()
            showMessage("Call ended — Walkie Talkie mic auto-restored")
        }
        recomputeConnectionStatus()
    }

    // --- USER ACTIONS ---

    fun setMicPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(micPermissionGranted = granted) }
    }

    fun setPowerState(powerOn: Boolean) {
        if (_uiState.value.isPoweredOn == powerOn) return
        togglePower()
    }

    fun togglePower() {
        val nextPower = !_uiState.value.isPoweredOn
        if (_uiState.value.preferences.rogerBeepEnabled) {
            tonePlayer.playChannelClick()
        }
        _uiState.update { it.copy(isPoweredOn = nextPower) }
        runtimeScope.launch { identityStore.setPoweredOn(nextPower) }

        if (!nextPower) {
            stopTransmittingInternal(playRogerBeep = false)
            audioEngine.disconnectAll(
                sendBye = true,
                myDeviceId = _uiState.value.myDeviceId,
                signalingSender = signalingSender
            )
            audioFocusManager.abandonFocusAndRestoreNormalMode()
            WalkieForegroundService.stop(appContext)
            runtimeScope.launch { syncPresenceToServer(online = false) }
        } else {
            if (!audioFocusManager.isExternalPhoneCallActive()) {
                audioFocusManager.requestRadioFocus(exclusiveForPtt = false)
                audioFocusManager.configureCommunicationRouting(_uiState.value.preferences.speakerphoneEnabled)
                audioEngine.resumeHardwareAudioAfterExternalCall()
            }
            runtimeScope.launch {
                syncPresenceToServer(online = _uiState.value.networkTransport != NetworkTransport.OFFLINE)
                resyncWebRtcMeshPeers()
            }
        }
        recomputeConnectionStatus()
    }

    fun channelUp() {
        if (!_uiState.value.isPoweredOn) return
        val next = if (_uiState.value.activeChannel >= MAX_CHANNEL) MIN_CHANNEL else _uiState.value.activeChannel + 1
        selectGroupChannel(next)
    }

    fun channelDown() {
        if (!_uiState.value.isPoweredOn) return
        val next = if (_uiState.value.activeChannel <= MIN_CHANNEL) MAX_CHANNEL else _uiState.value.activeChannel - 1
        selectGroupChannel(next)
    }

    fun selectGroupChannel(channel: Int) {
        val safeChannel = channel.coerceIn(MIN_CHANNEL, MAX_CHANNEL)
        if (!_uiState.value.isPoweredOn) return
        stopTransmittingInternal(playRogerBeep = false)
        if (_uiState.value.preferences.rogerBeepEnabled) {
            tonePlayer.playChannelClick()
        }
        privateSessionJob?.cancel()
        privateSessionJob = null

        _uiState.update {
            it.copy(
                radioMode = RadioMode.GROUP,
                activeChannel = safeChannel,
                privateTargetDeviceId = "",
                privateTargetName = "",
                activePrivateSession = null
            )
        }
        runtimeScope.launch {
            identityStore.saveLastChannel(safeChannel)
            if (_uiState.value.approvalStatus == DeviceApprovalStatus.APPROVED) {
                repository.updateDeviceRadioState(
                    deviceId = _uiState.value.myDeviceId,
                    channel = safeChannel,
                    mode = RadioMode.GROUP,
                    privateTargetDeviceId = ""
                )
            }
            refreshActiveSpeakerFromRooms()
            resyncWebRtcMeshPeers()
            updatePersistentNotification()
        }
    }

    fun startPrivateModeWith(targetDevice: WalkieDevice) {
        val state = _uiState.value
        if (!state.isPoweredOn) {
            showMessage("Turn Walkie Talkie ON first.")
            return
        }
        if (state.approvalStatus != DeviceApprovalStatus.APPROVED) {
            showMessage("Your device must be approved by Admin before using Private Mode.")
            return
        }
        if (targetDevice.id == state.myDeviceId) {
            showMessage("Select another approved family device for Private Mode.")
            return
        }
        stopTransmittingInternal(playRogerBeep = false)
        if (state.preferences.rogerBeepEnabled) {
            tonePlayer.playChannelClick()
        }

        val sessionId = buildPrivateSessionId(state.myDeviceId, targetDevice.id)
        _uiState.update {
            it.copy(
                radioMode = RadioMode.PRIVATE,
                privateTargetDeviceId = targetDevice.id,
                privateTargetName = targetDevice.deviceName
            )
        }

        runtimeScope.launch {
            repository.ensurePrivateSession(state.myDeviceId, targetDevice)
                .onFailure { err -> showMessage(err.localizedMessage ?: "Failed to open private channel") }

            repository.updateDeviceRadioState(
                deviceId = state.myDeviceId,
                mode = RadioMode.PRIVATE,
                privateTargetDeviceId = targetDevice.id
            )

            privateSessionJob?.cancel()
            privateSessionJob = runtimeScope.launch {
                repository.observePrivateSession(sessionId)
                    .catch { err -> handleStreamError(err, "private session") }
                    .collect { session ->
                        _uiState.update { it.copy(activePrivateSession = session) }
                        refreshActiveSpeakerFromRooms()
                    }
            }

            resyncWebRtcMeshPeers()
            updatePersistentNotification()
        }
    }

    fun adjustVolume(percent: Int) = audioFocusManager.setVolumePercent(percent)
    fun stepVolume(deltaPercent: Int) = audioFocusManager.stepVolume(deltaPercent)
    fun syncSystemVolume() = audioFocusManager.refreshSystemVolume()
    fun notifyUser(message: String) = showMessage(message)

    // --- PUSH-TO-TALK (PTT) ---

    fun onPttPressed() {
        val state = _uiState.value
        if (!state.isPoweredOn) {
            showMessage("Power ON the Walkie Talkie to transmit.")
            return
        }
        if (state.networkTransport == NetworkTransport.OFFLINE) {
            showMessage("Offline — cannot transmit until internet reconnects.")
            return
        }
        if (state.approvalStatus != DeviceApprovalStatus.APPROVED) {
            showMessage("Waiting for Admin approval (max 6 family devices).")
            return
        }
        if (!state.micPermissionGranted) {
            showMessage("Microphone permission is required for Push-to-Talk.")
            return
        }
        if (state.isCallInterrupted || audioFocusManager.isExternalPhoneCallActive()) {
            showMessage("Mic released to active call — will auto-restore when call ends.")
            return
        }
        if (state.isTransmitting) return

        runtimeScope.launch {
            val privateSessionId = if (state.radioMode == RadioMode.PRIVATE && state.privateTargetDeviceId.isNotBlank()) {
                buildPrivateSessionId(state.myDeviceId, state.privateTargetDeviceId)
            } else ""

            val floorResult = repository.acquirePttFloor(
                mode = state.radioMode,
                channelNumber = state.activeChannel,
                privateSessionId = privateSessionId,
                myDeviceId = state.myDeviceId,
                myDeviceName = state.myDeviceName
            )

            when (floorResult) {
                is PttFloorResult.Acquired -> {
                    val focusOk = audioFocusManager.requestRadioFocus(exclusiveForPtt = true)
                    if (!focusOk) {
                        repository.releasePttFloor(
                            mode = state.radioMode,
                            channelNumber = state.activeChannel,
                            privateSessionId = privateSessionId,
                            myDeviceId = state.myDeviceId
                        )
                        showMessage("Cannot transmit while another call holds audio focus.")
                        return@launch
                    }

                    audioFocusManager.ownPttActive = true
                    audioFocusManager.configureCommunicationRouting(state.preferences.speakerphoneEnabled)
                    if (state.preferences.rogerBeepEnabled) {
                        tonePlayer.playPttKeyUp()
                    }
                    audioEngine.setTransmitting(true)
                    _uiState.update {
                        it.copy(
                            isTransmitting = true,
                            activeSpeakerName = state.myDeviceName,
                            activeSpeakerDeviceId = state.myDeviceId,
                            isChannelBusyByOther = false
                        )
                    }
                    updatePersistentNotification()
                }

                is PttFloorResult.Busy -> {
                    if (state.preferences.rogerBeepEnabled) {
                        tonePlayer.playChannelBusy()
                    }
                    _uiState.update {
                        it.copy(
                            activeSpeakerName = floorResult.speakerName,
                            activeSpeakerDeviceId = floorResult.speakerDeviceId,
                            isChannelBusyByOther = true,
                            snackbarMessage = "Channel busy — ${floorResult.speakerName} is speaking"
                        )
                    }
                }

                is PttFloorResult.Failed -> showMessage(floorResult.message)
            }
        }
    }

    fun onPttReleased() {
        stopTransmittingInternal(playRogerBeep = true)
    }

    private fun stopTransmittingInternal(playRogerBeep: Boolean) {
        val state = _uiState.value
        val wasTransmitting = state.isTransmitting
        audioFocusManager.ownPttActive = false
        audioEngine.setTransmitting(false)
        if (!wasTransmitting) return

        if (playRogerBeep && state.preferences.rogerBeepEnabled) {
            tonePlayer.playRogerBeep()
        }
        _uiState.update { it.copy(isTransmitting = false) }
        updatePersistentNotification()

        val privateSessionId = if (state.radioMode == RadioMode.PRIVATE && state.privateTargetDeviceId.isNotBlank()) {
            buildPrivateSessionId(state.myDeviceId, state.privateTargetDeviceId)
        } else ""

        runtimeScope.launch {
            repository.releasePttFloor(
                mode = state.radioMode,
                channelNumber = state.activeChannel,
                privateSessionId = privateSessionId,
                myDeviceId = state.myDeviceId
            )
            if (_uiState.value.isPoweredOn && !_uiState.value.isCallInterrupted) {
                audioFocusManager.requestRadioFocus(exclusiveForPtt = false)
            }
        }
    }

    // --- ADMIN & SETTINGS ---

    fun approveDevice(device: WalkieDevice, requestedSlot: Int? = null) {
        runtimeScope.launch {
            _uiState.update { it.copy(isBusySaving = true) }
            repository.approveDeviceToSlot(device.id, requestedSlot)
                .onSuccess { slot -> showMessage("${device.deviceName} approved in Slot #$slot") }
                .onFailure { err -> showMessage(err.localizedMessage ?: "Unable to approve device") }
            _uiState.update { it.copy(isBusySaving = false) }
        }
    }

    fun removeDevice(device: WalkieDevice) {
        runtimeScope.launch {
            _uiState.update { it.copy(isBusySaving = true) }
            repository.removeDeviceFromSlot(device)
                .onSuccess { showMessage("${device.deviceName} removed — Slot #${device.slotNumber} is now free") }
                .onFailure { err -> showMessage(err.localizedMessage ?: "Unable to remove device") }
            _uiState.update { it.copy(isBusySaving = false) }
        }
    }

    fun renameDevice(device: WalkieDevice, newName: String) {
        runtimeScope.launch {
            _uiState.update { it.copy(isBusySaving = true) }
            repository.renameDevice(device, newName)
                .onSuccess {
                    if (device.id == _uiState.value.myDeviceId) {
                        identityStore.saveDeviceName(newName)
                    }
                    showMessage("Renamed to ${newName.trim()}")
                }
                .onFailure { err -> showMessage(err.localizedMessage ?: "Rename failed") }
            _uiState.update { it.copy(isBusySaving = false) }
        }
    }

    fun renameChannel(channelNumber: Int, newName: String) {
        runtimeScope.launch {
            _uiState.update { it.copy(isBusySaving = true) }
            repository.renameChannel(channelNumber, newName)
                .onSuccess { showMessage("Channel $channelNumber renamed") }
                .onFailure { err -> showMessage(err.localizedMessage ?: "Channel rename failed") }
            _uiState.update { it.copy(isBusySaving = false) }
        }
    }

    fun clearChannelFloorAsAdmin(channelNumber: Int) {
        runtimeScope.launch {
            repository.releasePttFloor(
                mode = RadioMode.GROUP,
                channelNumber = channelNumber,
                privateSessionId = "",
                myDeviceId = _uiState.value.myDeviceId,
                forceAdminClear = true
            ).onSuccess { showMessage("Channel $channelNumber floor cleared") }
                .onFailure { err -> showMessage(err.localizedMessage ?: "Unable to clear channel lock") }
        }
    }

    fun saveIceServerConfig(
        stunText: String,
        turnUrl: String,
        turnUsername: String,
        turnCredential: String
    ) {
        val stuns = stunText.lines().map { it.trim() }.filter { it.isNotBlank() }
        runtimeScope.launch {
            _uiState.update { it.copy(isBusySaving = true) }
            repository.saveIceServerConfig(
                stunUrls = stuns,
                turnUrl = turnUrl,
                turnUsername = turnUsername,
                turnCredential = turnCredential,
                sfuEnabled = false,
                sfuUrl = ""
            ).onSuccess { showMessage("STUN / TURN configuration saved") }
                .onFailure { err -> showMessage(err.localizedMessage ?: "Failed to save ICE server config") }
            _uiState.update { it.copy(isBusySaving = false) }
        }
    }

    fun setKeepActiveInBackground(enabled: Boolean) {
        runtimeScope.launch {
            identityStore.setKeepActiveInBackground(enabled)
            updatePersistentNotification()
        }
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        runtimeScope.launch { identityStore.setAutoStartOnBoot(enabled) }
    }

    fun setRogerBeepEnabled(enabled: Boolean) {
        runtimeScope.launch { identityStore.setRogerBeep(enabled) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        runtimeScope.launch { identityStore.setHaptics(enabled) }
    }

    fun setSpeakerphoneEnabled(enabled: Boolean) {
        runtimeScope.launch {
            identityStore.setSpeakerphone(enabled)
            if (_uiState.value.isPoweredOn && !_uiState.value.isCallInterrupted) {
                audioFocusManager.configureCommunicationRouting(enabled)
            }
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    suspend fun prepareForSignOut() {
        stopTransmittingInternal(playRogerBeep = false)
        stopSessionStreamsOnly()
        val deviceId = _uiState.value.myDeviceId
        if (deviceId.isNotBlank()) {
            audioEngine.disconnectAll(sendBye = true, myDeviceId = deviceId, signalingSender = signalingSender)
            runCatching {
                repository.updateDeviceRadioState(deviceId = deviceId, online = false)
            }
        }
        audioFocusManager.abandonFocusAndRestoreNormalMode()
        WalkieForegroundService.stop(appContext)
        runCatching { identityStore.clearOtpVerification() }
        activeUserId = null
        _uiState.value = WalkieUiState()
    }

    private fun stopSessionStreamsOnly() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        privateSessionJob?.cancel()
        privateSessionJob = null
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        processedSignalIds.clear()
    }

    private fun resyncWebRtcMeshPeers() {
        val state = _uiState.value
        val uid = activeUserId ?: return
        if (!state.isPoweredOn ||
            state.approvalStatus != DeviceApprovalStatus.APPROVED ||
            state.networkTransport == NetworkTransport.OFFLINE ||
            state.myDeviceId.isBlank()
        ) {
            if (state.myDeviceId.isNotBlank()) {
                audioEngine.disconnectAll(sendBye = false, myDeviceId = state.myDeviceId, signalingSender = null)
            }
            return
        }

        val roomId = buildRoomId(
            mode = state.radioMode,
            channelNumber = state.activeChannel,
            myDeviceId = state.myDeviceId,
            targetDeviceId = state.privateTargetDeviceId
        )

        val eligiblePeers = when (state.radioMode) {
            RadioMode.GROUP -> state.approvedDevices.filter { peer ->
                peer.id != state.myDeviceId &&
                    peer.online &&
                    peer.radioMode == RadioMode.GROUP &&
                    peer.currentChannel.toInt() == state.activeChannel
            }

            RadioMode.PRIVATE -> state.approvedDevices.filter { peer ->
                peer.id == state.privateTargetDeviceId &&
                    peer.online &&
                    peer.radioMode == RadioMode.PRIVATE &&
                    peer.privateTargetDeviceId == state.myDeviceId
            }
        }

        audioEngine.syncRoomPeers(
            roomId = roomId,
            myDeviceId = state.myDeviceId,
            myUid = uid,
            remotePeers = eligiblePeers,
            signalingSender = signalingSender
        )
    }

    private fun refreshActiveSpeakerFromRooms() {
        val state = _uiState.value
        val (speakerId, speakerName) = if (state.radioMode == RadioMode.PRIVATE) {
            val s = state.activePrivateSession
            (s?.activeSpeakerDeviceId.orEmpty()) to (s?.activeSpeakerName.orEmpty())
        } else {
            val ch = state.channels.firstOrNull { it.channelNumber.toInt() == state.activeChannel }
            (ch?.activeSpeakerDeviceId.orEmpty()) to (ch?.activeSpeakerName.orEmpty())
        }

        val busyByOther = speakerId.isNotBlank() && speakerId != state.myDeviceId
        _uiState.update {
            it.copy(
                activeSpeakerDeviceId = speakerId,
                activeSpeakerName = speakerName,
                isChannelBusyByOther = busyByOther
            )
        }
    }

    private fun recomputeConnectionStatus() {
        val state = _uiState.value
        val status = when {
            !state.isPoweredOn -> ConnectionStatus.POWERED_OFF
            state.networkTransport == NetworkTransport.OFFLINE -> ConnectionStatus.OFFLINE
            state.approvalStatus == DeviceApprovalStatus.REVOKED -> ConnectionStatus.REVOKED
            state.approvalStatus == DeviceApprovalStatus.PENDING -> ConnectionStatus.PENDING_APPROVAL
            state.isCallInterrupted -> ConnectionStatus.CALL_PAUSED
            state.myDevice != null -> ConnectionStatus.CONNECTED
            else -> ConnectionStatus.CONNECTING
        }
        _uiState.update { it.copy(connectionStatus = status) }
        updatePersistentNotification()
    }

    private fun updatePersistentNotification() {
        val state = _uiState.value
        if (!state.isPoweredOn || !state.preferences.keepActiveInBackground) {
            WalkieForegroundService.stop(appContext)
            return
        }
        val modeLabel = if (state.radioMode == RadioMode.PRIVATE) {
            "PRIVATE: ${state.privateTargetName.ifBlank { state.privateTargetDeviceId }}"
        } else {
            "CH ${state.activeChannel} (${state.onlineInActiveChannelCount}/$MAX_FAMILY_DEVICES)"
        }
        val statusText = when {
            state.isTransmitting -> "TX LIVE • $modeLabel"
            state.connectionStatus == ConnectionStatus.OFFLINE -> "Offline • Auto-Reconnecting…"
            state.connectionStatus == ConnectionStatus.CALL_PAUSED -> "External Call Active • Mic Released"
            state.connectionStatus == ConnectionStatus.PENDING_APPROVAL -> "Pending Admin Approval"
            state.connectionStatus == ConnectionStatus.CONNECTED -> "Active in Background • $modeLabel"
            else -> "Connecting in Background…"
        }
        WalkieForegroundService.updateStatus(
            context = appContext,
            title = "Mamun Walkie Talkie",
            body = statusText,
            transmitting = state.isTransmitting
        )
    }

    private suspend fun syncPresenceToServer(online: Boolean) {
        val state = _uiState.value
        if (state.myDeviceId.isBlank()) return
        repository.updateDeviceRadioState(
            deviceId = state.myDeviceId,
            online = online,
            batteryLevel = state.batteryPercent,
            networkType = state.networkTransport
        )
    }

    private fun handleStreamError(error: Throwable, label: String) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(RUNTIME_TAG, "Error observing $label", rootError)
        showMessage("Sync issue ($label): ${rootError.localizedMessage ?: "retrying"}")
    }

    private fun showMessage(msg: String) {
        _uiState.update { it.copy(snackbarMessage = msg) }
    }
}
