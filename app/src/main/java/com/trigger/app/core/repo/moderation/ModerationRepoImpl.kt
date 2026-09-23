package com.trigger.app.core.repo.moderation

import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.toObject
import com.google.firebase.ktx.Firebase
import com.trigger.app.core.domain.User
import com.trigger.app.core.repo.user.UserRepo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class ModerationRepoImpl : ModerationRepo {
    private val firestore = Firebase.firestore

    private fun blockedUsersReference() = firestore
        .collection(UserRepo.USERS_COLLECTION)
        .document(Firebase.auth.uid!!)
        .collection("blockedUsers")

    override fun getBlockedUsers(): Flow<List<User>> = callbackFlow {
        val registration = blockedUsersReference().addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.documents?.mapNotNull { it.toObject<User>() } ?: emptyList())
        }
        awaitClose { registration.remove() }
    }

    override suspend fun blockUser(user: User) {
        blockedUsersReference().document(user.uid).set(user).await()
    }

    override suspend fun unblockUser(userID: String) {
        blockedUsersReference().document(userID).delete().await()
    }

    override suspend fun reportUser(userID: String, reason: String) {
        firestore.collection("reports").add(
            mapOf(
                "reporterID" to Firebase.auth.uid,
                "reportedUserID" to userID,
                "reason" to reason.trim(),
                "createdAt" to System.currentTimeMillis()
            )
        ).await()
    }
}
