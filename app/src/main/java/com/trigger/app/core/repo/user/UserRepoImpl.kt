package com.trigger.app.core.repo.user

import android.net.Uri
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.toObject
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.ktx.storage
import com.trigger.app.R
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.domain.User
import com.trigger.app.core.domain.UserStatus
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

    override suspend fun getPublicUserFromUID(uid: String): User? = try {
        // Read from public_users/{uid} — signed-in-readable for everyone.
        // Falls back gracefully for private fields (bio, number, lastSeen,
        // userStatus) that aren't in the public projection.
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
        Timber.e(e, "getPublicUserFromUID: failed for uid=$uid")
        null
    }


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
        // FIX #5: Call the callable Cloud Function `deleteUserAccount` instead
        // of doing client-side cleanup. The old implementation did Firestore +
        // Storage cleanup from the client (subject to permission rules + race
        // conditions) and tried to delete the Auth user from the client (which
        // throws FirebaseAuthRecentLoginRequiredException if signed in >5min ago).
        // The Cloud Function has admin privileges (bypasses all rules) and
        // calls auth.deleteUser(uid) server-side (no recent-login requirement).
        //
        // The old Firestore trigger `onDeleteUser` (onDocumentDeleted) has been
        // REMOVED — accidental Firestore doc deletion no longer triggers Auth
        // account deletion. Account deletion only happens when the client
        // explicitly calls this callable function.
        return try {
            val result = Firebase.functions
                .getHttpsCallable("deleteUserAccount")
                .call(mapOf("uid" to uid, "username" to username))
                .await()

            // Sign out locally regardless of the function result.
            Firebase.auth.signOut()

            val data = result.data as? Map<*, *>
            data?.get("success") == true
        } catch (e: Exception) {
            Timber.e(e, "deleteUserCompletely: Cloud Function call failed")
            // Sign out anyway — the user wants to leave.
            Firebase.auth.signOut()
            false
        }
    }
}
