package com.example.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

private val Context.walkiePrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mamun_walkie_prefs"
)

data class LocalRadioPreferences(
    val deviceId: String = "",
    val deviceName: String = "",
    val lastChannel: Int = 1,
    val poweredOn: Boolean = true,
    val keepActiveInBackground: Boolean = true,
    val autoStartOnBoot: Boolean = true,
    val rogerBeepEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val speakerphoneEnabled: Boolean = true,
    val verifiedOtpUid: String = "",
    val verifiedOtpEmail: String = ""
)

class DeviceIdentityStore(private val context: Context) {
    private val keyDeviceId = stringPreferencesKey("device_id")
    private val keyDeviceName = stringPreferencesKey("device_name")
    private val keyLastChannel = intPreferencesKey("last_channel")
    private val keyPoweredOn = booleanPreferencesKey("powered_on")
    private val keyKeepActiveBg = booleanPreferencesKey("keep_active_bg")
    private val keyAutoStartBoot = booleanPreferencesKey("auto_start_boot")
    private val keyRogerBeep = booleanPreferencesKey("roger_beep")
    private val keyHaptics = booleanPreferencesKey("haptics")
    private val keySpeakerphone = booleanPreferencesKey("speakerphone")
    private val keyVerifiedOtpUid = stringPreferencesKey("verified_otp_uid")
    private val keyVerifiedOtpEmail = stringPreferencesKey("verified_otp_email")

    val preferencesFlow: Flow<LocalRadioPreferences> = context.walkiePrefsDataStore.data.map { prefs ->
        LocalRadioPreferences(
            deviceId = prefs[keyDeviceId] ?: "",
            deviceName = prefs[keyDeviceName] ?: defaultDeviceName(),
            lastChannel = (prefs[keyLastChannel] ?: 1).coerceIn(MIN_CHANNEL, MAX_CHANNEL),
            poweredOn = prefs[keyPoweredOn] ?: true,
            keepActiveInBackground = prefs[keyKeepActiveBg] ?: true,
            autoStartOnBoot = prefs[keyAutoStartBoot] ?: true,
            rogerBeepEnabled = prefs[keyRogerBeep] ?: true,
            hapticsEnabled = prefs[keyHaptics] ?: true,
            speakerphoneEnabled = prefs[keySpeakerphone] ?: true,
            verifiedOtpUid = prefs[keyVerifiedOtpUid] ?: "",
            verifiedOtpEmail = prefs[keyVerifiedOtpEmail] ?: ""
        )
    }

    suspend fun readSnapshot(): LocalRadioPreferences = preferencesFlow.first()

    suspend fun getOrCreateDeviceId(): String {
        val current = context.walkiePrefsDataStore.data.first()[keyDeviceId]
        if (!current.isNullOrBlank()) return current

        val generated = generateUniqueDeviceId()
        context.walkiePrefsDataStore.edit { prefs ->
            if (prefs[keyDeviceId].isNullOrBlank()) {
                prefs[keyDeviceId] = generated
            }
            if (prefs[keyDeviceName].isNullOrBlank()) {
                prefs[keyDeviceName] = defaultDeviceName()
            }
            if (prefs[keyPoweredOn] == null) {
                prefs[keyPoweredOn] = true
            }
            if (prefs[keyKeepActiveBg] == null) {
                prefs[keyKeepActiveBg] = true
            }
            if (prefs[keyAutoStartBoot] == null) {
                prefs[keyAutoStartBoot] = true
            }
        }
        return context.walkiePrefsDataStore.data.first()[keyDeviceId] ?: generated
    }

    suspend fun getOrCreateDeviceName(): String {
        val current = context.walkiePrefsDataStore.data.first()[keyDeviceName]
        if (!current.isNullOrBlank()) return current
        val fallback = defaultDeviceName()
        saveDeviceName(fallback)
        return fallback
    }

    suspend fun saveDeviceName(name: String) {
        val cleaned = name.trim().take(40).ifBlank { defaultDeviceName() }
        context.walkiePrefsDataStore.edit { it[keyDeviceName] = cleaned }
    }

    suspend fun saveLastChannel(channel: Int) {
        context.walkiePrefsDataStore.edit {
            it[keyLastChannel] = channel.coerceIn(MIN_CHANNEL, MAX_CHANNEL)
        }
    }

    suspend fun setPoweredOn(poweredOn: Boolean) {
        context.walkiePrefsDataStore.edit { it[keyPoweredOn] = poweredOn }
    }

    suspend fun setKeepActiveInBackground(enabled: Boolean) {
        context.walkiePrefsDataStore.edit { it[keyKeepActiveBg] = enabled }
    }

    suspend fun setAutoStartOnBoot(enabled: Boolean) {
        context.walkiePrefsDataStore.edit { it[keyAutoStartBoot] = enabled }
    }

    suspend fun setRogerBeep(enabled: Boolean) {
        context.walkiePrefsDataStore.edit { it[keyRogerBeep] = enabled }
    }

    suspend fun setHaptics(enabled: Boolean) {
        context.walkiePrefsDataStore.edit { it[keyHaptics] = enabled }
    }

    suspend fun setSpeakerphone(enabled: Boolean) {
        context.walkiePrefsDataStore.edit { it[keySpeakerphone] = enabled }
    }

    suspend fun markOtpVerified(uid: String, email: String) {
        context.walkiePrefsDataStore.edit {
            it[keyVerifiedOtpUid] = uid
            it[keyVerifiedOtpEmail] = email
        }
    }

    suspend fun clearOtpVerification() {
        context.walkiePrefsDataStore.edit {
            it[keyVerifiedOtpUid] = ""
            it[keyVerifiedOtpEmail] = ""
        }
    }

    private fun generateUniqueDeviceId(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        fun block(len: Int) = buildString {
            repeat(len) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
        return "MWT-${block(4)}-${block(4)}"
    }

    private fun defaultDeviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isNotBlank()) model.take(32) else "Family Radio"
    }
}
