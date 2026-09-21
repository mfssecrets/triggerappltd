package com.trigger.app.core.repo.user

import android.net.Uri
import com.trigger.app.R
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.domain.User
import com.trigger.app.core.repo.user.UserRepo.Companion.getStorageRefForProfilePic
import com.trigger.app.core.repo.user.UserRepo.Companion.getUserProfileReference
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.toObject
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await

class UserRepoImpl : UserRepo {

    override suspend fun createUser(
        name: String,
        bio: String,
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
            bio = bio,
            number = phoneNumber,
            profilePic = profilePicRemoteUrl?.toString(),
            lastSeen = System.currentTimeMillis()
        )

        getUserProfileReference(Firebase.auth.uid!!)
            .set(user)
            .addOnCompleteListener { task ->
                if (task.isSuccessful)
                    onComplete(TaskState.DONE.SUCCESS)
                else
                    onComplete(TaskState.DONE.ERROR(R.string.error_occurred))

            }
    }


    override suspend fun getUserFromUID(uid: String): User? =
        getUserProfileReference(uid)
            .get().await()
            .toObject<User>()


    override suspend fun deleteUser(uid: String) =
        getUserProfileReference(uid).delete().isSuccessful

}