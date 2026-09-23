package com.trigger.app.calls.repo

import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.toObject
import com.google.firebase.ktx.Firebase
import com.trigger.app.calls.domain.Call
import com.trigger.app.calls.domain.CallStatus
import com.trigger.app.calls.domain.CallType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.UUID

class CallsRepoImpl : CallsRepo {

    private val firestore = Firebase.firestore

    override fun getMissedCallNotifications(): Flow<List<Call>> = callbackFlow {
        val uid = Firebase.auth.uid ?: run {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        // Query calls where the current user is the callee AND the call
        // was missed AND they haven't seen the notification yet.
        //
        // Firestore compound query: calleeID == uid AND status == MISSED
        // AND read == false. Ordered by startedAt DESC (newest first).
        //
        // NOTE: requires a Firestore composite index on (calleeID, status,
        // read, startedAt). The first request from the console will create
        // it automatically — the SDK returns a "missing index" link in the
        // error that you click to one-click-create.
        val listener = firestore.collection(Call.CALLS_COLLECTION)
            .whereEqualTo("calleeID", uid)
            .whereEqualTo("status", CallStatus.MISSED.firebaseKey)
            .whereEqualTo("read", false)
            .orderBy("startedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.e(error, "getMissedCallNotifications: snapshot error")
                    // Defensive: don't crash — emit empty list and move on.
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                if (snapshot == null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                val missedCalls = snapshot.documents.mapNotNull { doc ->
                    try {
                        doc.toObject<Call>()?.copy(callID = doc.id)
                    } catch (e: Exception) {
                        Timber.e(e, "getMissedCallNotifications: parse failure for ${doc.id}")
                        null
                    }
                }
                trySend(missedCalls)
            }

        awaitClose { listener.remove() }
    }

    override suspend fun markCallAsRead(callID: String) {
        try {
            firestore.collection(Call.CALLS_COLLECTION)
                .document(callID)
                .update("read", true)
                .await()
        } catch (e: Exception) {
            Timber.e(e, "markCallAsRead: failed for callID=$callID")
        }
    }

    override suspend fun createCallRecord(calleeID: String, callType: CallType): String? {
        val callerID = Firebase.auth.uid ?: return null
        val callID = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val call = Call(
            callID = callID,
            callerID = callerID,
            calleeID = calleeID,
            callType = callType,
            status = CallStatus.INITIATED,
            startedAt = now,
            endedAt = null,
            durationMillis = null,
            read = true  // INITIATED isn't a notification — only MISSED flips this
        )

        return try {
            firestore.collection(Call.CALLS_COLLECTION)
                .document(callID)
                .set(call)
                .await()
            callID
        } catch (e: Exception) {
            Timber.e(e, "createCallRecord: failed for calleeID=$calleeID")
            null
        }
    }

    override suspend fun updateCallStatus(
        callID: String,
        newStatus: CallStatus,
        endedAt: Long?,
        durationMillis: Long?
    ) {
        val updates = mutableMapOf<String, Any?>(
            "status" to newStatus.firebaseKey
        )
        endedAt?.let { updates["endedAt"] = it }
        durationMillis?.let { updates["durationMillis"] = it }

        // When a call transitions to MISSED, flip `read` to false so it shows
        // up in the recipient's notifications. Any other transition flips
        // it back to true (no notification).
        updates["read"] = (newStatus == CallStatus.MISSED).not()

        try {
            firestore.collection(Call.CALLS_COLLECTION)
                .document(callID)
                .update(updates)
                .await()
        } catch (e: Exception) {
            Timber.e(e, "updateCallStatus: failed for callID=$callID")
        }
    }
}
