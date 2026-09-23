package com.trigger.app.calls.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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
import com.trigger.app.calls.webrtc.CallAudioManager
import com.trigger.app.calls.webrtc.WebRtcCallSession
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
 * REAL WebRTC integration (no longer fake):
 *   - On ANSWERED: creates WebRtcCallSession + initialises audio track.
 *     Both sides do SDP offer/answer exchange + ICE candidate exchange
 *     via Firestore signaling docs under calls/{callID}/signaling.
 *   - On Hangup: closes PeerConnection + frees audio resources + writes
 *     COMPLETED + duration to calls/{callID}.
 *
 * AudioManager (CallAudioManager):
 *   - MODE_IN_COMMUNICATION is set on call start → routes audio to earpiece.
 *   - Speaker toggle flips isSpeakerphoneOn.
 *   - Mic mute flips the local audio track's setEnabled flag.
 */
class CallViewModel(
    application: Application,
    private val callsRepo: CallsRepo
) : AndroidViewModel(application) {

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
        val isVideoEnabled: Boolean = false,
        /** WebRTC peer connection state — null when no session has been created. */
        val isWebRtcConnected: Boolean = false
    )

    private val _state = MutableStateFlow<CallUiState?>(null)
    val state: StateFlow<CallUiState?> = _state.asStateFlow()

    private var snapshotListener: ListenerRegistration? = null
    private var timeoutJob: kotlinx.coroutines.Job? = null

    // REAL WebRTC session — replaces the fake-call placeholders.
    private var webRtcSession: WebRtcCallSession? = null
    private var audioManager: CallAudioManager? = null

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
            // Tear down WebRTC + AudioManager synchronously — needs to
            // happen NOW so the audio stops immediately when the user
            // hangs up, not on the next Firestore emission.
            teardownWebRtc()
        }
    }

    fun toggleMicrophone() {
        val current = _state.value ?: return
        val newMuted = !current.isMicrophoneMuted
        // REAL: tell the local audio track to disable + mute AudioManager.
        webRtcSession?.setMicrophoneEnabled(!newMuted)
        audioManager?.setMicrophoneMuted(newMuted)
        _state.value = current.copy(isMicrophoneMuted = newMuted)
    }

    fun toggleSpeaker() {
        val current = _state.value ?: return
        val newOn = !current.isSpeakerOn
        // REAL: route audio to earpiece vs speaker via AudioManager.
        audioManager?.setSpeakerOn(newOn)
        _state.value = current.copy(isSpeakerOn = newOn)
    }

    fun toggleVideo() {
        // Only relevant for VIDEO calls.
        if (_state.value?.callType != CallType.VIDEO) return
        _state.value = _state.value?.copy(isVideoEnabled = !_state.value!!.isVideoEnabled)
        // TODO Phase 2: real WebRTC video track enable/disable.
    }

    /**
     * Snapshot listener on `calls/{callID}`. Updates _state on every change.
     * Sets up the WebRTC session when status flips to ANSWERED.
     * Tears down on COMPLETED/DECLINED/MISSED/FAILED.
     */
    private fun startListening(
        callID: String,
        otherUserID: String,
        callType: CallType,
        isCaller: Boolean
    ) {
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
                    _state.value = null
                    snapshotListener?.remove()
                    snapshotListener = null
                    teardownWebRtc()
                    return@addSnapshotListener
                }

                val call = try {
                    snapshot.toObject<Call>() ?: return@addSnapshotListener
                } catch (e: Exception) {
                    Timber.e(e, "CallViewModel: parse failure for callID=$callID")
                    return@addSnapshotListener
                }

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
                    isVideoEnabled = prev?.isVideoEnabled ?: (callType == CallType.VIDEO),
                    isWebRtcConnected = prev?.isWebRtcConnected ?: false
                )

                // Trigger WebRTC setup when status flips to ANSWERED.
                if (call.status == CallStatus.ANSWERED && webRtcSession == null) {
                    setupWebRtc(
                        callID = callID,
                        isCaller = isCaller,
                        otherUserID = otherUserID,
                        callType = callType
                    )
                }

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
                    teardownWebRtc()
                }
            }
    }

    /**
     * Set up the real WebRTC peer connection + audio session. Called when
     * status flips to ANSWERED. Both sides call this — the caller creates
     * the SDP offer, the callee listens for it + creates the answer.
     */
    private fun setupWebRtc(
        callID: String,
        isCaller: Boolean,
        otherUserID: String,
        callType: CallType
    ) {
        val appContext = getApplication<Application>().applicationContext
        audioManager = CallAudioManager(appContext).also { it.onCallStart() }
        webRtcSession = WebRtcCallSession(
            context = appContext,
            callID = callID,
            isCaller = isCaller,
            otherUserID = otherUserID,
            onConnected = {
                Timber.d("WebRtcCallSession: CONNECTED")
                _state.value = _state.value?.copy(isWebRtcConnected = true)
            },
            onDisconnected = {
                Timber.d("WebRtcCallSession: DISCONNECTED")
                _state.value = _state.value?.copy(isWebRtcConnected = false)
            },
            onRemoteAudioAttached = {
                Timber.d("WebRtcCallSession: remote audio attached")
            },
            onError = { e ->
                Timber.e(e, "WebRtcCallSession: error — ending call")
                hangupCall()
            }
        ).also {
            it.init(enableVideo = callType == CallType.VIDEO)
        }
    }

    private fun teardownWebRtc() {
        try { webRtcSession?.end() } catch (_: Exception) {}
        webRtcSession = null
        try { audioManager?.onCallEnd() } catch (_: Exception) {}
        audioManager = null
    }

    /**
     * If the callee doesn't answer in 30s, auto-mark the call as MISSED.
     */
    private fun scheduleTimeout(callID: String) {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(30_000L)
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
        teardownWebRtc()
    }
}

