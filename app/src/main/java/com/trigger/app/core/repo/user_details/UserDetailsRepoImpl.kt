package com.trigger.app.core.repo.user_details

import android.net.Uri
import com.trigger.app.chats.domain.Chat
import com.trigger.app.chats.presentation.utils.toChatPreviewTime
import com.trigger.app.chats.repo.chats.ChatRepo.Companion.CHAT_DETAILS
import com.trigger.app.chats.repo.chats.ChatRepo.Companion.getChatDetailsRef
import com.trigger.app.core.domain.MiniUser
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.domain.User
import com.trigger.app.core.domain.UserStatus
import com.trigger.app.core.domain.isOnline
import com.trigger.app.core.domain.toMiniUser
import com.trigger.app.core.repo.user.UserRepo.Companion.getPublicUserProfileReference
import com.trigger.app.core.repo.user.UserRepo.Companion.getStorageRefForProfilePic
import com.trigger.app.core.repo.user.UserRepo.Companion.getUserProfileReference
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.toObject
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.tasks.await
import timber.log.Timber

class UserDetailsRepoImpl : UserDetailsRepo {

    override val getUserProfileFlow: Flow<User?>
        get() = callbackFlow {
            val userListener = getUserProfileReference(Firebase.auth.uid!!)
                .addSnapshotListener { value, error ->
                    val user = value?.toObject(User::class.java)
                    Timber.e(error)

                    trySend(user)
                }


            awaitClose {
                userListener.remove()
            }
        }


    override fun getUserLastSeenAsFlow(user: Flow<MiniUser?>): Flow<String?> = callbackFlow {
        var lastSeenCallback: ListenerRegistration? = null

        user.collectLatest {
            val userID = it?.uid

            if (userID != null) {
                lastSeenCallback =
                    getUserProfileReference(userID).addSnapshotListener { value, error ->
                        error?.printStackTrace()

                        val updatedUser = value?.toObject<User>()
//                        Timber.d("updatedUser is $updatedUser")

                        trySend(
                            if (updatedUser != null) {
                                if (updatedUser.isOnline()) User.ONLINE
                                else "Last Seen: ${
                                    updatedUser.lastSeen.toChatPreviewTime(addAmPMSymbol = true)
                                }"
                            } else
                                null
                        )
                    }
            }
        }

        awaitClose {
            lastSeenCallback?.remove()
        }
    }


    override suspend fun goOnline(userID: String) {
        Timber.d("Go Online called")
        getUserProfileReference(userID).update(
            User::userStatus.name, UserStatus.Active
        ).await()
    }

    override suspend fun goOffline(userID: String) {
        Timber.d("Go Offline called")

        getUserProfileReference(userID).update(
            User::userStatus.name, UserStatus.Offline
        ).await()

        Timber.d("GoOffline was successful: true")
    }

    override suspend fun updateUserName(userID: String, newName: String): Boolean {
        val currentUser =
            getUserProfileReference(userID).get().await().toObject(User::class.java)?.toMiniUser()
        val newCurrentUser = currentUser?.copy(name = newName)

        // Await the Firestore Task — fixes the previous .isSuccessful-on-incomplete-Task
        // bug that always returned false. Now reflects the real outcome.
        val isSuccessful = try {
            getUserProfileReference(userID)
                .update(User::name.name, newName)
                .await()
            // Mirror the update to the public_users projection so other users
            // see the new name on chat previews without leaking the phone number.
            getPublicUserProfileReference(userID)
                .update("name", newName)
                .await()
            true
        } catch (e: Exception) {
            Timber.e(e, "updateUserName: failed for userID=$userID")
            false
        }

        updateUserProfileInExistingChats(currentUser, newCurrentUser)

        return isSuccessful
    }

    override suspend fun updateUserBio(userID: String, newBio: String): Boolean = try {
        getUserProfileReference(userID)
            .update(User::bio.name, newBio)
            .await()
        true
    } catch (e: Exception) {
        Timber.e(e, "updateUserBio: failed for userID=$userID")
        false
    }


    override fun updateUserLastSeen(userID: String, lastSeen: Long) {
        Timber.d("Service: updateUserLastSeen with lastSeen as $lastSeen")

        getUserProfileReference(userID)
            .update(User::lastSeen.name, lastSeen)

//        if (userStatus == UserStatus.Offline)
//            getUserProfileReference(userID)
//                .update(User::lastSeen.name, System.currentTimeMillis())
    }

    override suspend fun updateUserProfilePic(
        userID: String,
        newProfilePicLocalUri: Uri
    ): Flow<TaskState> = callbackFlow {
        val newProfilePic = getStorageRefForProfilePic(userID).putFile(newProfilePicLocalUri)
            .addOnProgressListener { task ->
                val progress = ((task.bytesTransferred) / (task.totalByteCount)) * 100
                Timber.d("progress is $progress")

                trySend(TaskState.LOADING(progress = progress.toInt()))
            }
            .await()
            .storage.downloadUrl
            .await()
            .toString()

        Timber.d("newProfilePic is $newProfilePic")

        val currentUser =
            getUserProfileReference(userID).get().await().toObject(User::class.java)?.toMiniUser()
        val newCurrentUser = currentUser?.copy(profilePic = newProfilePic)

        // Update the main profile in /users/
        getUserProfileReference(userID)
            .update(User::profilePic.name, newProfilePic)

        // Update the user profile in the chats collection
        updateUserProfileInExistingChats(currentUser, newCurrentUser)

        trySend(TaskState.DONE.SUCCESS)

        awaitClose {
            trySend(TaskState.DONE.SUCCESS)
        }
    }.catch {
        Timber.e(it)
    }


    private suspend fun updateUserProfileInExistingChats(
        currentUser: MiniUser?,
        newCurrentUser: MiniUser?
    ) {
        val chatsWhereCurrentUserIsFirstUser = Firebase.firestore.collection(CHAT_DETAILS)
            .whereEqualTo(Chat::firstMiniUser.name, currentUser)
            .get()
            .await()
            .toObjects(Chat::class.java)
            .map { it.chatID }

        chatsWhereCurrentUserIsFirstUser.forEach { chatId ->
            getChatDetailsRef(chatId)
                .update(Chat::firstMiniUser.name, newCurrentUser)
        }


        val chatsWhereCurrentUserIsSecondUser = Firebase.firestore.collection(CHAT_DETAILS)
            .whereEqualTo(Chat::secondMiniUser.name, currentUser)
            .get()
            .await()
            .toObjects(Chat::class.java)
            .map { it.chatID }

        chatsWhereCurrentUserIsSecondUser.forEach { chatId ->
            getChatDetailsRef(chatId)
                .update(Chat::secondMiniUser.name, newCurrentUser)
        }
    }
}