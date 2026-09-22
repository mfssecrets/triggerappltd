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
