package com.trigger.app.core.repo.user

import android.net.Uri
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.domain.User

interface UserRepo {

    suspend fun createUser(
        name: String,
        bio: String,
        username: String,
        phoneNumber: String,
        profilePicLocalUri: Uri?,
        onComplete: (TaskState) -> Unit
    )

    suspend fun isUsernameAvailable(username: String): Boolean

    suspend fun getUserByUsername(username: String): User?

    suspend fun getUserFromUID(uid: String): User?

    /**
     * Read the PUBLIC projection of a user (from `public_users/{uid}`).
     *
     * Use this instead of [getUserFromUID] when displaying ANOTHER user's
     * profile (e.g., ChatDetails screen when you tap a search result).
     *
     * Firestore rules restrict `users/{uid}` to owner-only reads, so
     * `getUserFromUID` will throw `PERMISSION_DENIED` for any user that
     * isn't the caller. `public_users/{uid}` is signed-in-readable for
     * everyone, and contains { uid, name, username, profilePic } — enough
     * to render a profile UI. Private fields (bio, number, lastSeen,
     * userStatus) are filled with defaults.
     *
     * Returns null if the public_users doc doesn't exist (user signed up
     * but didn't complete onboarding — should not normally happen).
     */
    suspend fun getPublicUserFromUID(uid: String): User?

    // Full account-deletion pipeline. Callers MUST pass the username so we can
    // free the usernames/{username} reservation (the Firestore rules don't let
    // us read it back without ownership). See UserRepoImpl for the step list.
    suspend fun deleteUserCompletely(uid: String, username: String): Boolean

    // Deprecated: only deletes the users/{uid} doc and the Storage pic.
    // Use [deleteUserCompletely] for the full cleanup pipeline.
    suspend fun deleteUser(uid: String): Boolean


    companion object {
        const val USERS_COLLECTION = "users"
        const val USERNAMES_COLLECTION = "usernames"
        const val PUBLIC_USERS_COLLECTION = "public_users"

        const val PROFILE_PIC = "profilePic"

        fun getStorageRefForProfilePic(uid: String) =
            Firebase.storage.reference.child(USERS_COLLECTION).child(uid).child(PROFILE_PIC)

        fun getUserProfileReference(uid: String) =
            Firebase.firestore.collection(USERS_COLLECTION).document(uid)

        fun getPublicUserProfileReference(uid: String) =
            Firebase.firestore.collection(PUBLIC_USERS_COLLECTION).document(uid)

        fun getUsernameReference(username: String) =
            Firebase.firestore.collection(USERNAMES_COLLECTION).document(username)
    }
}
