/**
 * Trigger App — Cloud Functions v2 (all 9 audit fixes applied)
 * Project: trigger-app-cd138
 *
 * Fixes in this version:
 *   #1: onStoryCreate NO LONGER increments storyCount (was double-incrementing
 *       with the client transaction). Now reconciliation-only — sets
 *       previewImage/timeUploaded + authorName/authorProfilePic on first upload.
 *   #4: deleteUserAccount queries by firstMiniUser.uid (not whole-map equality).
 *   #5: Account deletion is now a callable function (client explicitly calls it),
 *       NOT a Firestore trigger. Accidental Firestore doc deletion no longer
 *       triggers Auth account deletion.
 *   #6: Story expiry sweep runs every 15 minutes (was 60).
 *   #7: FCM tokens read from users/{uid}/deviceTokens (owner-only), not public_users.
 *   #8: lookupUsersByPhone no longer returns phone numbers.
 */

const { onDocumentCreated, onDocumentDeleted } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { onCall } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();
const storage = admin.storage();
const auth = admin.auth();

// ============================================================================
// 1. onStoryCreate — reconciliation-only (FIX #1: no more double-increment)
//    Sets previewImage + timeUploaded + authorName/authorProfilePic.
//    Does NOT touch storyCount — the client's transaction owns that.
// ============================================================================
exports.onStoryCreate = onDocumentCreated(
  "story/content/{authorID}/{storyID}",
  async (event) => {
    const { authorID } = event.params;
    const newStory = event.data?.data();
    if (!newStory) return;

    const storyDetailsRef = db.doc(`story_details/${authorID}`);
    const storyDetailsSnap = await storyDetailsRef.get();

    // FIX #1: DO NOT increment storyCount here. The client's runTransaction
    // already does FieldValue.increment(1) atomically. If we also increment
    // here, the count doubles. Instead, only set the display fields.
    const update = {
      previewImage: newStory.imageUrl,
      timeUploaded: newStory.timeUploaded,
      authorID: authorID,
    };

    // On first upload, populate authorName + authorProfilePic from public_users.
    if (!storyDetailsSnap.exists) {
      const publicUserSnap = await db.doc(`public_users/${authorID}`).get();
      const publicUser = publicUserSnap.data() || {};
      update.authorName = publicUser.name || "";
      update.authorProfilePic = publicUser.profilePic || null;
    }

    await storyDetailsRef.set(update, { merge: true });
  }
);

// ============================================================================
// 2. onStoryDelete — recompute story_details from remaining stories
// ============================================================================
exports.onStoryDelete = onDocumentDeleted(
  "story/content/{authorID}/{storyID}",
  async (event) => {
    const { authorID, storyID } = event.params;
    const storyDetailsRef = db.doc(`story_details/${authorID}`);

    const remainingSnap = await db.collection("story")
      .doc("content")
      .collection(authorID)
      .orderBy("timeUploaded", "desc")
      .get();

    if (remainingSnap.empty) {
      await storyDetailsRef.delete();
    } else {
      const latest = remainingSnap.docs[0].data();
      await storyDetailsRef.set({
        storyCount: remainingSnap.size,
        previewImage: latest.imageUrl,
        timeUploaded: latest.timeUploaded,
      }, { merge: true });
    }

    // Best-effort viewer subcollection cleanup.
    try {
      const viewersSnap = await db.collection(`story/viewers/${authorID}/${storyID}`).get();
      const batch = db.batch();
      viewersSnap.docs.forEach(d => batch.delete(d.ref));
      await batch.commit();
    } catch (e) {
      console.warn("onStoryDelete: viewer cleanup failed (non-fatal)", e);
    }

    // Best-effort Storage cleanup.
    try {
      await storage.bucket().file(`story/${authorID}/${storyID}`).delete();
    } catch (e) { /* already gone */ }
  }
);

// ============================================================================
// 3. scheduledStoryExpiryCleanup — every 15 minutes (FIX #6: was 60)
// ============================================================================
exports.scheduledStoryExpiryCleanup = onSchedule(
  "every 15 minutes",
  async () => {
    const now = Date.now();
    const storyDetailsSnap = await db.collection("story_details").get();

    for (const authorDoc of storyDetailsSnap.docs) {
      const authorID = authorDoc.id;
      const expiredSnap = await db.collection("story")
        .doc("content")
        .collection(authorID)
        .where("expiresAt", "<", now)
        .get();

      for (const storyDoc of expiredSnap.docs) {
        // Deleting triggers onStoryDelete for cascading cleanup.
        await storyDoc.ref.delete();
      }
    }
  }
);

// ============================================================================
// 4. deleteUserAccount — callable function (FIX #5: not a Firestore trigger)
//    Client calls this explicitly. Accidental Firestore doc deletion does NOT
//    trigger Auth account deletion.
//    FIX #4: queries by firstMiniUser.uid (not whole-map equality).
// ============================================================================
exports.deleteUserAccount = onCall(
  async (request) => {
    // Auth check — only the caller can delete their own account.
    if (!request.auth) {
      throw new Error("UNAUTHENTICATED: Must be signed in.");
    }

    const uid = request.data?.uid;
    const username = request.data?.username || "";

    if (!uid || uid !== request.auth.uid) {
      throw new Error("PERMISSION_DENIED: Can only delete your own account.");
    }

    // FIX #4: query by firstMiniUser.uid / secondMiniUser.uid (not whole-map
    // equality). Whole-map equality fails if the user changed their name or
    // profile pic after the chat was created.
    const chatsAsFirst = await db.collection("chat_details")
      .where("firstMiniUser.uid", "==", uid)
      .get();
    const chatsAsSecond = await db.collection("chat_details")
      .where("secondMiniUser.uid", "==", uid)
      .get();

    const allChats = [...chatsAsFirst.docs, ...chatsAsSecond.docs];
    const otherUids = new Set();
    const batch = db.batch();
    for (const chatDoc of allChats) {
      batch.update(chatDoc.ref, { isDisabled: true });
      const data = chatDoc.data();
      const firstUid = data.firstMiniUser?.uid;
      const secondUid = data.secondMiniUser?.uid;
      const otherUid = firstUid === uid ? secondUid : firstUid;
      if (otherUid) otherUids.add(otherUid);
    }
    await batch.commit();

    // Remove OTHER participants' personalized_chats entries.
    for (const otherUid of otherUids) {
      for (const chatDoc of allChats) {
        try {
          await db.doc(`personalized_chats/FILLER/${otherUid}/${chatDoc.id}`).delete();
        } catch (e) { /* non-fatal */ }
      }
    }

    // Remove own personalized_chats subcollection.
    try {
      const ownSnap = await db.collection(`personalized_chats/FILLER/${uid}`).get();
      const b = db.batch();
      ownSnap.docs.forEach(d => b.delete(d.ref));
      await b.commit();
    } catch (e) { /* non-fatal */ }

    // Remove blockedUsers subcollection.
    try {
      const blockedSnap = await db.collection(`users/${uid}/blockedUsers`).get();
      const b = db.batch();
      blockedSnap.docs.forEach(d => b.delete(d.ref));
      await b.commit();
    } catch (e) { /* non-fatal */ }

    // Remove deviceTokens subcollection (FIX #7: FCM tokens are here now).
    try {
      const tokensSnap = await db.collection(`users/${uid}/deviceTokens`).get();
      const b = db.batch();
      tokensSnap.docs.forEach(d => b.delete(d.ref));
      await b.commit();
    } catch (e) { /* non-fatal */ }

    // Remove stories.
    try { await db.doc(`story_details/${uid}`).delete(); } catch (e) { }
    try {
      const storySnap = await db.collection("story").doc("content").collection(uid).get();
      const b = db.batch();
      storySnap.docs.forEach(d => b.delete(d.ref));
      await b.commit();
    } catch (e) { }
    try {
      const [files] = await storage.bucket().getFiles({ prefix: `story/${uid}/` });
      await Promise.all(files.map(f => f.delete()));
    } catch (e) { }

    // Remove Storage profile pic.
    try { await storage.bucket().file(`USERS/${uid}/profilePic`).delete(); } catch (e) { }

    // Remove public_users projection.
    try { await db.doc(`public_users/${uid}`).delete(); } catch (e) { }

    // Free username reservation.
    if (username) {
      try { await db.doc(`usernames/${username}`).delete(); } catch (e) { }
    }

    // Remove users/{uid} doc.
    try { await db.doc(`users/${uid}`).delete(); } catch (e) { }

    // Delete Firebase Auth user (server-side, no recent-login required).
    try {
      await auth.deleteUser(uid);
    } catch (e) {
      console.warn(`deleteUserAccount: Auth deletion failed for ${uid}`, e);
    }

    return { success: true };
  }
);

// ============================================================================
// 5. onNewChatMessage — FCM push (FIX #7: reads token from users/{uid}/deviceTokens)
// ============================================================================
exports.onNewChatMessage = onDocumentCreated(
  "chats/{chatID}/messages/{messageID}",
  async (event) => {
    const { chatID } = event.params;
    const message = event.data?.data();
    if (!message) return;

    const chatDoc = await db.doc(`chat_details/${chatID}`).get();
    if (!chatDoc.exists) return;

    const chat = chatDoc.data();
    const senderID = message.senderID;
    const firstUID = chat.firstMiniUser?.uid;
    const secondUID = chat.secondMiniUser?.uid;
    const recipientUID = senderID === firstUID ? secondUID : firstUID;
    if (!recipientUID) return;

    // FIX #7: Read FCM token(s) from users/{uid}/deviceTokens (owner-only),
    // NOT from public_users/{uid}.fcmToken (public-readable).
    const tokensSnap = await db.collection(`users/${recipientUID}/deviceTokens`).get();
    const tokens = tokensSnap.docs.map(d => d.id);
    if (tokens.length === 0) return;

    const senderDoc = await db.doc(`public_users/${senderID}`).get();
    const senderName = senderDoc.data()?.name || "New message";

    // Build message preview.
    let preview = "New message";
    if (message.messageType) {
      const type = message.messageType.type;
      const msg = message.messageType.message || "";
      if (type === "Text") {
        preview = msg.length > 100 ? msg.substring(0, 97) + "..." : msg;
      } else if (type === "Image") { preview = "📷 Photo"; }
      else if (type === "Audio") { preview = "🎙️ Voice message"; }
      else if (type === "Video") { preview = "🎥 Video"; }
    }

    // Send to ALL the recipient's devices.
    for (const token of tokens) {
      const payload = {
        token: token,
        notification: { title: senderName, body: preview },
        data: {
          type: "message",
          chatID: chatID,
          senderID: senderID,
          senderName: senderName,
          messagePreview: preview,
        },
        android: {
          notification: { channelId: "CHAT_MESSAGES_CHANNEL_ID", priority: "high" },
        },
      };
      try { await admin.messaging().send(payload); }
      catch (e) { console.warn(`FCM send failed for token ${token.substring(0, 10)}...`, e); }
    }
  }
);

// ============================================================================
// 6. lookupUsersByPhone — contact discovery (FIX #8: no phone numbers in response)
// ============================================================================
exports.lookupUsersByPhone = onCall(
  async (request) => {
    const phoneNumbers = request.data?.phoneNumbers;
    if (!Array.isArray(phoneNumbers) || phoneNumbers.length === 0) {
      return { users: [] };
    }
    if (!request.auth) {
      throw new Error("UNAUTHENTICATED: Must be signed in.");
    }

    const matchedUsers = [];
    for (let i = 0; i < phoneNumbers.length; i += 30) {
      const batch = phoneNumbers.slice(i, i + 30);
      const snap = await db.collection("users")
        .where("number", "in", batch)
        .get();

      for (const doc of snap.docs) {
        const data = doc.data();
        matchedUsers.push({
          uid: doc.id,
          name: data.name || "",
          username: data.username || "",
          // FIX #8: DO NOT return phone number — defeats the privacy separation.
          // The caller already knows the phone numbers (they came from their contacts).
          // Returning the matched user's number would leak it if the caller
          // passed a random number that happened to match.
        });
      }
    }

    // Look up profilePic from public_users for each match.
    for (const user of matchedUsers) {
      try {
        const publicDoc = await db.doc(`public_users/${user.uid}`).get();
        user.profilePic = publicDoc.data()?.profilePic || null;
      } catch (e) {
        user.profilePic = null;
      }
    }

    return { users: matchedUsers };
  }
);


// ============================================================================
// 7. onCallInitiate — FCM push to callee when a new call doc is created
//    Listens to: calls/{callID} ( onCreate ).
//    Sends a high-priority FCM with type="call" + callID + callerID + callType
//    to ALL the callee's device tokens (users/{calleeID}/deviceTokens).
//    The callee's TriggerMessagingService sees type="call" and launches
//    IncomingCallScreen as a full-screen intent.
// ============================================================================
exports.onCallInitiate = onDocumentCreated(
  "calls/{callID}",
  async (event) => {
    const newCall = event.data?.data();
    if (!newCall) return;

    const calleeID = newCall.calleeID;
    const callerID = newCall.callerID;
    const callType = newCall.callType || "AUDIO";
    const callID = event.params.callID;

    if (!calleeID || !callerID) {
      console.warn("onCallInitiate: missing calleeID or callerID");
      return;
    }

    // Read caller's name + profilePic from public_users projection.
    let callerName = "Unknown";
    let callerProfilePic = null;
    try {
      const callerDoc = await db.doc(`public_users/${callerID}`).get();
      callerName = callerDoc.data()?.name || "Unknown";
      callerProfilePic = callerDoc.data()?.profilePic || null;
    } catch (e) {
      console.warn(`onCallInitiate: failed to read caller public_users/${callerID}`, e);
    }

    // Read ALL the callee's device tokens (multi-device support).
    const tokensSnap = await db.collection(`users/${calleeID}/deviceTokens`).get();
    const tokens = tokensSnap.docs.map(d => d.id);
    if (tokens.length === 0) {
      console.log(`onCallInitiate: callee ${calleeID} has no device tokens — no FCM sent`);
      return;
    }

    // Send the high-priority call FCM to every device.
    for (const token of tokens) {
      const payload = {
        token: token,
        // NO `notification` block — we use `data` only + a high-priority
        // Android channel so the client's onMessageReceived always fires
        // (not just when the app is foregrounded). The client then shows
        // the IncomingCallScreen as a full-screen intent.
        data: {
          type: "call",
          callID: callID,
          callerID: callerID,
          callerName: callerName,
          callerProfilePic: callerProfilePic || "",
          callType: callType
        },
        android: {
          priority: "high",
          // Use the CALLS channel — must be created on the client with
          // setBypassDnd(true) and fullScreenIntent for incoming-call UX.
          notification: {
            channelId: "CALLS_CHANNEL_ID",
            priority: "max",
            visibility: "public",
            title: `Incoming ${callType.toLowerCase()} call`,
            body: `${callerName} is calling`
          }
        }
      };
      try { await admin.messaging().send(payload); }
      catch (e) { console.warn(`onCallInitiate: FCM send failed for token ${token.substring(0, 10)}...`, e); }
    }
  }
);
