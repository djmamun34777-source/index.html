package com.example.data

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Date

sealed interface PttFloorResult {
    data object Acquired : PttFloorResult
    data class Busy(val speakerName: String, val speakerDeviceId: String) : PttFloorResult
    data class Failed(val message: String) : PttFloorResult
}

class WalkieRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun isCurrentUserBootstrapAdmin(): Boolean {
        val user = auth.currentUser ?: return false
        return user.email.equals(BOOTSTRAP_ADMIN_EMAIL, ignoreCase = true) && user.isEmailVerified
    }

    suspend fun checkIsAdmin(uid: String): Boolean {
        if (isCurrentUserBootstrapAdmin()) return true
        return try {
            val doc = db.collection("admins").document(uid).get().await()
            doc.exists()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "admins/$uid")
            false
        }
    }

    /**
     * Registers or refreshes this phone's device record.
     * - If the user is the bootstrapped admin and this device is new, it automatically creates
     *   the admin record, assigns an available slot (1..6), seeds Channels 1..6, and default ICE config.
     * - If the user is a non-admin family member and the device is new, it registers with status = PENDING
     *   and slotNumber = 0 so the admin can approve it into one of the 6 slots.
     */
    suspend fun registerOrSyncDevice(
        deviceId: String,
        deviceName: String,
        preferredChannel: Int,
        batteryLevel: Int,
        networkType: NetworkTransport
    ): Result<WalkieDevice> {
        val path = "devices/$deviceId"
        return try {
            val uid = requireUserId()
            val isAdmin = checkIsAdmin(uid)
            val safeName = deviceName.trim().take(40).ifBlank { "Family Radio" }
            val safeChannel = preferredChannel.coerceIn(MIN_CHANNEL, MAX_CHANNEL)
            val safeBattery = batteryLevel.coerceIn(0, 100)

            val docRef = db.collection("devices").document(deviceId)
            val existingSnap = docRef.get().await()

            if (isAdmin) {
                ensureAdminBootstrapResources(uid)
            }

            if (existingSnap.exists()) {
                val existingDevice = existingSnap.toObject(
                    WalkieDevice::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                if (existingDevice != null) {
                    val status = existingDevice.approvalStatus
                    if (isAdmin && status != DeviceApprovalStatus.APPROVED) {
                        val freeSlot = findFirstFreeSlotNumber()
                        if (freeSlot != null) {
                            approveDeviceToSlot(deviceId, freeSlot).getOrThrow()
                        }
                    } else if (status == DeviceApprovalStatus.APPROVED || status == DeviceApprovalStatus.PENDING) {
                        val updates = mutableMapOf<String, Any>(
                            "online" to (networkType != NetworkTransport.OFFLINE),
                            "batteryLevel" to safeBattery,
                            "networkType" to networkType.name,
                            "lastSeenAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        docRef.update(updates).await()
                    }
                    val refreshed = docRef.get().await().toObject(
                        WalkieDevice::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ) ?: existingDevice
                    return Result.success(refreshed)
                }
            }

            // New device registration
            if (isAdmin) {
                val freeSlot = findFirstFreeSlotNumber()
                val assignedSlot = freeSlot ?: 0
                val status = if (assignedSlot in 1..MAX_FAMILY_DEVICES) {
                    DeviceApprovalStatus.APPROVED.name
                } else {
                    DeviceApprovalStatus.PENDING.name
                }
                val activeMemberUids = collectApprovedMemberUids(extraUid = uid)
                val devicePayload = mapOf(
                    "ownerUid" to uid,
                    "deviceName" to safeName,
                    "slotNumber" to assignedSlot,
                    "status" to status,
                    "online" to (networkType != NetworkTransport.OFFLINE),
                    "currentChannel" to safeChannel,
                    "mode" to RadioMode.GROUP.name,
                    "privateTargetDeviceId" to "",
                    "batteryLevel" to safeBattery,
                    "networkType" to networkType.name,
                    "adminDevice" to true,
                    "memberUids" to activeMemberUids,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                val batch = db.batch()
                batch.set(docRef, devicePayload)
                if (assignedSlot in 1..MAX_FAMILY_DEVICES) {
                    val slotRef = db.collection("slots").document(assignedSlot.toString())
                    batch.set(
                        slotRef,
                        mapOf(
                            "deviceId" to deviceId,
                            "ownerUid" to uid,
                            "deviceName" to safeName,
                            "memberUids" to activeMemberUids,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.commit().await()
                syncMemberUidsAcrossNetwork(activeMemberUids)
            } else {
                val pendingPayload = mapOf(
                    "ownerUid" to uid,
                    "deviceName" to safeName,
                    "slotNumber" to 0,
                    "status" to DeviceApprovalStatus.PENDING.name,
                    "online" to (networkType != NetworkTransport.OFFLINE),
                    "currentChannel" to safeChannel,
                    "mode" to RadioMode.GROUP.name,
                    "privateTargetDeviceId" to "",
                    "batteryLevel" to safeBattery,
                    "networkType" to networkType.name,
                    "adminDevice" to false,
                    "memberUids" to listOf(uid),
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(pendingPayload).await()
            }

            val created = docRef.get().await().toObject(
                WalkieDevice::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: WalkieDevice(id = deviceId, ownerUid = uid, deviceName = safeName)
            Result.success(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    private suspend fun ensureAdminBootstrapResources(adminUid: String) {
        val adminRef = db.collection("admins").document(adminUid)
        if (!adminRef.get().await().exists()) {
            adminRef.set(
                mapOf(
                    "displayName" to (auth.currentUser?.displayName?.take(60)?.ifBlank { "Family Admin" } ?: "Family Admin"),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }

        val memberUids = collectApprovedMemberUids(extraUid = adminUid)
        val defaultChannelNames = listOf(
            "Family Main",
            "Home Base",
            "Travel & Road",
            "Emergency",
            "Work & Errands",
            "Open Comms"
        )
        for (ch in MIN_CHANNEL..MAX_CHANNEL) {
            val chRef = db.collection("channels").document(buildChannelDocId(ch))
            if (!chRef.get().await().exists()) {
                chRef.set(
                    mapOf(
                        "channelNumber" to ch,
                        "name" to "CH $ch • ${defaultChannelNames[ch - 1]}",
                        "activeSpeakerDeviceId" to "",
                        "activeSpeakerName" to "",
                        "activeSpeakerUid" to "",
                        "memberUids" to memberUids,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        }

        val iceRef = db.collection("settings").document(ICE_CONFIG_DOC_ID)
        val iceSnap = iceRef.get().await()
        if (!iceSnap.exists()) {
            iceRef.set(
                mapOf(
                    "stunUrls" to DEFAULT_STUN_SERVERS,
                    "turnUrl" to DEFAULT_TURN_URL,
                    "turnUsername" to DEFAULT_TURN_USERNAME,
                    "turnCredential" to DEFAULT_TURN_CREDENTIAL,
                    "sfuEnabled" to false,
                    "sfuUrl" to "",
                    "memberUids" to memberUids,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } else if (iceSnap.getString("turnUrl").isNullOrBlank()) {
            iceRef.update(
                mapOf(
                    "stunUrls" to DEFAULT_STUN_SERVERS,
                    "turnUrl" to DEFAULT_TURN_URL,
                    "turnUsername" to DEFAULT_TURN_USERNAME,
                    "turnCredential" to DEFAULT_TURN_CREDENTIAL,
                    "memberUids" to memberUids,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    private suspend fun findFirstFreeSlotNumber(): Int? {
        val snap = db.collection("slots").get().await()
        val used = snap.documents.mapNotNull { it.id.toIntOrNull() }.toSet()
        return (1..MAX_FAMILY_DEVICES).firstOrNull { it !in used }
    }

    private suspend fun collectApprovedMemberUids(extraUid: String? = null): List<String> {
        val uids = linkedSetOf<String>()
        if (!extraUid.isNullOrBlank()) uids.add(extraUid)
        val currentUid = auth.currentUser?.uid
        if (!currentUid.isNullOrBlank() && isCurrentUserBootstrapAdmin()) {
            uids.add(currentUid)
        }
        val slots = db.collection("slots").get().await()
        slots.documents.forEach { doc ->
            val owner = doc.getString("ownerUid")
            if (!owner.isNullOrBlank()) uids.add(owner)
        }
        return uids.take(7)
    }

    private suspend fun syncMemberUidsAcrossNetwork(memberUids: List<String>) {
        val bounded = memberUids.distinct().take(7)
        val batch = db.batch()
        val slots = db.collection("slots").get().await()
        slots.documents.forEach { doc ->
            batch.update(
                doc.reference,
                mapOf("memberUids" to bounded, "updatedAt" to FieldValue.serverTimestamp())
            )
        }
        val approvedDevices = db.collection("devices")
            .whereEqualTo("status", DeviceApprovalStatus.APPROVED.name)
            .get()
            .await()
        approvedDevices.documents.forEach { doc ->
            batch.update(
                doc.reference,
                mapOf("memberUids" to bounded, "updatedAt" to FieldValue.serverTimestamp())
            )
        }
        for (ch in MIN_CHANNEL..MAX_CHANNEL) {
            val chRef = db.collection("channels").document(buildChannelDocId(ch))
            if (chRef.get().await().exists()) {
                batch.update(
                    chRef,
                    mapOf("memberUids" to bounded, "updatedAt" to FieldValue.serverTimestamp())
                )
            }
        }
        val iceRef = db.collection("settings").document(ICE_CONFIG_DOC_ID)
        if (iceRef.get().await().exists()) {
            batch.update(
                iceRef,
                mapOf("memberUids" to bounded, "updatedAt" to FieldValue.serverTimestamp())
            )
        }
        batch.commit().await()
    }

    // --- REALTIME OBSERVATION FLOWS ---

    fun observeMyDevice(deviceId: String): Flow<WalkieDevice?> = flow {
        val uid = requireUserId()
        val path = "devices/$deviceId"
        emitAll(
            db.collection("devices").document(deviceId)
                .snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(
                            WalkieDevice::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else null
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.GET, path, uid)
                    throw error
                }
        )
    }

    fun observeDevices(isAdmin: Boolean): Flow<List<WalkieDevice>> = flow {
        val uid = requireUserId()
        val path = "devices"
        val query = if (isAdmin) {
            db.collection("devices")
        } else {
            db.collection("devices").whereArrayContains("memberUids", uid)
        }
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(
                        WalkieDevice::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedWith(compareBy<WalkieDevice> {
                        if (it.slotNumber in 1..6) it.slotNumber else 99L
                    }.thenBy { it.deviceName.lowercase() })
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeSlots(isAdmin: Boolean): Flow<List<DeviceSlot>> = flow {
        val uid = requireUserId()
        val path = "slots"
        val query = if (isAdmin) {
            db.collection("slots")
        } else {
            db.collection("slots").whereArrayContains("memberUids", uid)
        }
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(
                        DeviceSlot::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.id.toIntOrNull() ?: 99 }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeChannels(isAdmin: Boolean): Flow<List<RadioChannel>> = flow {
        val uid = requireUserId()
        val path = "channels"
        val query = if (isAdmin) {
            db.collection("channels")
        } else {
            db.collection("channels").whereArrayContains("memberUids", uid)
        }
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(
                        RadioChannel::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.channelNumber }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observePrivateSession(sessionId: String): Flow<PrivateSession?> = flow {
        val uid = requireUserId()
        val path = "private_sessions/$sessionId"
        emitAll(
            db.collection("private_sessions").document(sessionId)
                .snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(
                            PrivateSession::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else null
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.GET, path, uid)
                    throw error
                }
        )
    }

    fun observeIncomingSignals(myDeviceId: String): Flow<List<WebRtcSignal>> = flow {
        val uid = requireUserId()
        val path = "webrtc_signals"
        emitAll(
            db.collection("webrtc_signals")
                .whereEqualTo("toUid", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        WebRtcSignal::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                        .filter { it.toDeviceId == myDeviceId }
                        .sortedBy { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeIceConfig(isAdmin: Boolean): Flow<IceServerConfig> = flow {
        val uid = requireUserId()
        val path = "settings/$ICE_CONFIG_DOC_ID"
        val query = if (isAdmin) {
            db.collection("settings")
        } else {
            db.collection("settings").whereArrayContains("memberUids", uid)
        }
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.documents
                        .firstOrNull { it.id == ICE_CONFIG_DOC_ID }
                        ?.toObject(
                            IceServerConfig::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                        ?: IceServerConfig(id = ICE_CONFIG_DOC_ID)
                }
                .catch { error ->
                    logStreamErrorIfActive(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    // --- RADIO STATE & TELEMETRY UPDATES ---

    suspend fun updateDeviceRadioState(
        deviceId: String,
        online: Boolean? = null,
        channel: Int? = null,
        mode: RadioMode? = null,
        privateTargetDeviceId: String? = null,
        batteryLevel: Int? = null,
        networkType: NetworkTransport? = null,
        deviceName: String? = null
    ): Result<Unit> {
        val path = "devices/$deviceId"
        return try {
            requireUserId()
            require(deviceId.isNotBlank()) { "Device ID cannot be blank" }
            val updates = mutableMapOf<String, Any>(
                "lastSeenAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (online != null) updates["online"] = online
            if (channel != null) updates["currentChannel"] = channel.coerceIn(MIN_CHANNEL, MAX_CHANNEL)
            if (mode != null) updates["mode"] = mode.name
            if (privateTargetDeviceId != null) updates["privateTargetDeviceId"] = privateTargetDeviceId.take(64)
            if (batteryLevel != null) updates["batteryLevel"] = batteryLevel.coerceIn(0, 100)
            if (networkType != null) updates["networkType"] = networkType.name
            if (deviceName != null) {
                val cleaned = deviceName.trim().take(40)
                if (cleaned.isNotEmpty()) updates["deviceName"] = cleaned
            }
            db.collection("devices").document(deviceId).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- PRIVATE 1-TO-1 SESSION ISOLATION ---

    suspend fun ensurePrivateSession(
        myDeviceId: String,
        targetDevice: WalkieDevice
    ): Result<String> {
        val sessionId = buildPrivateSessionId(myDeviceId, targetDevice.id)
        val path = "private_sessions/$sessionId"
        return try {
            val uid = requireUserId()
            val sortedDevices = listOf(myDeviceId, targetDevice.id).sorted()
            val participants = listOf(uid, targetDevice.ownerUid).distinct().take(2)
            val docRef = db.collection("private_sessions").document(sessionId)
            val snap = docRef.get().await()
            if (!snap.exists()) {
                docRef.set(
                    mapOf(
                        "deviceAId" to sortedDevices[0],
                        "deviceBId" to sortedDevices[1],
                        "participantUids" to participants,
                        "activeSpeakerDeviceId" to "",
                        "activeSpeakerName" to "",
                        "activeSpeakerUid" to "",
                        "active" to true,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.update(
                    mapOf(
                        "active" to true,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    // --- PTT FLOOR LOCK (AUDIO COLLISION CONTROL) ---

    suspend fun acquirePttFloor(
        mode: RadioMode,
        channelNumber: Int,
        privateSessionId: String,
        myDeviceId: String,
        myDeviceName: String
    ): PttFloorResult {
        val collection = if (mode == RadioMode.PRIVATE && privateSessionId.isNotBlank()) {
            "private_sessions"
        } else {
            "channels"
        }
        val docId = if (mode == RadioMode.PRIVATE && privateSessionId.isNotBlank()) {
            privateSessionId
        } else {
            buildChannelDocId(channelNumber)
        }
        val path = "$collection/$docId"

        return try {
            val uid = requireUserId()
            val docRef = db.collection(collection).document(docId)
            val safeName = myDeviceName.trim().take(40).ifBlank { myDeviceId }

            db.runTransaction { tx ->
                val snap = tx.get(docRef)
                if (!snap.exists()) {
                    throw IllegalStateException("Channel or session not initialized yet.")
                }
                val currentSpeakerId = snap.getString("activeSpeakerDeviceId").orEmpty()
                val currentSpeakerName = snap.getString("activeSpeakerName").orEmpty()
                val lockedAt = snap.getTimestamp("pttLockedAt")?.toDate()
                val isStale = lockedAt == null || (Date().time - lockedAt.time) > 30_000L

                if (currentSpeakerId.isNotBlank() && currentSpeakerId != myDeviceId && !isStale) {
                    PttFloorResult.Busy(
                        speakerName = currentSpeakerName.ifBlank { currentSpeakerId },
                        speakerDeviceId = currentSpeakerId
                    )
                } else {
                    tx.update(
                        docRef,
                        mapOf(
                            "activeSpeakerDeviceId" to myDeviceId,
                            "activeSpeakerName" to safeName,
                            "activeSpeakerUid" to uid,
                            "pttLockedAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    PttFloorResult.Acquired
                }
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            PttFloorResult.Failed(e.localizedMessage ?: "Unable to lock channel")
        }
    }

    suspend fun releasePttFloor(
        mode: RadioMode,
        channelNumber: Int,
        privateSessionId: String,
        myDeviceId: String,
        forceAdminClear: Boolean = false
    ): Result<Unit> {
        val collection = if (mode == RadioMode.PRIVATE && privateSessionId.isNotBlank()) {
            "private_sessions"
        } else {
            "channels"
        }
        val docId = if (mode == RadioMode.PRIVATE && privateSessionId.isNotBlank()) {
            privateSessionId
        } else {
            buildChannelDocId(channelNumber)
        }
        val path = "$collection/$docId"

        return try {
            requireUserId()
            val docRef = db.collection(collection).document(docId)
            db.runTransaction { tx ->
                val snap = tx.get(docRef)
                if (!snap.exists()) return@runTransaction
                val currentSpeakerId = snap.getString("activeSpeakerDeviceId").orEmpty()
                if (forceAdminClear || currentSpeakerId == myDeviceId) {
                    tx.update(
                        docRef,
                        mapOf(
                            "activeSpeakerDeviceId" to "",
                            "activeSpeakerName" to "",
                            "activeSpeakerUid" to "",
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- WEBRTC SIGNALING ---

    suspend fun sendWebRtcSignal(
        roomId: String,
        fromDeviceId: String,
        toDeviceId: String,
        toUid: String,
        signalType: SignalType,
        sdp: String = "",
        iceCandidate: String = "",
        iceSdpMid: String = "",
        iceSdpMLineIndex: Int = -1
    ): Result<Unit> {
        val docRef = db.collection("webrtc_signals").document()
        val path = docRef.path
        return try {
            val uid = requireUserId()
            val payload = mapOf(
                "roomId" to roomId.take(128),
                "fromDeviceId" to fromDeviceId.take(64),
                "toDeviceId" to toDeviceId.take(64),
                "fromUid" to uid,
                "toUid" to toUid.take(128),
                "signalType" to signalType.name,
                "sdp" to sdp.take(32768),
                "iceCandidate" to iceCandidate.take(2048),
                "iceSdpMid" to iceSdpMid.take(64),
                "iceSdpMLineIndex" to iceSdpMLineIndex.coerceIn(-1, 16),
                "createdAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun consumeWebRtcSignal(signalId: String): Result<Unit> {
        val path = "webrtc_signals/$signalId"
        return try {
            requireUserId()
            if (signalId.isBlank()) return Result.success(Unit)
            db.collection("webrtc_signals").document(signalId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- ADMIN PANEL OPERATIONS (6-SLOT ENFORCEMENT) ---

    suspend fun approveDeviceToSlot(deviceId: String, requestedSlot: Int? = null): Result<Int> {
        val path = "devices/$deviceId"
        return try {
            val adminUid = requireUserId()
            require(checkIsAdmin(adminUid)) { "Admin access required." }

            val deviceRef = db.collection("devices").document(deviceId)
            val deviceSnap = deviceRef.get().await()
            val device = deviceSnap.toObject(
                WalkieDevice::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: return Result.failure(IllegalArgumentException("Device not found"))

            val targetSlot = requestedSlot?.takeIf { it in 1..MAX_FAMILY_DEVICES }
                ?: findFirstFreeSlotNumber()
                ?: return Result.failure(
                    IllegalStateException("All 6 family device slots are full. Remove an approved device first.")
                )

            val slotRef = db.collection("slots").document(targetSlot.toString())
            val slotSnap = slotRef.get().await()
            if (slotSnap.exists() && slotSnap.getString("deviceId") != deviceId) {
                return Result.failure(IllegalStateException("Slot #$targetSlot is already occupied."))
            }

            val updatedMembers = collectApprovedMemberUids(extraUid = device.ownerUid)
            val batch = db.batch()
            if (slotSnap.exists()) {
                batch.update(
                    slotRef,
                    mapOf(
                        "deviceId" to deviceId,
                        "ownerUid" to device.ownerUid,
                        "deviceName" to device.deviceName.take(40).ifBlank { "Family Radio" },
                        "memberUids" to updatedMembers,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            } else {
                batch.set(
                    slotRef,
                    mapOf(
                        "deviceId" to deviceId,
                        "ownerUid" to device.ownerUid,
                        "deviceName" to device.deviceName.take(40).ifBlank { "Family Radio" },
                        "memberUids" to updatedMembers,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.update(
                deviceRef,
                mapOf(
                    "slotNumber" to targetSlot,
                    "status" to DeviceApprovalStatus.APPROVED.name,
                    "memberUids" to updatedMembers,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            syncMemberUidsAcrossNetwork(updatedMembers)
            Result.success(targetSlot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun removeDeviceFromSlot(device: WalkieDevice): Result<Unit> {
        val path = "devices/${device.id}"
        return try {
            val adminUid = requireUserId()
            require(checkIsAdmin(adminUid)) { "Admin access required." }

            val batch = db.batch()
            if (device.slotNumber in 1L..MAX_FAMILY_DEVICES.toLong()) {
                val slotRef = db.collection("slots").document(device.slotNumber.toString())
                batch.delete(slotRef)
            }
            val deviceRef = db.collection("devices").document(device.id)
            batch.update(
                deviceRef,
                mapOf(
                    "slotNumber" to 0,
                    "status" to DeviceApprovalStatus.REVOKED.name,
                    "online" to false,
                    "privateTargetDeviceId" to "",
                    "mode" to RadioMode.GROUP.name,
                    "memberUids" to listOf(device.ownerUid),
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()

            val remainingMembers = collectApprovedMemberUids(extraUid = adminUid)
            syncMemberUidsAcrossNetwork(remainingMembers)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun renameDevice(device: WalkieDevice, newName: String): Result<Unit> {
        val path = "devices/${device.id}"
        return try {
            requireUserId()
            val cleaned = newName.trim().take(40)
            require(cleaned.isNotEmpty()) { "Device name cannot be empty." }
            val batch = db.batch()
            batch.update(
                db.collection("devices").document(device.id),
                mapOf(
                    "deviceName" to cleaned,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            if (device.slotNumber in 1L..MAX_FAMILY_DEVICES.toLong() && checkIsAdmin(requireUserId())) {
                val slotRef = db.collection("slots").document(device.slotNumber.toString())
                if (slotRef.get().await().exists()) {
                    batch.update(
                        slotRef,
                        mapOf(
                            "deviceName" to cleaned,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun renameChannel(channelNumber: Int, newName: String): Result<Unit> {
        val docId = buildChannelDocId(channelNumber)
        val path = "channels/$docId"
        return try {
            val adminUid = requireUserId()
            require(checkIsAdmin(adminUid)) { "Admin access required." }
            val cleaned = newName.trim().take(40)
            require(cleaned.isNotEmpty()) { "Channel name cannot be empty." }
            db.collection("channels").document(docId).update(
                mapOf(
                    "name" to cleaned,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun saveIceServerConfig(
        stunUrls: List<String>,
        turnUrl: String,
        turnUsername: String,
        turnCredential: String,
        sfuEnabled: Boolean,
        sfuUrl: String
    ): Result<Unit> {
        val path = "settings/$ICE_CONFIG_DOC_ID"
        return try {
            val adminUid = requireUserId()
            require(checkIsAdmin(adminUid)) { "Admin access required." }
            val validStuns = stunUrls.map { it.trim() }.filter { it.isNotBlank() }.take(6)
                .ifEmpty { DEFAULT_STUN_SERVERS }
            val memberUids = collectApprovedMemberUids(extraUid = adminUid)
            val docRef = db.collection("settings").document(ICE_CONFIG_DOC_ID)
            if (docRef.get().await().exists()) {
                docRef.update(
                    mapOf(
                        "stunUrls" to validStuns,
                        "turnUrl" to turnUrl.trim().take(256),
                        "turnUsername" to turnUsername.trim().take(128),
                        "turnCredential" to turnCredential.trim().take(256),
                        "sfuEnabled" to sfuEnabled,
                        "sfuUrl" to sfuUrl.trim().take(256),
                        "memberUids" to memberUids,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "stunUrls" to validStuns,
                        "turnUrl" to turnUrl.trim().take(256),
                        "turnUsername" to turnUsername.trim().take(128),
                        "turnCredential" to turnCredential.trim().take(256),
                        "sfuEnabled" to sfuEnabled,
                        "sfuUrl" to sfuUrl.trim().take(256),
                        "memberUids" to memberUids,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    private fun logStreamErrorIfActive(
        error: Throwable,
        operationType: OperationType,
        path: String,
        expectedUid: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) return
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == expectedUid) {
            handleFirestoreError(exceptionToLog, operationType, path)
        }
    }
}
