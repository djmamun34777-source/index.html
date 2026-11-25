const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const DATABASE_ID = "ai-studio-android-mamunwal-b15e2de4-963d-42fb-8781-ee5c589888ba";

const ADMIN_UID = "admin_mamun_uid";
const ADMIN_EMAIL = "djmamun34777@gmail.com";
const FAMILY_A_UID = "family_member_a";
const FAMILY_B_UID = "family_member_b";
const OUTSIDER_UID = "outsider_stranger";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(msAgo = 2000) {
  return new Date(Date.now() - msAgo);
}

function getDb(context) {
  return context.firestore(DATABASE_ID);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated users are denied across all collections", async () => {
  const unauthDb = getDb(testEnv.unauthenticatedContext());
  await assertFails(unauthDb.collection("devices").get());
  await assertFails(unauthDb.collection("channels").get());
  await assertFails(unauthDb.collection("slots").get());
  await assertFails(unauthDb.collection("private_sessions").get());
  await assertFails(unauthDb.collection("webrtc_signals").get());
  await assertFails(unauthDb.collection("settings").doc("ice_config").get());
});

test("Email spoofing with email_verified=false cannot gain admin privileges", async () => {
  const spoofedAdminDb = getDb(
    testEnv.authenticatedContext("spoof_uid", {
      email: ADMIN_EMAIL,
      email_verified: false,
    })
  );
  const ts = pastDate();
  await assertFails(
    spoofedAdminDb.collection("slots").doc("1").set({
      deviceId: "MWT-SPOOF-1",
      ownerUid: "spoof_uid",
      deviceName: "Spoof Phone",
      memberUids: ["spoof_uid"],
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

test("6-device hard ceiling: Admin can create slots 1..6, but slot 7 is rejected", async () => {
  const adminDb = getDb(
    testEnv.authenticatedContext(ADMIN_UID, {
      email: ADMIN_EMAIL,
      email_verified: true,
    })
  );
  const ts = pastDate();

  for (let slot = 1; slot <= 6; slot++) {
    await assertSucceeds(
      adminDb.collection("slots").doc(String(slot)).set({
        deviceId: `MWT-DEV0-${slot}`,
        ownerUid: `uid_${slot}`,
        deviceName: `Family Phone ${slot}`,
        memberUids: [ADMIN_UID, `uid_${slot}`],
        createdAt: ts,
        updatedAt: ts,
      })
    );
  }

  // 7th device slot MUST fail at rules layer
  await assertFails(
    adminDb.collection("slots").doc("7").set({
      deviceId: "MWT-DEV0-7",
      ownerUid: "uid_7",
      deviceName: "7th Phone Blocked",
      memberUids: [ADMIN_UID, "uid_7"],
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

test("Non-admin family device registration enforces PENDING/slot 0 and blocks self-approval or shadow fields", async () => {
  const familyDb = getDb(testEnv.authenticatedContext(FAMILY_A_UID));
  const ts = pastDate();

  // Valid Kotlin self-registration payload (matches WalkieRepository.registerOrSyncDevice)
  await assertSucceeds(
    familyDb.collection("devices").doc("MWT-FAMA-1001").set({
      ownerUid: FAMILY_A_UID,
      deviceName: "Mom Phone",
      slotNumber: 0,
      status: "PENDING",
      online: true,
      currentChannel: 1,
      mode: "GROUP",
      privateTargetDeviceId: "",
      batteryLevel: 88,
      networkType: "WIFI",
      adminDevice: false,
      memberUids: [FAMILY_A_UID],
      lastSeenAt: ts,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Privilege escalation attempt: non-admin trying to self-approve or claim adminDevice=true
  await assertFails(
    familyDb.collection("devices").doc("MWT-FAMA-1002").set({
      ownerUid: FAMILY_A_UID,
      deviceName: "Hacked Phone",
      slotNumber: 2,
      status: "APPROVED",
      online: true,
      currentChannel: 1,
      mode: "GROUP",
      privateTargetDeviceId: "",
      batteryLevel: 90,
      networkType: "WIFI",
      adminDevice: true,
      memberUids: [FAMILY_A_UID],
      lastSeenAt: ts,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Shadow update / extra @DocumentId 'id' key rejected
  await assertFails(
    familyDb.collection("devices").doc("MWT-FAMA-1003").set({
      id: "MWT-FAMA-1003",
      ownerUid: FAMILY_A_UID,
      deviceName: "Ghost Field",
      slotNumber: 0,
      status: "PENDING",
      online: true,
      currentChannel: 1,
      mode: "GROUP",
      privateTargetDeviceId: "",
      batteryLevel: 90,
      networkType: "WIFI",
      adminDevice: false,
      memberUids: [FAMILY_A_UID],
      lastSeenAt: ts,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Pending device owner cannot escalate own status via update
  await assertFails(
    familyDb.collection("devices").doc("MWT-FAMA-1001").update({
      status: "APPROVED",
      slotNumber: 2,
      lastSeenAt: pastDate(500),
      updatedAt: pastDate(500),
    })
  );
});

test("Approved member queries, channel PTT floor locking, and Private Mode isolation", async () => {
  const ts = pastDate(5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = getDb(ctx);
    await db.collection("devices").doc("MWT-DEV-A").set({
      ownerUid: FAMILY_A_UID,
      deviceName: "Phone A",
      slotNumber: 1,
      status: "APPROVED",
      online: true,
      currentChannel: 2,
      mode: "GROUP",
      privateTargetDeviceId: "",
      batteryLevel: 85,
      networkType: "WIFI",
      adminDevice: false,
      memberUids: [ADMIN_UID, FAMILY_A_UID, FAMILY_B_UID],
      lastSeenAt: ts,
      createdAt: ts,
      updatedAt: ts,
    });

    await db.collection("channels").doc("channel_2").set({
      channelNumber: 2,
      name: "CH 2 • Home Base",
      activeSpeakerDeviceId: "",
      activeSpeakerName: "",
      activeSpeakerUid: "",
      memberUids: [ADMIN_UID, FAMILY_A_UID, FAMILY_B_UID],
      createdAt: ts,
      updatedAt: ts,
    });

    await db.collection("private_sessions").doc("priv_MWT-DEV-A_MWT-DEV-B").set({
      deviceAId: "MWT-DEV-A",
      deviceBId: "MWT-DEV-B",
      participantUids: [FAMILY_A_UID, FAMILY_B_UID],
      activeSpeakerDeviceId: "",
      activeSpeakerName: "",
      activeSpeakerUid: "",
      active: true,
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const famADb = getDb(testEnv.authenticatedContext(FAMILY_A_UID));
  const outsiderDb = getDb(testEnv.authenticatedContext(OUTSIDER_UID));

  // Matches WalkieRepository.observeDevices(isAdmin = false)
  await assertSucceeds(
    famADb.collection("devices").where("memberUids", "array-contains", FAMILY_A_UID).get()
  );
  // Unfiltered list query by non-admin MUST FAIL
  await assertFails(famADb.collection("devices").get());

  // Approved device owner can switch channel & update battery
  await assertSucceeds(
    famADb.collection("devices").doc("MWT-DEV-A").update({
      currentChannel: 3,
      mode: "GROUP",
      privateTargetDeviceId: "",
      lastSeenAt: pastDate(500),
      updatedAt: pastDate(500),
    })
  );

  // Approved member can acquire PTT floor on channel_2
  await assertSucceeds(
    famADb.collection("channels").doc("channel_2").update({
      activeSpeakerDeviceId: "MWT-DEV-A",
      activeSpeakerName: "Phone A",
      activeSpeakerUid: FAMILY_A_UID,
      pttLockedAt: pastDate(400),
      updatedAt: pastDate(400),
    })
  );

  // Outsider cannot read or write private session or channel
  await assertFails(outsiderDb.collection("channels").doc("channel_2").get());
  await assertFails(
    outsiderDb.collection("private_sessions").doc("priv_MWT-DEV-A_MWT-DEV-B").get()
  );

  // Participant A can acquire floor on private session
  await assertSucceeds(
    famADb.collection("private_sessions").doc("priv_MWT-DEV-A_MWT-DEV-B").update({
      activeSpeakerDeviceId: "MWT-DEV-A",
      activeSpeakerName: "Phone A",
      activeSpeakerUid: FAMILY_A_UID,
      pttLockedAt: pastDate(300),
      updatedAt: pastDate(300),
    })
  );

  // WebRTC signaling: A sends OFFER to B; B queries with where("toUid", "==", FAMILY_B_UID) and consumes; outsider denied
  await assertSucceeds(
    famADb.collection("webrtc_signals").doc("sig_offer_1").set({
      roomId: "channel_2",
      fromDeviceId: "MWT-DEV-A",
      toDeviceId: "MWT-DEV-B",
      fromUid: FAMILY_A_UID,
      toUid: FAMILY_B_UID,
      signalType: "OFFER",
      sdp: "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\n",
      iceCandidate: "",
      iceSdpMid: "",
      iceSdpMLineIndex: -1,
      createdAt: pastDate(200),
    })
  );

  const famBDb = getDb(testEnv.authenticatedContext(FAMILY_B_UID));
  await assertSucceeds(
    famBDb.collection("webrtc_signals").where("toUid", "==", FAMILY_B_UID).get()
  );
  await assertFails(outsiderDb.collection("webrtc_signals").doc("sig_offer_1").get());
  // Signals are immutable (no update), delete-on-consume allowed for recipient
  await assertFails(
    famBDb.collection("webrtc_signals").doc("sig_offer_1").update({ sdp: "tampered" })
  );
  await assertSucceeds(famBDb.collection("webrtc_signals").doc("sig_offer_1").delete());

  // Tampered future timestamp rejected
  await assertFails(
    famADb.collection("webrtc_signals").doc("sig_future").set({
      roomId: "channel_2",
      fromDeviceId: "MWT-DEV-A",
      toDeviceId: "MWT-DEV-B",
      fromUid: FAMILY_A_UID,
      toUid: FAMILY_B_UID,
      signalType: "ICE",
      sdp: "",
      iceCandidate: "candidate:1",
      iceSdpMid: "0",
      iceSdpMLineIndex: 0,
      createdAt: new Date(Date.now() + 60000),
    })
  );
});

test("Google Email OTP verifications are strictly self-scoped, non-enumerable, and email-bound", async () => {
  const unauthDb = getDb(testEnv.unauthenticatedContext());
  const famADb = getDb(
    testEnv.authenticatedContext(FAMILY_A_UID, {
      email: "memberA@gmail.com",
      email_verified: true,
    })
  );
  const famBDb = getDb(
    testEnv.authenticatedContext(FAMILY_B_UID, {
      email: "memberB@gmail.com",
      email_verified: true,
    })
  );

  const validHash = "a".repeat(64);
  const ts = pastDate(1000);
  const expiresAt = Date.now() + 10 * 60 * 1000;

  // Unauthenticated denied
  await assertFails(unauthDb.collection("otp_verifications").doc(FAMILY_A_UID).get());
  // Collection listing denied even for authenticated users
  await assertFails(famADb.collection("otp_verifications").get());

  // User A creates own OTP challenge matching token email
  await assertSucceeds(
    famADb.collection("otp_verifications").doc(FAMILY_A_UID).set({
      email: "memberA@gmail.com",
      deviceId: "MWT-FAMA-1001",
      otpHash: validHash,
      verified: false,
      attempts: 0,
      expiresAtEpochMs: expiresAt,
      deliveryStatus: "SENT",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // User A cannot spoof another email in OTP challenge
  await assertFails(
    famADb.collection("otp_verifications").doc(FAMILY_A_UID).set({
      email: "someoneelse@gmail.com",
      deviceId: "MWT-FAMA-1001",
      otpHash: validHash,
      verified: false,
      attempts: 0,
      expiresAtEpochMs: expiresAt,
      deliveryStatus: "SENT",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // User B cannot read or tamper with User A's OTP doc
  await assertFails(famBDb.collection("otp_verifications").doc(FAMILY_A_UID).get());
  await assertFails(
    famBDb.collection("otp_verifications").doc(FAMILY_A_UID).update({
      verified: true,
      updatedAt: pastDate(200),
    })
  );

  // User A can update attempts and mark verified after matching OTP
  await assertSucceeds(
    famADb.collection("otp_verifications").doc(FAMILY_A_UID).update({
      verified: true,
      attempts: 1,
      updatedAt: pastDate(200),
    })
  );
});
