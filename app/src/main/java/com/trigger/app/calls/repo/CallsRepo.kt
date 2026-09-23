package com.trigger.app.calls.repo

import com.trigger.app.calls.domain.Call
import com.trigger.app.calls.domain.CallStatus
import com.trigger.app.calls.domain.CallType
import kotlinx.coroutines.flow.Flow

/**
 * Read + write call records to Firestore `calls/{callID}`.
 *
 * The notifications screen queries `getMissedCallNotifications()` to show
 * missed calls for the current user. The actual call-creation flow (placing
 * a call, ringing the other side, answering, etc.) is a separate concern
 * that will be implemented when the Calls feature is built out from its
 * current "Coming Soon" placeholder.
 */
interface CallsRepo {

    /**
     * Live list of MISSED + unread calls for the current user (where they
     * are the callee — incoming missed calls only).
     *
     * Used by NotificationsScreen. Each emission replaces the entire list
     * — no incremental updates needed because the volume is small.
     */
    fun getMissedCallNotifications(): Flow<List<Call>>

    /**
     * Mark a missed call as read (after the user taps the notification row).
     * Server-side update — only the caller/callee can write.
     */
    suspend fun markCallAsRead(callID: String)

    /**
     * Write a new call record. Used by the (future) call-initiation flow.
     * Returns the new callID on success, null on failure.
     */
    suspend fun createCallRecord(
        calleeID: String,
        callType: CallType
    ): String?

    /**
     * Update a call's status (e.g., INITIATED → ANSWERED → COMPLETED,
     * or INITIATED → MISSED). Used by the (future) call state machine.
     */
    suspend fun updateCallStatus(
        callID: String,
        newStatus: CallStatus,
        endedAt: Long? = null,
        durationMillis: Long? = null
    )
}
