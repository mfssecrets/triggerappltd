/**
 * Trigger App — Cloud Functions
 * Project: trigger-app-cd138
 *
 * Responsibilities:
 *   1. onStoryCreate — when a new story doc is written to
 *      `story/content/{authorID}/{storyID}`, ensure `story_details/{authorID}`
 *      exists and increment `storyCount`. (Defensive mirror of the client-side
 *      FieldValue.increment(1) in StoryRepoImpl.postStory — if the client-side
 *      transaction somehow fails mid-flight, this trigger catches it up.)
 *
 *   2. onStoryDelete — when a story doc is deleted, decrement
 *      `story_details/{authorID}.storyCount` and recompute `previewImage` +
 *      `timeUploaded` from the latest remaining story. If `storyCount` hits 0,
 *      delete the `story_details/{authorID}` doc entirely.
 *
 *   3. scheduledStoryExpiryCleanup — runs every 1 hour, scans for stories
 *      whose `expiresAt < Date.now()`, deletes them (Firestore + Storage),
 *      and recomputes the `story_details` projection per affected author.
 *
 *   4. onDeleteUser — when `users/{uid}` is deleted, clean up everything
 *      that points at that user (chat_details, personalized_chats entries on
 *      the OTHER participant's side, blockedUsers, story_details + stories +
 *      Storage, public_users, usernames reservation, and finally the Firebase
 *      Auth user record — which the client CAN'T reliably delete because
 *      Firebase.auth.currentUser.delete() throws FirebaseAuthRecentLoginRequiredException
 *      if the user signed in more than 5 minutes ago).
 */

const { onDocumentCreated, onDocumentDeleted } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();
const storage = admin.storage();
const auth = admin.auth();

const STORY_TTL_MS = 24 * 60 * 60 * 1000;  // 24 hours

// ============================================================================
// 1. onStoryCreate — defensive story_details maintenance
// ============================================================================
exports.onStoryCreate = onDocumentCreated(
  "story/content/{authorID}/{storyID}",
  async (event) => {
    const { authorID, storyID } = event.params;
    const newStory = event.data?.data();

    if (!newStory) return;

    const storyDetailsRef = db.doc(`story_details/${authorID}`);
    const storyDetailsSnap = await storyDetailsRef.get();

    const update = {
      storyCount: admin.firestore.FieldValue.increment(1),
      previewImage: newStory.imageUrl,
      timeUploaded: newStory.timeUploaded,
      authorID: authorID,
    };

    // If story_details doesn't exist yet, also set authorName + authorProfilePic
    // (these come from the public_users projection since the function runs
    // with admin privileges and isn't subject to the owner-only-read rule).
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
// 2. onStoryDelete — decrement count + recompute preview
// ============================================================================
exports.onStoryDelete = onDocumentDeleted(
  "story/content/{authorID}/{storyID}",
  async (event) => {
    const { authorID, storyID } = event.params;
    const storyDetailsRef = db.doc(`story_details/${authorID}`);

    // Find all remaining stories by this author, ordered by timeUploaded DESC.
    const remainingSnap = await db.collection("story")
      .doc("content")
      .collection(authorID)
      .orderBy("timeUploaded", "desc")
      .get();

    if (remainingSnap.empty) {
      // No stories left — delete the story_details doc.
      await storyDetailsRef.delete();
    } else {
      const latest = remainingSnap.docs[0].data();
      await storyDetailsRef.set({
        storyCount: remainingSnap.size,
        previewImage: latest.imageUrl,
        timeUploaded: latest.timeUploaded,
      }, { merge: true });
    }

    // Best-effort cleanup of the viewer subcollection.
    try {
      const viewersSnap = await db.collection(`story/viewers/${authorID}/${storyID}`)
        .get();
      const batch = db.batch();
      viewersSnap.docs.forEach(d => batch.delete(d.ref));
      await batch.commit();
    } catch (e) {
      console.warn("onStoryDelete: viewer subcollection cleanup failed (non-fatal)", e);
    }

    // Best-effort cleanup of the Storage object.
    try {
      await storage.bucket().file(`story/${authorID}/${storyID}`).delete();
    } catch (e) { /* already gone — non-fatal */ }
  }
);

// ============================================================================
// 3. scheduledStoryExpiryCleanup — hourly sweep of expired stories
// ============================================================================
exports.scheduledStoryExpiryCleanup = onSchedule(
  "every 60 minutes",
  async () => {
    const now = Date.now();
    // Scan all authors. For each author with a story_details doc, query their
    // stories where expiresAt < now.
    const storyDetailsSnap = await db.collection("story_details").get();

    for (const authorDoc of storyDetailsSnap.docs) {
      const authorID = authorDoc.id;
      const expiredSnap = await db.collection("story")
        .doc("content")
        .collection(authorID)
        .where("expiresAt", "<", now)
        .get();

      for (const storyDoc of expiredSnap.docs) {
        // Deleting the doc triggers onStoryDelete above, which handles the
        // story_details recompute + viewer subcollection cleanup + Storage.
        await storyDoc.ref.delete();
      }
    }
  }
);

// ============================================================================
// 4. onDeleteUser — full account-deletion cleanup
// ============================================================================
exports.onDeleteUser = onDocumentDeleted(
  "users/{uid}",
  async (event) => {
    const { uid } = event.params;
    const userDoc = event.data?.data();

    // 1. Disable all chat_details where the user is a participant.
    if (userDoc) {
      const ownMiniUser = {
        uid: uid,
        name: userDoc.name || "",
        profilePic: userDoc.profilePic || null,
      };
      const chatsAsFirst = await db.collection("chat_details")
        .where("firstMiniUser", "==", ownMiniUser)
        .get();
      const chatsAsSecond = await db.collection("chat_details")
        .where("secondMiniUser", "==", ownMiniUser)
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

      // 2. Remove the OTHER participant's personalized_chats entries for
      //    each chat this user was in.
      for (const otherUid of otherUids) {
        for (const chatDoc of allChats) {
          const chatID = chatDoc.id;
          try {
            await db.doc(`personalized_chats/FILLER/${otherUid}/${chatID}`).delete();
          } catch (e) { /* non-fatal */ }
        }
      }
    }

    // 3. Remove the user's own personalized_chats subcollection.
    try {
      const ownPersonalizedSnap = await db.collection(`personalized_chats/FILLER/${uid}`).get();
      const batch = db.batch();
      ownPersonalizedSnap.docs.forEach(d => batch.delete(d.ref));
      await batch.commit();
    } catch (e) { /* non-fatal */ }

    // 4. Remove blockedUsers subcollection.
    try {
      const blockedSnap = await db.collection(`users/${uid}/blockedUsers`).get();
      const batch = db.batch();
      blockedSnap.docs.forEach(d => batch.delete(d.ref));
      await batch.commit();
    } catch (e) { /* non-fatal */ }

    // 5. Remove story_details + all stories + Storage story objects.
    try {
      await db.doc(`story_details/${uid}`).delete();
    } catch (e) { /* non-fatal */ }

    try {
      const storySnap = await db.collection("story").doc("content").collection(uid).get();
      const batch = db.batch();
      storySnap.docs.forEach(d => batch.delete(d.ref));
      await batch.commit();
    } catch (e) { /* non-fatal */ }

    try {
      const [files] = await storage.bucket().getFiles({ prefix: `story/${uid}/` });
      await Promise.all(files.map(f => f.delete()));
    } catch (e) { /* non-fatal */ }

    // 6. Remove the Storage USERS/{uid}/profilePic object.
    try {
      await storage.bucket().file(`USERS/${uid}/profilePic`).delete();
    } catch (e) { /* non-fatal */ }

    // 7. Remove public_users/{uid} projection.
    let username = "";
    try {
      const publicDoc = await db.doc(`public_users/${uid}`).get();
      const publicData = publicDoc.data() || {};
      username = publicData.username || "";
      await db.doc(`public_users/${uid}`).delete();
    } catch (e) { /* non-fatal */ }

    // 8. Free the usernames/{username} reservation.
    if (username) {
      try {
        await db.doc(`usernames/${username}`).delete();
      } catch (e) { /* non-fatal */ }
    }

    // 9. Finally, delete the Firebase Auth user record. This is the step
    //    the client can't reliably do — Firebase.auth.currentUser.delete()
    //    throws FirebaseAuthRecentLoginRequiredException if the user signed
    //    in more than 5 minutes ago.
    try {
      await auth.deleteUser(uid);
    } catch (e) {
      console.warn(`onDeleteUser: failed to delete Auth user ${uid}`, e);
    }
  }
);

// ============================================================================
// 5. onNewChatMessage — FCM push notification for new messages
//    Triggers on chats/{chatID}/messages/{messageID}.onCreate. Looks up the
//    OTHER participant's FCM token from public_users/{uid}.fcmToken and sends
//    a push via Firebase Cloud Messaging.
// ============================================================================
exports.onNewChatMessage = onDocumentCreated(
  "chats/{chatID}/messages/{messageID}",
  async (event) => {
    const { chatID } = event.params;
    const message = event.data?.data();
    if (!message) return;

    // Look up the chat_details to find both participants.
    const chatDoc = await db.doc(`chat_details/${chatID}`).get();
    if (!chatDoc.exists) return;

    const chat = chatDoc.data();
    const senderID = message.senderID;
    const firstUID = chat.firstMiniUser?.uid;
    const secondUID = chat.secondMiniUser?.uid;
    const recipientUID = senderID === firstUID ? secondUID : firstUID;

    if (!recipientUID) return;

    // Look up the recipient's FCM token + the sender's name + profilePic.
    const recipientDoc = await db.doc(`public_users/${recipientUID}`).get();
    const fcmToken = recipientDoc.data()?.fcmToken;
    if (!fcmToken) return;

    const senderDoc = await db.doc(`public_users/${senderID}`).get();
    const senderName = senderDoc.data()?.name || "New message";
    const senderProfilePic = senderDoc.data()?.profilePic || null;

    // Build the message preview (truncate long messages).
    let preview = "";
    if (message.messageType) {
      const type = message.messageType.type;
      const msg = message.messageType.message || "";
      if (type === "Text") {
        preview = msg.length > 100 ? msg.substring(0, 97) + "..." : msg;
      } else if (type === "Image") {
        preview = "📷 Photo";
      } else if (type === "Audio") {
        preview = "🎙️ Voice message";
      } else if (type === "Video") {
        preview = "🎥 Video";
      } else {
        preview = "New message";
      }
    }

    // Send the FCM push.
    const payload = {
      token: fcmToken,
      notification: {
        title: senderName,
        body: preview,
      },
      data: {
        type: "message",
        chatID: chatID,
        senderID: senderID,
        senderName: senderName,
        messagePreview: preview,
        senderProfilePic: senderProfilePic || "",
      },
      android: {
        notification: {
          channelId: "CHAT_MESSAGES_CHANNEL_ID",
          priority: "high",
        },
      },
    };

    try {
      await admin.messaging().send(payload);
      console.log(`onNewChatMessage: FCM push sent to ${recipientUID} for chat ${chatID}`);
    } catch (e) {
      console.warn(`onNewChatMessage: FCM send failed for ${recipientUID}`, e);
    }
  }
);

// ============================================================================
// 6. lookupUsersByPhone — HTTPS callable for contact-based user discovery
//    The client can't query users/{uid}.number (owner-only-read rule). This
//    function runs with admin privileges and returns a list of {uid, name,
//    username, profilePic} for each phone number that matches a user in the DB.
// ============================================================================
const { onCall } = require("firebase-functions/v2/https");

exports.lookupUsersByPhone = onCall(
  async (request) => {
    const phoneNumbers = request.data?.phoneNumbers;
    if (!Array.isArray(phoneNumbers) || phoneNumbers.length === 0) {
      return { users: [] };
    }

    // Auth check — only signed-in users can call this.
    if (!request.auth) {
      throw new Error("UNAUTHENTICATED: Must be signed in to call lookupUsersByPhone.");
    }

    // Query users by phone number in batches of 30 (Firestore whereIn limit).
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
          number: data.number || "",
        });
      }
    }

    // Also look up profilePic from public_users for each match.
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
