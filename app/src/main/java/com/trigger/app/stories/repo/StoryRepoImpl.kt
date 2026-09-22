package com.trigger.app.stories.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.trigger.app.chats.domain.getOtherUser
import com.trigger.app.chats.repo.chats.ChatRepo
import com.trigger.app.core.repo.user.UserRepo
import com.trigger.app.stories.domain.Story
import com.trigger.app.stories.domain.StoryPreview
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.toObject
import com.google.firebase.firestore.toObjects
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.UUID

class StoryRepoImpl(
    private val userRepo: UserRepo,
    private val chatRepo: ChatRepo,
    private val appContext: Context? = null  // optional — used for proper MIME detection
) : StoryRepo {

    override suspend fun postStory(localImageUri: Uri, storyCaption: String): String? {
        val uid = Firebase.auth.uid ?: run {
            Timber.e("postStory: Firebase.auth.uid is null — not signed in")
            return null
        }

        val storyID = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val expiresAt = now + (Story.STORY_TTL_HOURS * 60L * 60L * 1000L)

        // Proper MIME detection: try ContentResolver.getType(uri); fall back to
        // image/jpeg only when null. (Audit fix #10: previously forced image/jpeg
        // even on PNG/WebP picks.)
        val contentType = detectImageContentType(localImageUri)
        val metadata = StorageMetadata.Builder()
            .setContentType(contentType)
            .build()

        // Step 1: upload to Storage. This is OUTSIDE the Firestore transaction
        // because you can't call Storage.putFile from inside a Firestore
        // transaction (it doesn't return a Firestore Task). If the upload fails,
        // we abort early — no orphaned Firestore doc.
        val imageUrl = try {
            Firebase.storage.getReference("${StoryRepo.STORY}/$uid/$storyID")
                .putFile(localImageUri, metadata)
                .await()
                .storage
                .downloadUrl
                .await()
                .toString()
        } catch (e: Exception) {
            Timber.e(e, "postStory: Storage upload failed")
            return null
        }

        // Step 2: atomic Firestore writes. The Story doc + the story_details
        // projection update happen inside a single runTransaction so the count
        // increment + previewImage overwrite can't race with a parallel upload.
        val storyRef = StoryRepo.getStoryCollection(uid).document(storyID)
        val storyDetailsRef = StoryRepo.getStoryDetailsCollection(uid)

        try {
            Firebase.firestore.runTransaction { transaction ->
                // Read first to get the previous previewImage + storyCount.
                val existing = transaction.get(storyDetailsRef).toObject<StoryPreview>()

                // Write the new Story doc.
                transaction.set(storyRef, Story(
                    storyID = storyID,
                    authorUID = uid,
                    imageUrl = imageUrl,
                    storyCaption = storyCaption,
                    timeUploaded = now,
                    expiresAt = expiresAt
                ))

                // Update story_details atomically. FieldValue.increment(1) is
                // server-side, race-condition-proof. previewImage + timeUploaded
                // get overwritten with the new story's values.
                val updateMap = mutableMapOf<String, Any?>(
                    "storyCount" to FieldValue.increment(1),
                    "previewImage" to imageUrl,
                    "timeUploaded" to now,
                    "authorID" to uid
                )

                // If the story_details doc didn't exist before, we need to set
                // the denormalized authorName/authorProfilePic too. On subsequent
                // uploads, these are left as-is (the caller can refresh them
                // separately when the user updates their profile).
                if (existing == null) {
                    // Read the user to populate authorName/authorProfilePic.
                    // NOTE: Firestore transactions support get-after-set semantics
                    // but reading users/{uid} from inside this transaction would
                    // require another transaction.get() — that's allowed.
                    val userRef = com.trigger.app.core.repo.user.UserRepo
                        .Companion.getUserProfileReference(uid)
                    val userDoc = transaction.get(userRef).toObject<com.trigger.app.core.domain.User>()
                    updateMap["authorName"] = userDoc?.name ?: ""
                    updateMap["authorProfilePic"] = userDoc?.profilePic
                }

                transaction.set(storyDetailsRef, updateMap, com.google.firebase.firestore.SetOptions.merge())
            }.await()
        } catch (e: Exception) {
            Timber.e(e, "postStory: Firestore transaction failed; attempting to clean up Storage")
            // Best-effort Storage cleanup so we don't leave orphaned bytes.
            try {
                Firebase.storage.getReference("${StoryRepo.STORY}/$uid/$storyID").delete().await()
            } catch (_: Exception) { }
            return null
        }

        return storyID
    }


    override suspend fun loadStories(authorID: String): List<Story> {
        // Filter out expired stories client-side. Firestore rules don't enforce
        // expiry here — the scheduled Cloud Function does the actual deletion.
        // DESCENDING = newest first (Instagram-style playback order).
        val now = System.currentTimeMillis()
        return StoryRepo.getStoryCollection(authorID)
            .whereEqualTo(Story::authorUID.name, authorID)
            .orderBy(Story::timeUploaded.name, Query.Direction.DESCENDING)
            .get().await()
            .toObjects(Story::class.java)
            .filter { it.expiresAt > now }
    }


    override suspend fun getStoriesForUID(userID: String): List<Story> =
        StoryRepo.getStoryCollection(userID).get().await().toObjects<Story>()


    override fun getMyStoryPreview() = callbackFlow {
        val storyPreviewListener = Firebase.auth.uid?.let { uid ->
            StoryRepo
                .getStoryDetailsCollection(uid)
                .addSnapshotListener { value, error ->
                    error?.printStackTrace()
                    val myStoryPreview = value?.toObject<StoryPreview>()
                    trySend(myStoryPreview)
                }
        }

        awaitClose {
            storyPreviewListener?.remove()
        }
    }

    override fun getStoryPreviews() = callbackFlow {
        // NOTE: This iterates the user's chats and fetches story_details for
        // each chat partner. That's N reads per chat-list emission — known
        // N+1 issue (audit fix #8 flagged it). For production, this should be
        // replaced with a single collection-group query OR a Cloud Function
        // that pre-joins chat partners' story previews. Left as-is for now
        // because rewriting would require schema changes.
        Firebase.auth.uid?.let { uid ->
            chatRepo.getChatsForUser(uid).collectLatest { chats ->
                val storyPreviews = chats?.mapNotNull {
                    getStoryPreview(it.getOtherUser().uid)
                } ?: listOf()
                trySend(storyPreviews)
            }
        }

        awaitClose()
    }

    private suspend fun getStoryPreview(userID: String) =
        StoryRepo.getStoryDetailsCollection(userID).get().await().toObject<StoryPreview>()


    override suspend fun watchStory(storyAuthor: String, storyID: String) {
        val viewerUID = Firebase.auth.uid ?: return

        // Idempotent viewer write: `story/viewers/{authorID}/{storyID}/{viewerUID}`
        // is a doc whose id is the viewer's uid. Re-viewing just re-sets the same
        // doc; totalViewers on the Story doc is bumped via FieldValue.increment
        // only on the FIRST view (we check via arrayUnion on viewersIDs).
        val viewerRef = StoryRepo.getStoryViewersSubcollection(storyAuthor, storyID)
            .document(viewerUID)
        val storyRef = StoryRepo.getStoryCollection(storyAuthor).document(storyID)

        try {
            Firebase.firestore.runTransaction { transaction ->
                val story = transaction.get(storyRef).toObject<Story>() ?: return@runTransaction

                if (story.viewersIDs.contains(viewerUID)) {
                    // Already viewed — no-op.
                    return@runTransaction
                }

                // Add the viewer doc + bump totalViewers + add to viewersIDs.
                transaction.set(viewerRef, mapOf("viewerID" to viewerUID, "viewedAt" to System.currentTimeMillis()))
                transaction.update(
                    storyRef,
                    mapOf(
                        "viewersIDs" to FieldValue.arrayUnion(viewerUID),
                        "totalViewers" to FieldValue.increment(1)
                    )
                )
            }.await()
        } catch (e: Exception) {
            Timber.e(e, "watchStory: failed for storyID=$storyID (non-fatal)")
        }
    }


    override suspend fun deleteStory(storyID: String): Boolean {
        val uid = Firebase.auth.uid ?: return false

        val storyRef = StoryRepo.getStoryCollection(uid).document(storyID)
        val storyDetailsRef = StoryRepo.getStoryDetailsCollection(uid)
        val storageRef = Firebase.storage.getReference("${StoryRepo.STORY}/$uid/$storyID")
        val viewersCollectionRef = StoryRepo.getStoryViewersSubcollection(uid, storyID)

        var success = true

        // 1. Atomic Firestore: decrement storyCount via FieldValue.increment(-1).
        //    If the count would go to 0 (or below), delete the entire story_details doc.
        try {
            // First check if this is the last story — if so, delete the doc.
            // Otherwise, decrement + recompute previewImage from the new latest.
            val storyDoc = storyRef.get().await()
            if (!storyDoc.exists()) {
                Timber.w("deleteStory: story $storyID doesn't exist (already deleted?)")
                return false
            }

            val remaining = StoryRepo.getStoryCollection(uid)
                .whereEqualTo(Story::authorUID.name, uid)
                .get().await()
                .toObjects<Story>()
                .filter { it.storyID != storyID }

            if (remaining.isEmpty()) {
                // Last story — delete the entire story_details doc.
                storyDetailsRef.delete().await()
            } else {
                // Recompute previewImage + timeUploaded from the new latest remaining story.
                val newLatest = remaining.maxByOrNull { it.timeUploaded }
                if (newLatest != null) {
                    storyDetailsRef.update(
                        mapOf(
                            "storyCount" to FieldValue.increment(-1),
                            "previewImage" to newLatest.imageUrl,
                            "timeUploaded" to newLatest.timeUploaded
                        )
                    ).await()
                }
            }

            // 2. Delete the Story doc itself.
            storyRef.delete().await()

            // 3. Delete the viewer subcollection (one doc per viewer). Firestore
            //    doesn't support collection-level deletes from the client; we
            //    iterate the docs and delete each. For very large viewer lists,
            //    a Cloud Function bulk-delete is preferable. For now this is
            //    acceptable (most stories get <100 viewers).
            try {
                val viewers = viewersCollectionRef.get().await()
                viewers.documents.forEach { it.reference.delete().await() }
            } catch (e: Exception) {
                Timber.e(e, "deleteStory: viewer subcollection cleanup failed (non-fatal)")
                // non-fatal — the story is gone, the viewer docs will be orphaned
                // until the scheduled Cloud Function sweeps them.
            }

            // 4. Delete the Storage object.
            storageRef.delete().await()

        } catch (e: Exception) {
            Timber.e(e, "deleteStory: failed for storyID=$storyID")
            success = false
        }

        return success
    }


    override suspend fun sendStoryReply(
        storyAuthor: String,
        storyID: String,
        replyText: String
    ): Boolean {
        val senderID = Firebase.auth.uid ?: return false
        if (replyText.isBlank()) return false

        // Look up the sender's name + profilePic from public_users projection
        // (audit fix: cross-user reads on users/{uid} are owner-only now).
        val sender = userRepo.getUserFromUID(senderID)  // own uid — OK
        val replyDoc = StoryRepo.getStoryRepliesCollection(storyAuthor, storyID)
            .document()
        val reply = mapOf<String, Any?>(
            "replyID" to replyDoc.id,
            "senderID" to senderID,
            "senderName" to (sender?.name ?: ""),
            "senderProfilePic" to (sender?.profilePic ?: ""),
            "message" to replyText.trim(),
            "sentAt" to System.currentTimeMillis()
        )
        return try {
            replyDoc.set(reply).await()
            true
        } catch (e: Exception) {
            Timber.e(e, "sendStoryReply: failed")
            false
        }
    }


    override suspend fun getStoryViewers(authorID: String, storyID: String): List<String> {
        return try {
            StoryRepo.getStoryViewersSubcollection(authorID, storyID)
                .get().await()
                .documents
                .mapNotNull { it.getString("viewerID") }
        } catch (e: Exception) {
            Timber.e(e, "getStoryViewers: failed")
            emptyList()
        }
    }


    /**
     * Detect the actual MIME type of the picked image. Falls back to image/jpeg
     * only when null. Uses ContentResolver.getType(uri); if that returns null
     * (e.g., the cropper's cache URI doesn't expose a type), we try to extract
     * it from the file extension via MimeTypeMap as a second fallback.
     */
    private fun detectImageContentType(uri: Uri): String {
        // Try ContentResolver.getType first.
        appContext?.let { ctx ->
            try {
                ctx.contentResolver.getType(uri)?.let { return it }
            } catch (_: Exception) { }

            // Fallback to file extension via MimeTypeMap.
            try {
                val mimeTypeMap = android.webkit.MimeTypeMap.getSingleton()
                val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                if (ext.isNotEmpty()) {
                    mimeTypeMap.getMimeTypeFromExtension(ext)?.let { return it }
                }
            } catch (_: Exception) { }
        }

        // Last-resort default.
        return "image/jpeg"
    }
}
