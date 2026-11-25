package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val FOCUS_TAG = "WalkieAudioFocus"

/**
 * 4-Layer External Call & Microphone Coexistence Manager for IMO, WhatsApp, Messenger, Telegram & SIM Calls.
 *
 * Layers:
 * 1. [AudioManager.OnAudioFocusChangeListener] — immediate reaction to transient/permanent focus loss.
 * 2. [AudioManager.AudioRecordingCallback] (API 24+) — detects third-party VoIP apps acquiring the mic
 *    (without false-positive self-detection against our own WebRTC AudioRecord) and detects the exact
 *    moment the external app releases the mic when the call ends.
 * 3. [AudioManager.OnModeChangedListener] (API 31+) — detects MODE_IN_CALL / MODE_RINGTONE / MODE_NORMAL.
 * 4. Recovery Watcher — while suspended for an external call, monitors mode + active recordings and
 *    automatically reclaims AudioFocus & hardware microphone as soon as the external call ends.
 */
class WalkieAudioFocusManager(
    context: Context,
    private val onExternalCallInterrupted: (reason: String) -> Unit,
    private val onAudioFocusRestored: () -> Unit
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isCallInterrupted = MutableStateFlow(false)
    val isCallInterrupted: StateFlow<Boolean> = _isCallInterrupted.asStateFlow()

    private val _volumePercent = MutableStateFlow(readSystemVolumePercent())
    val volumePercent: StateFlow<Int> = _volumePercent.asStateFlow()

    private var activeFocusRequest: AudioFocusRequest? = null
    private var monitoringStarted = false
    private var recoveryWatcherJob: Job? = null

    @Volatile
    var ownPttActive: Boolean = false

    @Volatile
    var ownAudioCaptureActive: Boolean = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                triggerExternalCallInterruption("External call/audio focus lost")
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                scheduleAutoReclaimCheck(delayMs = 450L)
            }
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            val activeConfigs = configs.orEmpty()
            val externalRecordingActive = hasThirdPartyRecording(activeConfigs)
            if (externalRecordingActive && !_isCallInterrupted.value) {
                triggerExternalCallInterruption("Third-party app (IMO/WhatsApp/Call) claimed microphone")
            } else if (!externalRecordingActive && _isCallInterrupted.value && !isExternalPhoneCallActive()) {
                scheduleAutoReclaimCheck(delayMs = 1200L)
            }
        }
    }

    private var modeChangedListener: Any? = null

    fun startMonitoring() {
        if (monitoringStarted) return
        monitoringStarted = true

        runCatching {
            audioManager.registerAudioRecordingCallback(recordingCallback, mainHandler)
        }.onFailure {
            Log.w(FOCUS_TAG, "Unable to register AudioRecordingCallback: ${it.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnModeChangedListener { mode ->
                if (mode == AudioManager.MODE_IN_CALL ||
                    mode == AudioManager.MODE_RINGTONE ||
                    mode == AudioManager.MODE_CALL_SCREENING
                ) {
                    triggerExternalCallInterruption("Phone/VoIP call active (mode=$mode)")
                } else if (mode == AudioManager.MODE_NORMAL && _isCallInterrupted.value) {
                    scheduleAutoReclaimCheck(delayMs = 1200L)
                }
            }
            modeChangedListener = listener
            runCatching {
                audioManager.addOnModeChangedListener(appContext.mainExecutor, listener)
            }
        }
    }

    fun stopMonitoring() {
        if (!monitoringStarted) return
        monitoringStarted = false
        recoveryWatcherJob?.cancel()
        recoveryWatcherJob = null

        runCatching { audioManager.unregisterAudioRecordingCallback(recordingCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (modeChangedListener as? AudioManager.OnModeChangedListener)?.let { listener ->
                runCatching { audioManager.removeOnModeChangedListener(listener) }
            }
            modeChangedListener = null
        }
    }

    private fun triggerExternalCallInterruption(reason: String) {
        if (_isCallInterrupted.value) return
        Log.i(FOCUS_TAG, "Releasing mic for external call: $reason")
        _isCallInterrupted.value = true
        onExternalCallInterrupted(reason)
        startRecoveryPollingWatcher()
    }

    /**
     * Watches while an external call (IMO/WhatsApp/Messenger/SIM) is in progress so even if
     * Android does not send AUDIOFOCUS_GAIN after a VoIP app hangs up, we STILL auto-reclaim
     * the microphone as soon as the call finishes!
     */
    private fun startRecoveryPollingWatcher() {
        recoveryWatcherJob?.cancel()
        recoveryWatcherJob = monitorScope.launch {
            var quietTicks = 0
            while (isActive && _isCallInterrupted.value) {
                delay(1000L)
                val callModeActive = isExternalPhoneCallActive()
                val thirdPartyRec = runCatching {
                    hasThirdPartyRecording(audioManager.activeRecordingConfigurations)
                }.getOrDefault(false)

                if (!callModeActive && !thirdPartyRec) {
                    quietTicks++
                    if (quietTicks >= 2) {
                        attemptAutoReclaimNow()
                        break
                    }
                } else {
                    quietTicks = 0
                }
            }
        }
    }

    private fun scheduleAutoReclaimCheck(delayMs: Long) {
        recoveryWatcherJob?.cancel()
        recoveryWatcherJob = monitorScope.launch {
            delay(delayMs)
            if (!_isCallInterrupted.value) return@launch
            val callModeActive = isExternalPhoneCallActive()
            val thirdPartyRec = runCatching {
                hasThirdPartyRecording(audioManager.activeRecordingConfigurations)
            }.getOrDefault(false)
            if (!callModeActive && !thirdPartyRec) {
                attemptAutoReclaimNow()
            } else {
                startRecoveryPollingWatcher()
            }
        }
    }

    private fun attemptAutoReclaimNow() {
        if (!_isCallInterrupted.value) return
        val reclaimed = requestRadioFocus(exclusiveForPtt = false)
        if (reclaimed) {
            Log.i(FOCUS_TAG, "External call finished — microphone & audio focus auto-reclaimed!")
            _isCallInterrupted.value = false
            onAudioFocusRestored()
        } else {
            startRecoveryPollingWatcher()
        }
    }

    /**
     * Accurately distinguishes genuine third-party microphone usage (IMO/WhatsApp/Messenger/Phone)
     * from our own WebRTC AudioRecord session so the app NEVER self-triggers an external call pause.
     */
    private fun hasThirdPartyRecording(configs: List<AudioRecordingConfiguration>): Boolean {
        if (configs.isEmpty()) return false

        // On Android 10+, if Android silenced an active recording client, another higher-priority
        // app (like a phone or VoIP call) has preempted the microphone!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (configs.any { it.isClientSilenced }) {
                return true
            }
        }

        // If more than 1 recording session is active at the same time, another app is using the mic
        if (configs.size > 1) return true

        // Exactly 1 active recording config: if our own app is NOT holding an AudioRecord right now
        // (e.g. standby without peers, or already suspended during recovery polling), that 1 config
        // definitely belongs to a third-party app!
        return !ownAudioCaptureActive && !ownPttActive
    }

    fun isExternalPhoneCallActive(): Boolean {
        val mode = audioManager.mode
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_RINGTONE ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING)
    }

    fun requestRadioFocus(exclusiveForPtt: Boolean): Boolean {
        if (isExternalPhoneCallActive()) {
            _isCallInterrupted.value = true
            startRecoveryPollingWatcher()
            return false
        }
        val gainType = if (exclusiveForPtt) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        } else {
            AudioManager.AUDIOFOCUS_GAIN
        }

        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(gainType)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
                .build()
            activeFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            activeFocusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                gainType
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        if (granted) {
            _isCallInterrupted.value = false
        } else {
            _isCallInterrupted.value = true
            startRecoveryPollingWatcher()
        }
        return granted
    }

    fun configureCommunicationRouting(speakerphoneOn: Boolean) {
        if (isExternalPhoneCallActive() || _isCallInterrupted.value) return
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (speakerphoneOn) {
                    val speaker = audioManager.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) {
                        audioManager.setCommunicationDevice(speaker)
                    }
                } else {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = speakerphoneOn
            }
        }
    }

    fun abandonFocusAndRestoreNormalMode() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                activeFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusChangeListener)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            if (!isExternalPhoneCallActive()) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    fun refreshSystemVolume() {
        _volumePercent.value = readSystemVolumePercent()
    }

    fun readSystemVolumePercent(): Int {
        val stream = AudioManager.STREAM_VOICE_CALL
        val max = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(stream)
        return ((current.toFloat() / max.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
    }

    fun setVolumePercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        _volumePercent.value = clamped
        runCatching {
            val voiceMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
            val voiceTarget = ((clamped / 100f) * voiceMax).roundToInt().coerceIn(0, voiceMax)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, voiceTarget, 0)

            val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val musicTarget = ((clamped / 100f) * musicMax).roundToInt().coerceIn(0, musicMax)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, musicTarget, 0)
        }
    }

    fun stepVolume(deltaPercent: Int) {
        setVolumePercent(_volumePercent.value + deltaPercent)
    }
}
