package com.trigger.app.calls.domain

/**
 * Represents a single call record (audio / video).
 *
 * Stored in Firestore at `calls/{callID}`. Used by:
 *   - CallsScreen (future — list of recent calls)
 *   - NotificationsScreen (missed calls row — `status == MISSED && read == false`)
 *
 * Lifecycle (state machine):
 *   INITIATED  → call doc created when caller taps "call"
 *   ANSWERED   → callee accepted (was INITIATED)
 *   COMPLETED  → call ended normally (was ANSWERED)
 *   DECLINED  → callee rejected (was INITIATED)
 *   MISSED    → caller hung up before callee answered, OR no answer in 30s
 *   FAILED    → network error / device unreachable
 *
 * `read` field: false until the recipient opens the missed-call notification
 * (true after they tap it in NotificationsScreen). Only MISSED calls have a
 * meaningful `read` flag — answered/declined/completed calls default true.
 */
data class Call(
    val callID: String,
    val callerID: String,            // UID of the user who initiated
    val calleeID: String,            // UID of the user being called
    val callType: CallType,
    val status: CallStatus,
    val startedAt: Long,             // epoch millis when the call was initiated
    val endedAt: Long? = null,       // epoch millis when the call ended (null if ongoing)
    val durationMillis: Long? = null,// only for COMPLETED — for display in call history
    val read: Boolean = true         // for MISSED notifications: false until viewed
) {

    /**
     * Empty no-arg constructor for Firestore.toObject<Call>().
     * Uses sensible defaults so a missing field doesn't crash parsing.
     */
    constructor() : this(
        callID = "",
        callerID = "",
        calleeID = "",
        callType = CallType.AUDIO,
        status = CallStatus.FAILED,
        startedAt = 0L,
        endedAt = null,
        durationMillis = null,
        read = true
    )


    companion object {
        const val CALLS_COLLECTION = "calls"
    }
}


enum class CallType(val firebaseKey: String) {
    AUDIO("AUDIO"),
    VIDEO("VIDEO");

    companion object {
        fun fromFirebaseKey(key: String?): CallType =
            entries.firstOrNull { it.firebaseKey == key } ?: AUDIO
    }
}


enum class CallStatus(val firebaseKey: String) {
    INITIATED("INITIATED"),
    ANSWERED("ANSWERED"),
    COMPLETED("COMPLETED"),
    DECLINED("DECLINED"),
    MISSED("MISSED"),
    FAILED("FAILED");

    companion object {
        fun fromFirebaseKey(key: String?): CallStatus =
            entries.firstOrNull { it.firebaseKey == key } ?: FAILED
    }
}
