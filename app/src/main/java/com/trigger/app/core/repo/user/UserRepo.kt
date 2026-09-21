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
     * Full account-deletion pipeline. Callers MUST pass the username so we can
     * free the `usernames/{username}` reservation (the Firestore rules don't
     * let us read it back without ownership).
     *
     * Order of operations:
     *   1. Disable all `chat_details` where user is a participant (set isDisabled=true)
     *   2. Remove the user's own `personalized_chats/FILLER/{uid}/*` docs
     *   3. Remove the OTHER participant's `personalized_chats/FILLER/{otherUid}/{chatID}` entries
     *   4. Remove all `users/{uid}/blockedUsers/*` docs
     *   5. Remove the `users/{uid}` profile doc
     *   6. Remove the `public_users/{uid}` projection doc
     *   7. Remove the `usernames/{username}` reservation
     *   8. Remove `story_details/{uid}` and all `story/content/{uid}/*`
     *   9. Remove the Storage `USERS/{uid}/profilePic` object
     *  10. Call `Firebase.auth.currentUser?.delete()` to remove the Auth user record
     *
     * @return true iff every step completed without error (errors are logged via Timber)
     */
    suspend fun deleteUserCompletely(uid: String, username: String): Boolean

    /**
     * @deprecated use [deleteUserCompletely] instead — this only removes the
     *   `users/{uid}` doc and does not await the Task. Kept for source-compat
     *   with callers that haven't been migrated yet.
     */
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
