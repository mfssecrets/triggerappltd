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
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.tasks.await
import com.trigger.app.R
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

        // Wrap chat-details propagation in its own try-catch so a rule-denial or
        // network failure on the chat_details side does NOT cause the whole
        // updateUserName call to throw out of the viewModelScope.launch in
        // ProfileViewModel — that would silently kill the coroutine and the
        // user would see no error message (looks like "save didn't work").
        try {
            updateUserProfileInExistingChats(currentUser, newCurrentUser)
        } catch (e: Exception) {
            Timber.e(e, "updateUserName: chat propagation failed (non-fatal)")
        }

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
        // Explicit StorageMetadata with contentType="image/jpeg" — same reason as
        // in UserRepoImpl.createUser: CanHub cropper's cache URI may not have a
        // detectable image MIME type, which would fail the storage.rules
        // `request.resource.contentType.matches('image/.*')` check.
        val metadata = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .build()

        // FIX: Wrapped the entire flow body in try/catch. Previously the outer
        // `.catch { e -> Timber.e(e, ...) }` SILENTLY swallowed ALL exceptions
        // (Storage permission denied, network error, file > 5MB, etc.) and the
        // flow completed with NO error emission. The consumer's _taskState
        // stayed at the last LOADING(progress) value forever → infinite spinner,
        // no user feedback. Now we emit TaskState.DONE.ERROR so the UI can show
        // a message and reset the spinner.
        try {
            // Use Float division (was Long/Long → 0 for small files). Multiply
            // by 100 AFTER the division to get a 0..100 progress value.
            val newProfilePic = try {
                getStorageRefForProfilePic(userID)
                    .putFile(newProfilePicLocalUri, metadata)
                    .addOnProgressListener { task ->
                        val total = task.totalByteCount
                        val progress = if (total > 0) {
                            ((task.bytesTransferred.toFloat() / total) * 100).toInt()
                        } else 0
                        trySend(TaskState.LOADING(progress = progress))
                    }
                    .await()
                    .storage.downloadUrl
                    .await()
                    .toString()
            } catch (e: Exception) {
                Timber.e(e, "updateUserProfilePic: Storage upload failed for userID=$userID")
                // FIX: surface the error — was swallowed by .catch() at flow end.
                trySend(TaskState.DONE.ERROR(R.string.profile_pic_upload_failed))
                awaitClose { /* no-op */ }
                return@callbackFlow
            }

            val currentUser =
                getUserProfileReference(userID).get().await().toObject(User::class.java)?.toMiniUser()
            val newCurrentUser = currentUser?.copy(profilePic = newProfilePic)

            // FIX: if the Firestore users/{uid} update fails, the Storage upload
            // succeeded but the URL is never saved to Firestore → snapshot
            // listener never fires → user sees cartoon forever, even though
            // "upload succeeded". Now we surface this as an error to the user.
            var usersUpdateFailed = false
            try {
                getUserProfileReference(userID)
                    .update(User::profilePic.name, newProfilePic)
                    .await()
            } catch (e: Exception) {
                Timber.e(e, "updateUserProfilePic: users/{uid} update failed")
                usersUpdateFailed = true
            }

            // Mirror to public_users projection (best-effort — failure here
            // doesn't affect the owner's own profile screen, only other users'
            // view of this user).
            try {
                getPublicUserProfileReference(userID)
                    .update("profilePic", newProfilePic)
                    .await()
            } catch (e: Exception) {
                Timber.e(e, "updateUserProfilePic: public_users/{uid} update failed (non-fatal)")
            }

            // Update the user profile in the chats collection (best-effort).
            try {
                updateUserProfileInExistingChats(currentUser, newCurrentUser)
            } catch (e: Exception) {
                Timber.e(e, "updateUserProfilePic: chat propagation failed (non-fatal)")
            }

            if (usersUpdateFailed) {
                // FIX: surface this as an error so the user knows the upload
                // didn't stick — they should retry.
                trySend(TaskState.DONE.ERROR(R.string.profile_pic_save_failed))
            } else {
                trySend(TaskState.DONE.SUCCESS)
            }
        } catch (e: Exception) {
            // Fallback catch — anything not caught above.
            Timber.e(e, "updateUserProfilePic: unexpected error")
            trySend(TaskState.DONE.ERROR(R.string.profile_pic_upload_failed))
        }

        // No spurious SUCCESS on awaitClose — the previous `awaitClose { trySend(SUCCESS) }`
        // sent a second SUCCESS when the flow was cancelled (e.g., screen exit),
        // making the consumer think a cancelled upload actually succeeded.
        awaitClose { /* no-op — flow is cancelled by the consumer */ }
    }.catch { e ->
        // FIX: don't silently swallow — re-throw so the consumer sees the failure.
        // The try/catch inside the flow body already emitted TaskState.DONE.ERROR
        // for known failures. This outer catch is a last-resort safety net that
        // ensures the flow doesn't silently complete if something unexpected
        // throws OUTSIDE the try block (e.g. cancellation propagation).
        Timber.e(e, "updateUserProfilePic: flow-level catch — should not normally fire")
        throw e
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