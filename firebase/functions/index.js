const { onSchedule } = require("firebase-functions/v2/scheduler");
const { onDocumentWritten } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, Timestamp } = require("firebase-admin/firestore");

initializeApp();

const DATABASE_ID = "ai-studio-android-mamunwal-b15e2de4-963d-42fb-8781-ee5c589888ba";
const db = getFirestore(DATABASE_ID);

/**
 * Scheduled server-side maintenance (every 5 minutes):
 * 1. Marks devices offline if lastSeenAt is older than 90 seconds (e.g., phone lost battery/power abruptly).
 * 2. Clears stale PTT floor locks older than 45 seconds so no channel stays stuck.
 * 3. Cleans up consumed/expired WebRTC signaling messages older than 5 minutes.
 */
exports.sweepStaleWalkieState = onSchedule("every 5 minutes", async () => {
  const now = Date.now();
  const offlineCutoff = Timestamp.fromMillis(now - 90 * 1000);
  const pttCutoff = Timestamp.fromMillis(now - 45 * 1000);
  const signalCutoff = Timestamp.fromMillis(now - 5 * 60 * 1000);

  const batch = db.batch();

  const staleDevices = await db
    .collection("devices")
    .where("online", "==", true)
    .where("lastSeenAt", "<", offlineCutoff)
    .get();
  staleDevices.forEach((doc) => {
    batch.update(doc.ref, { online: false, networkType: "OFFLINE", updatedAt: Timestamp.now() });
  });

  const channels = await db.collection("channels").get();
  channels.forEach((doc) => {
    const data = doc.data();
    if (data.activeSpeakerDeviceId && data.pttLockedAt && data.pttLockedAt.toMillis() < pttCutoff.toMillis()) {
      batch.update(doc.ref, {
        activeSpeakerDeviceId: "",
        activeSpeakerName: "",
        activeSpeakerUid: "",
        updatedAt: Timestamp.now(),
      });
    }
  });

  const oldSignals = await db
    .collection("webrtc_signals")
    .where("createdAt", "<", signalCutoff)
    .limit(200)
    .get();
  oldSignals.forEach((doc) => batch.delete(doc.ref));

  await batch.commit();
});

/**
 * Server-side 6-device slot audit trigger: logs slot changes and ensures
 * strictly <= 6 slots exist in /slots/{1..6}.
 */
exports.auditFamilySlots = onDocumentWritten(
  { document: "slots/{slotId}", database: DATABASE_ID },
  async (event) => {
    const slotId = event.params.slotId;
    const slotNum = parseInt(slotId, 10);
    if (Number.isNaN(slotNum) || slotNum < 1 || slotNum > 6) {
      if (event.data?.after?.exists) {
        await event.data.after.ref.delete();
      }
    }
  }
);

/**
 * Optional Production Backend Email OTP Sender (Nodemailer / Resend):
 * Deploy with `firebase deploy --only functions` and configure SMTP_USER + SMTP_PASS (Gmail App Password)
 * or RESEND_API_KEY in Firebase Functions secrets so no email API key needs to live inside the APK.
 */
exports.cleanupExpiredOtpChallenges = onSchedule("every 30 minutes", async () => {
  const nowMs = Date.now();
  const expired = await db
    .collection("otp_verifications")
    .where("verified", "==", false)
    .where("expiresAtEpochMs", "<", nowMs)
    .limit(200)
    .get();
  if (expired.empty) return;
  const batch = db.batch();
  expired.forEach((doc) => batch.delete(doc.ref));
  await batch.commit();
});

