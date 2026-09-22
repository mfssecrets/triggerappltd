package com.trigger.app.core.repo.user

import android.net.Uri
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.toObject
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.ktx.storage
import com.trigger.app.R
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.domain.User
import com.trigger.app.core.repo.user.UserRepo.Companion.getPublicUserProfileReference
import com.trigger.app.core.repo.user.UserRepo.Companion.getStorageRefForProfilePic
import com.trigger.app.core.repo.user.UserRepo.Companion.getUserProfileReference
import com.trigger.app.core.repo.user.UserRepo.Companion.getUsernameReference
import kotlinx.coroutines.tasks.await
import timber.log.Timber

class UserRepoImpl : UserRepo {

    private val firestore = Firebase.firestore

    override suspend fun createUser(
        name: String,
        bio: String,
        username: String,
        phoneNumber: String,
        profilePicLocalUri: Uri?,
        onComplete: (TaskState) -> Unit
    ) {
        val uid = Firebase.auth.uid
            ?: return onComplete(TaskState.DONE.ERROR(R.string.error_occurred))

        // --- Step 1: Run the username-reservation + user-doc + public-profile
        //          transaction FIRST. If the username is taken, we abort before
        //          uploading any bytes to Storage (fixes orphaned-profile-pic bug
        //          where the previous code uploaded first and then could fail).
        val user = User(
            uid = uid,
            name = name,
            username = username,
            bio = bio,
            number = phoneNumber,
            profilePic = null,        // will be filled in after upload
            lastSeen = System.currentTimeMillis()
        )

        val usernameReference = getUsernameReference(username)
        val userProfileReference = getUserProfileReference(uid)
        val publicProfileReference = getPublicUserProfileReference(uid)

        val publicProjection = mapOf(
            "uid" to uid,
            "name" to name,
            "username" to username,
            // profilePic is added in step 2 after upload; null-safe in the rules
            "profilePic" to null
        )

        try {
            firestore.runTransaction { transaction ->
                if (transaction.get(usernameReference).exists())
                    throw FirebaseFirestoreException(
                        "Username is already taken",
                        FirebaseFirestoreException.Code.ALREADY_EXISTS
                    )

                transaction.set(userProfileReference, user)
                transaction.set(publicProfileReference, publicProjection)
                transaction.set(usernameReference, mapOf("uid" to user.uid))
            }.await()
        } catch (exception: FirebaseFirestoreException) {
            val error = if (exception.code == FirebaseFirestoreException.Code.ALREADY_EXISTS)
                R.string.username_taken
            else
                R.string.error_occurred
            return onComplete(TaskState.DONE.ERROR(error))
        } catch (_: Exception) {
            return onComplete(TaskState.DONE.ERROR(R.string.error_occurred))
        }

        // --- Step 2: Upload the profile pic (if any) to Storage and patch the
        //          two Firestore docs with the resulting URL. If this fails,
        //          the user is still created — they just have no profile pic.
        if (profilePicLocalUri != null) {
            try {
                // Explicit StorageMetadata with contentType="image/jpeg" — the CanHub
                // cropper returns a cache URI whose content type is often detected as
                // null or application/octet-stream by ContentResolver.getType(uri),
                // which the storage.rules check `request.resource.contentType.matches('image/.*')`
                // would then DENY. Setting it explicitly bypasses the content-type
                // detection and unblocks the upload.
                val metadata = StorageMetadata.Builder()
                    .setContentType("image/jpeg")
                    .build()

                val downloadUrl = getStorageRefForProfilePic(uid)
                    .putFile(profilePicLocalUri, metadata)
                    .await()
                    .storage
                    .downloadUrl
                    .await()
                    .toString()

                userProfileReference.update(User::profilePic.name, downloadUrl).await()
                publicProfileReference.update("profilePic", downloadUrl).await()
            } catch (e: Exception) {
                Timber.e(e, "Profile pic upload failed; user created without pic")
                // non-fatal: leave profilePic as null in both docs
            }
        }

        onComplete(TaskState.DONE.SUCCESS)
    }


    override suspend fun isUsernameAvailable(username: String): Boolean =
        !getUsernameReference(username)
            .get()
            .await()
            .exists()

    override suspend fun getUserByUsername(username: String): User? {
        val usernameSnapshot = getUsernameReference(username).get().await()
        val uid = usernameSnapshot.getString("uid") ?: return null

        // Read from public_users/{uid} instead of users/{uid} — the users/{uid}
        // doc is owner-only-read under the new rules. public_users has
        // {uid, name, profilePic, username, fcmToken} — not the full User
        // (no number, bio, lastSeen, userStatus). Construct a partial User with
        // defaults for the private fields.
        return try {
            val publicDoc = getPublicUserProfileReference(uid).get().await()
            val data = publicDoc.data ?: return null
            User(
                uid = uid,
                name = data["name"] as? String ?: "",
                bio = "",  // not in public projection
                profilePic = data["profilePic"] as? String?,
                number = "",  // not in public projection
                lastSeen = 0,  // not in public projection
                userStatus = UserStatus.HasDataButNotInApp,  // default
                username = data["username"] as? String ?: ""
            )
        } catch (e: Exception) {
            Timber.e(e, "getUserByUsername: public_users read failed for uid=$uid")
            null
        }
    }


    override suspend fun getUserFromUID(uid: String): User? =
        getUserProfileReference(uid)
            .get().await()
            .toObject<User>()


    // ----------------------------------------------------------------------------
    // Deprecated: only deletes the `users/{uid}` doc and the Storage pic.
    // Use [deleteUserCompletely] for the full cleanup pipeline.
    // ----------------------------------------------------------------------------
    override suspend fun deleteUser(uid: String): Boolean = try {
        getUserProfileReference(uid).delete().await()
        // best-effort Storage cleanup
        getStorageRefForProfilePic(uid).delete().await()
        true
    } catch (e: Exception) {
        Timber.e(e, "deleteUser: failed to clean up $uid")
        false
    }


    override suspend fun deleteUserCompletely(uid: String, username: String): Boolean {
        var success = true

        // Helper that swallows per-step errors so the rest of the pipeline still runs.
        suspend fun step(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                Timber.e(e, "deleteUserCompletely: $name failed for uid=$uid")
                success = false
            }
        }

        // 1. Disable all chats where this user is a participant (set isDisabled=true)
        step("disableChatsForUser") {
            val chatsAsFirst = firestore.collection("chat_details")
                .whereEqualTo("firstMiniUser.uid", uid)
                .get().await()
            val chatsAsSecond = firestore.collection("chat_details")
                .whereEqualTo("secondMiniUser.uid", uid)
                .get().await()

            val allChatDocs = (chatsAsFirst.documents + chatsAsSecond.documents)
                .distinctBy { it.id }

            allChatDocs.forEach { doc ->
                val chatID = doc.id
                val otherUid =
                    if (doc.getString("firstMiniUser.uid") == uid)
                        doc.getString("secondMiniUser.uid")
                    else
                        doc.getString("firstMiniUser.uid")

                // Set isDisabled on the chat_details doc
                firestore.collection("chat_details").document(chatID)
                    .update("isDisabled", true).await()

                // Remove the OTHER participant's personalized_chats entry for this chat
                if (otherUid != null) {
                    firestore.collection("personalized_chats")
                        .document("FILLER")
                        .collection(otherUid)
                        .document(chatID)
                        .delete().await()
                }
            }

            // Remove the user's own personalized_chats subcollection entirely
            val ownPersonalizedChats = firestore.collection("personalized_chats")
                .document("FILLER")
                .collection(uid)
                .get().await()
            ownPersonalizedChats.documents.forEach { d ->
                d.reference.delete().await()
            }
        }

        // 2. Remove the user's blockedUsers subcollection
        step("removeBlockedUsers") {
            val blocked = firestore.collection("users")
                .document(uid)
                .collection("blockedUsers")
                .get().await()
            blocked.documents.forEach { d -> d.reference.delete().await() }
        }

        // 3. Remove the user's stories
        step("removeStories") {
            firestore.collection("story_details").document(uid).delete().await()

            val storyContent = firestore.collection("story")
                .document("content")
                .collection(uid)
                .get().await()
            storyContent.documents.forEach { d -> d.reference.delete().await() }
        }

        // 4. Remove the Storage profile pic
        step("removeStorageProfilePic") {
            getStorageRefForProfilePic(uid).delete().await()
        }

        // 5. Remove the `users/{uid}` doc
        step("removeUsersDoc") {
            getUserProfileReference(uid).delete().await()
        }

        // 6. Remove the `public_users/{uid}` projection doc
        step("removePublicUsersDoc") {
            getPublicUserProfileReference(uid).delete().await()
        }

        // 7. Free the `usernames/{username}` reservation (rules require caller == owner)
        step("removeUsernameReservation") {
            if (username.isNotEmpty()) {
                getUsernameReference(username).delete().await()
            }
        }

        // 8. Finally, delete the Firebase Auth user record (prevents "zombie"
        //    state on next launch where uid != null but no profile exists)
        step("deleteAuthUser") {
            Firebase.auth.currentUser?.delete()?.await()
        }

        // Sign out locally so any cached Auth state is cleared
        Firebase.auth.signOut()

        return success
    }
}
