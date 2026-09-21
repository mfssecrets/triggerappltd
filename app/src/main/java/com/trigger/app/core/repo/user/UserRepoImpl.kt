package com.trigger.app.core.repo.user

import android.net.Uri
import com.trigger.app.R
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.domain.User
import com.trigger.app.core.repo.user.UserRepo.Companion.getStorageRefForProfilePic
import com.trigger.app.core.repo.user.UserRepo.Companion.getUserProfileReference
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.toObject
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await

class UserRepoImpl : UserRepo {

    override suspend fun createUser(
        name: String,
        bio: String,
        username: String,
        phoneNumber: String,
        profilePicLocalUri: Uri?,
        onComplete: (TaskState) -> Unit
    ) {
        var profilePicRemoteUrl: Uri? = null

        if (profilePicLocalUri != null)
            profilePicRemoteUrl =
                getStorageRefForProfilePic(Firebase.auth.uid!!).putFile(profilePicLocalUri)
                    .await()
                    .storage.downloadUrl.await()

        val user = User(
            uid = Firebase.auth.uid
                ?: throw NullPointerException("Firebase.auth.uid is ${Firebase.auth.uid}"),
            name = name,
            username = username,
            bio = bio,
            number = phoneNumber,
            profilePic = profilePicRemoteUrl?.toString(),
            lastSeen = System.currentTimeMillis()
        )

        val usernameReference = Firebase.firestore
            .collection(UserRepo.USERNAMES_COLLECTION)
            .document(username)

        try {
            Firebase.firestore.runTransaction { transaction ->
                if (transaction.get(usernameReference).exists())
                    throw FirebaseFirestoreException("Username is already taken", FirebaseFirestoreException.Code.ALREADY_EXISTS)

                transaction.set(usernameReference, mapOf("uid" to user.uid))
                transaction.set(getUserProfileReference(user.uid), user)
            }.await()
            onComplete(TaskState.DONE.SUCCESS)
        } catch (exception: FirebaseFirestoreException) {
            val error = if (exception.code == FirebaseFirestoreException.Code.ALREADY_EXISTS)
                R.string.username_taken
            else
                R.string.error_occurred
            onComplete(TaskState.DONE.ERROR(error))
        } catch (_: Exception) {
            onComplete(TaskState.DONE.ERROR(R.string.error_occurred))
        }
    }

    override suspend fun isUsernameAvailable(username: String): Boolean =
        !Firebase.firestore.collection(UserRepo.USERNAMES_COLLECTION)
            .document(username)
            .get()
            .await()
            .exists()

    override suspend fun getUserByUsername(username: String): User? {
        val usernameSnapshot = Firebase.firestore
            .collection(UserRepo.USERNAMES_COLLECTION)
            .document(username)
            .get()
            .await()
        val uid = usernameSnapshot.getString("uid") ?: return null
        return getUserFromUID(uid)
    }


    override suspend fun getUserFromUID(uid: String): User? =
        getUserProfileReference(uid)
            .get().await()
            .toObject<User>()


    override suspend fun deleteUser(uid: String) =
        getUserProfileReference(uid).delete().isSuccessful

}