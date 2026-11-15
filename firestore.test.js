const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const SESSION_ID = "session_cbtc_01";
const UTTERANCE_ID = "utt_01";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

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

function validSessionPayload(userId) {
  const now = new Date();
  return {
    userId,
    title: "板南線 CBTC 號誌聯調測試",
    metroLine: "板南線 (BL)",
    testCategory: "CBTC 號誌系統",
    foreignLangCode: "en-US",
    foreignLangLabel: "English (英文)",
    utteranceCount: 0,
    minutesSummary: "初步測試完成，列車自動保護系統訊號穩定。",
    minutesKeyPoints: ["ATP 煞車曲線符合規範", "應答器讀取率 100%"],
    minutesActionItems: ["西門子技師於明日提供軟體版次報告"],
    minutesTestVerdict: "進行中 (In Progress)",
    createdAt: now,
    updatedAt: now,
  };
}

function validUtterancePayload(userId, sessionId) {
  const now = new Date();
  return {
    userId,
    sessionId,
    speakerRole: "FOREIGN_ENGINEER",
    speakerLabel: "外籍技師 (Foreign Engineer)",
    sourceLang: "en-US",
    targetLang: "zh-TW",
    originalText: "Please verify the CBTC beacon handover latency at platform 2.",
    translatedText: "請確認第二月台 CBTC 信標交遞的延遲時間。",
    technicalTerms: ["CBTC: 通訊式列車控制", "Beacon handover: 信標交遞"],
    isPinnedToMinutes: true,
    translationAttemptId: "attempt_init_001",
    createdAt: now,
    updatedAt: now,
  };
}

test("Unauthenticated user: cannot read or create sessions", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("sessions").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("sessions")
      .doc(SESSION_ID)
      .set(validSessionPayload(ALICE_UID))
  );
});

test("Authenticated owner: can create, read, list, and update own session", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("sessions")
    .doc(SESSION_ID);

  await assertSucceeds(docRef.set(validSessionPayload(ALICE_UID)));
  await assertSucceeds(docRef.get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("sessions").get()
  );
  await assertSucceeds(
    docRef.update({
      utteranceCount: 1,
      minutesTestVerdict: "通過 (PASS)",
      updatedAt: new Date(),
    })
  );
});

test("Cross-user isolation: Bob cannot read or modify Alice's session", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx
      .firestore()
      .collection("users")
      .doc(ALICE_UID)
      .collection("sessions")
      .doc(SESSION_ID)
      .set(validSessionPayload(ALICE_UID));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("sessions")
      .doc(SESSION_ID)
      .get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("sessions").get()
  );
});

test("Shadow update test: rejects ghost fields on session update", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("sessions")
    .doc(SESSION_ID);
  await assertSucceeds(docRef.set(validSessionPayload(ALICE_UID)));

  await assertFails(
    docRef.update({
      isVerifiedAdmin: true,
      updatedAt: new Date(),
    })
  );
});

test("Utterance subcollection: owner can create, list, and pin utterance when session exists; blocked after session deleted or cross-user", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const sessionRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("sessions")
    .doc(SESSION_ID);
  const uttRef = sessionRef.collection("utterances").doc(UTTERANCE_ID);

  // Cannot create utterance before parent session exists
  await assertFails(uttRef.set(validUtterancePayload(ALICE_UID, SESSION_ID)));

  // Create parent session first
  await assertSucceeds(sessionRef.set(validSessionPayload(ALICE_UID)));

  await assertSucceeds(uttRef.set(validUtterancePayload(ALICE_UID, SESSION_ID)));
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("sessions")
      .doc(SESSION_ID)
      .collection("utterances")
      .get()
  );
  await assertSucceeds(
    uttRef.update({
      isPinnedToMinutes: false,
      updatedAt: new Date(),
    })
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("sessions")
      .doc(SESSION_ID)
      .collection("utterances")
      .get()
  );

  // Deleting parent session blocks new utterances from being created under it
  await assertSucceeds(sessionRef.delete());
  await assertFails(
    sessionRef
      .collection("utterances")
      .doc("utt_after_delete")
      .set(validUtterancePayload(ALICE_UID, SESSION_ID))
  );
});
