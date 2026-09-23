package com.trigger.app.calls.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.toObject
import com.google.firebase.ktx.Firebase
import com.trigger.app.calls.domain.Call
import com.trigger.app.calls.domain.CallStatus
import com.trigger.app.calls.domain.CallType
import com.trigger.app.calls.repo.CallsRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Drives the call state machine across all three call screens:
 *   - OutgoingCallScreen: caller waiting for callee to answer
 *   - IncomingCallScreen: callee deciding to accept/decline
 *   - InCallScreen: both sides in the active call
 *
 * The state machine is stored in Firestore `calls/{callID}.status`. Both
 * sides listen to the same doc via a snapshot listener → state transitions
 * are propagated in real time.
 *
 * NOTE: This VM is a "fake call" implementation — it manages the call
 * state machine and writes call records, but it does NOT do real WebRTC
 * audio/video. Both sides will see "Call in progress" UI but no audio.
 * Real WebRTC integration is a separate phase.
 *
 * Lifecycle hooks for the (future) WebRTC integration:
 *   - onEnterActive(): set up PeerConnection, exchange SDP offer/answer
 *     via Firestore signaling docs, start audio track.
 *   - onHangup(): close PeerConnection, free audio resources.
 */
class CallViewModel(
    private val callsRepo: CallsRepo
) : ViewModel() {

    /** Per-call UI state. Null when no call is in progress. */
    data class CallUiState(
        val callID: String,
        val callType: CallType,
        val status: CallStatus,
        val otherUserID: String,
        val otherUserName: String,
        val otherUserProfilePic: String?,
        val startedAt: Long,
        val endedAt: Long? = null,
        val durationMillis: Long? = null,
        val isMicrophoneMuted: Boolean = false,
        val isSpeakerOn: Boolean = false,
        val isVideoEnabled: Boolean = false
    )

    private val _state = MutableStateFlow<CallUiState?>(null)
    val state: StateFlow<CallUiState?> = _state.asStateFlow()

    private var snapshotListener: ListenerRegistration? = null
    private var timeoutJob: kotlinx.coroutines.Job? = null

    /**
     * Caller-side entry: caller tapped "Call" button on ChatDetailsScreen.
     * Creates a call record with status=INITIATED and starts listening.
     */
    fun initiateCall(calleeID: String, callType: CallType) {
        viewModelScope.launch {
            val callID = callsRepo.createCallRecord(calleeID, callType)
            if (callID == null) {
                Timber.e("initiateCall: createCallRecord returned null")
                return@launch
            }
            // The Cloud Function onCallInitate picks up the new doc and
            // sends an FCM to the callee. Caldee's app opens IncomingCallScreen.
            startListening(callID, calleeID, callType, isCaller = true)

            // Auto-mark MISSED if callee doesn't answer in 30s.
            scheduleTimeout(callID)
        }
    }

    /**
     * Callee-side entry: FCM push arrived with callID. Loads the call doc
     * + starts listening for state transitions.
     */
    fun joinIncomingCall(callID: String, callerID: String, callType: CallType) {
        startListening(callID, callerID, callType, isCaller = false)
    }

    /**
     * Callee taps "Accept" on IncomingCallScreen.
     * Sets status=ANSWERED. Both sides' snapshot listeners fire → navigate to InCallScreen.
     */
    fun acceptCall() {
        val current = _state.value ?: return
        viewModelScope.launch {
            callsRepo.updateCallStatus(
                callID = current.callID,
                newStatus = CallStatus.ANSWERED
            )
            // Cancel the auto-miss timeout — the callee answered.
            timeoutJob?.cancel()
            timeoutJob = null
        }
    }

    /**
     * Callee taps "Decline" on IncomingCallScreen, OR caller taps "Cancel"
     * on OutgoingCallScreen before the callee answers.
     */
    fun declineOrCancelCall(targetStatus: CallStatus) {
        val current = _state.value ?: return
        viewModelScope.launch {
            callsRepo.updateCallStatus(
                callID = current.callID,
                newStatus = targetStatus,
                endedAt = System.currentTimeMillis()
            )
            timeoutJob?.cancel()
            timeoutJob = null
        }
    }

    /**
     * Either side taps "Hangup" on InCallScreen.
     */
    fun hangupCall() {
        val current = _state.value ?: return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            callsRepo.updateCallStatus(
                callID = current.callID,
                newStatus = CallStatus.COMPLETED,
                endedAt = now,
                durationMillis = now - current.startedAt
            )
            timeoutJob?.cancel()
            timeoutJob = null
        }
    }

    fun toggleMicrophone() {
        _state.value = _state.value?.copy(isMicrophoneMuted = !_state.value!!.isMicrophoneMuted)
        // TODO: WebRTC — actually mute the local audio track
    }

    fun toggleSpeaker() {
        _state.value = _state.value?.copy(isSpeakerOn = !_state.value!!.isSpeakerOn)
        // TODO: WebRTC — route audio to earpiece / speakerphone
    }

    fun toggleVideo() {
        // Only relevant for VIDEO calls.
        if (_state.value?.callType != CallType.VIDEO) return
        _state.value = _state.value?.copy(isVideoEnabled = !_state.value!!.isVideoEnabled)
        // TODO: WebRTC — start/stop local video capture
    }

    /**
     * Snapshot listener on `calls/{callID}`. Updates _state on every change.
     * Cleans up on null (call doc deleted) or COMPLETED/DECLINED/MISSED.
     */
    private fun startListening(
        callID: String,
        otherUserID: String,
        callType: CallType,
        isCaller: Boolean
    ) {
        // Tear down any previous listener (VM can be reused).
        snapshotListener?.remove()

        snapshotListener = Firebase.firestore
            .collection(Call.CALLS_COLLECTION)
            .document(callID)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.e(error, "CallViewModel: snapshot error for callID=$callID")
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) {
                    // Call doc was deleted — clean up.
                    _state.value = null
                    snapshotListener?.remove()
                    snapshotListener = null
                    return@addSnapshotListener
                }

                val call = try {
                    snapshot.toObject<Call>() ?: return@addSnapshotListener
                } catch (e: Exception) {
                    Timber.e(e, "CallViewModel: parse failure for callID=$callID")
                    return@addSnapshotListener
                }

                // Preserve the user's mute/speaker/video toggles across state updates.
                val prev = _state.value
                _state.value = CallUiState(
                    callID = call.callID,
                    callType = call.callType,
                    status = call.status,
                    otherUserID = otherUserID,
                    otherUserName = prev?.otherUserName ?: "",
                    otherUserProfilePic = prev?.otherUserProfilePic,
                    startedAt = call.startedAt,
                    endedAt = call.endedAt,
                    durationMillis = call.durationMillis,
                    isMicrophoneMuted = prev?.isMicrophoneMuted ?: false,
                    isSpeakerOn = prev?.isSpeakerOn ?: false,
                    isVideoEnabled = prev?.isVideoEnabled ?: (callType == CallType.VIDEO)
                )

                // Terminal states — tear down listener + WebRTC resources.
                if (call.status == CallStatus.COMPLETED ||
                    call.status == CallStatus.DECLINED ||
                    call.status == CallStatus.MISSED ||
                    call.status == CallStatus.FAILED
                ) {
                    Timber.d("CallViewModel: terminal state ${call.status} — cleaning up")
                    snapshotListener?.remove()
                    snapshotListener = null
                    timeoutJob?.cancel()
                    timeoutJob = null
                    // The UI keeps the state for ~2s to show a "Call ended"
                    // message, then the screen dismisses. Caller's responsibility.
                }
            }
    }

    /**
     * If the callee doesn't answer in 30s, auto-mark the call as MISSED.
     * The snapshot listener on the callee side then sees the transition
     * and dismisses IncomingCallScreen.
     */
    private fun scheduleTimeout(callID: String) {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(30_000L)
            // If still INITIATED, mark as MISSED.
            val current = _state.value
            if (current != null && current.status == CallStatus.INITIATED) {
                callsRepo.updateCallStatus(
                    callID = callID,
                    newStatus = CallStatus.MISSED,
                    endedAt = System.currentTimeMillis()
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        snapshotListener?.remove()
        snapshotListener = null
        timeoutJob?.cancel()
        timeoutJob = null
    }
}
