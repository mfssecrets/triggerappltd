package com.trigger.app.calls.webrtc

import android.content.Context
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.trigger.app.calls.domain.Call
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Real WebRTC peer-to-peer audio + video session for a single call.
 *
 * Uses raw `org.webrtc.*` classes (the underlying libwebrtc API, repackaged
 * by `io.getstream:stream-webrtc-android:1.3.8` with prebuilt native binaries).
 *
 * Firestore signaling layout (per call):
 *   calls/{callID}/signaling/offer       → { sdp, type, senderID }
 *   calls/{callID}/signaling/answer      → { sdp, type, senderID }
 *   calls/{callID}/signaling/candidates/{uid}/items/{candidateID} → ICE candidate docs
 */
class WebRtcCallSession(
    private val context: Context,
    private val callID: String,
    private val isCaller: Boolean,
    private val otherUserID: String,
    private val myUID: String,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onRemoteAudioAttached: () -> Unit,
    private val onError: (Exception) -> Unit
) {

    companion object {
        private const val TAG = "WebRtcCallSession"
        private const val SIGNALING_COLLECTION = "signaling"
        private const val OFFER_DOC = "offer"
        private const val ANSWER_DOC = "answer"
        private const val CANDIDATES_COLLECTION = "candidates"
        private const val CANDIDATES_SUBCOLLECTION = "items"
        private const val AUDIO_TRACK_ID = "local_audio"
        private const val VIDEO_TRACK_ID = "local_video"

        private val STUN_SERVER = PeerConnection.IceServer.builder(
            listOf("stun:stun.l.google.com:19302")
        ).createIceServer()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val firestore = Firebase.firestore

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var videoCapturer: org.webrtc.VideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null

    private val pendingRemoteCandidates = ConcurrentLinkedQueue<IceCandidate>()

    private var offerListener: ListenerRegistration? = null
    private var answerListener: ListenerRegistration? = null
    private var remoteCandidatesListener: ListenerRegistration? = null

    private val _connectionState = MutableStateFlow(PeerConnection.IceConnectionState.NEW)
    val connectionState: StateFlow<PeerConnection.IceConnectionState> =
        _connectionState.asStateFlow()

    private val _isMicrophoneEnabled = MutableStateFlow(true)
    val isMicrophoneEnabled: StateFlow<Boolean> = _isMicrophoneEnabled.asStateFlow()

    fun init(enableVideo: Boolean) {
        try {
            // 1. Initialise global WebRTC.
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
            )

            // 2. EGL base for video (even for audio-only calls, factory
            // builder may need it for video encoder/decoder factories).
            eglBase = EglBase.create()

            // 3. AudioDeviceModule — handles mic capture + speaker routing.
            adm = JavaAudioDeviceModule.builder(context)
                .createAudioDeviceModule()

            // 4. PeerConnectionFactory with audio + video encoder/decoder
            // factories. We include video factories even for audio-only
            // calls because remote side might be a video call.
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm!!)
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
                )
                .setVideoDecoderFactory(
                    DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
                )
                .createPeerConnectionFactory()

            // 5. ICE servers — STUN (always) + TURN (if configured).
            val iceServers = mutableListOf(STUN_SERVER)
            CallConfig.turnServer?.let { turn ->
                iceServers.add(
                    PeerConnection.IceServer.builder(
                        listOf("turn:${turn.host}:${turn.port}")
                    )
                        .setUsername(turn.username)
                        .setPassword(turn.credential)
                        .createIceServer()
                )
            }

            // 6. RTC config.
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                // iceTransportsPolicy defaults to ALL — don't set explicitly
                // to avoid API naming drift across libwebrtc versions.
                bundlePolicy = PeerConnection.BundlePolicy.BALANCED
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            // 7. PeerConnection with observer.
            peerConnection = factory?.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate?.let { sendIceCandidateToFirestore(it) }
                    }

                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        Timber.d("$TAG iceConnectionState=$state")
                        _connectionState.value = state
                            ?: PeerConnection.IceConnectionState.NEW
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> onConnected()
                            PeerConnection.IceConnectionState.DISCONNECTED,
                            PeerConnection.IceConnectionState.FAILED,
                            PeerConnection.IceConnectionState.CLOSED -> onDisconnected()
                            else -> Unit
                        }
                    }

                    override fun onAddTrack(
                        receiver: RtpReceiver?,
                        mediaStreams: Array<out MediaStream>?
                    ) {
                        val track = receiver?.track() ?: return
                        if (track.kind() == MediaStreamTrack.AUDIO_TRACK_KIND) {
                            (track as? AudioTrack)?.setEnabled(true)
                            onRemoteAudioAttached()
                            Timber.d("$TAG remote audio track attached")
                        }
                    }

                    override fun onSignalingChange(p0: PeerConnection.SignalingState?) { }
                    override fun onIceConnectionReceivingChange(p0: Boolean) { }
                    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) { }
                    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) { }
                    override fun onAddStream(p0: MediaStream?) { }
                    override fun onRemoveStream(p0: MediaStream?) { }
                    override fun onDataChannel(p0: DataChannel?) { }
                    override fun onRenegotiationNeeded() { }
                    override fun onTrack(p0: RtpTransceiver?) { }
                }
            ) ?: run {
                onError(IllegalStateException("PeerConnection creation returned null"))
                return
            }

            // 8. Local audio track with echo cancellation + noise suppression.
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            }
            localAudioSource = factory?.createAudioSource(audioConstraints)
            localAudioTrack = factory?.createAudioTrack(AUDIO_TRACK_ID, localAudioSource)
                ?.apply { setEnabled(true) }
            peerConnection?.addTrack(localAudioTrack)

            // 9. (Phase 2 TODO) Video — Camera2Enumerator + VideoCapturer.
            if (enableVideo) {
                Timber.w("$TAG video track not yet implemented — see Phase 2 TODO")
            }

            // 10. Signaling flow.
            if (isCaller) {
                createAndSendOffer()
            } else {
                listenForOffer()
            }
            listenForRemoteIceCandidates()

            Timber.d("$TAG initialised — isCaller=$isCaller, otherUserID=$otherUserID")
        } catch (e: Exception) {
            Timber.e(e, "$TAG init failed")
            onError(e)
        }
    }

    // -------------------------------------------------------------------------
    // Signaling — SDP offer / answer exchange
    // -------------------------------------------------------------------------

    private fun createAndSendOffer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        writeSdpToFirestore(OFFER_DOC, sdp, "offer") {
                            Timber.d("$TAG offer written to Firestore")
                            listenForAnswer()
                        }
                    }
                    override fun onSetFailure(error: String?) {
                        Timber.e("$TAG setLocalDescription failed: $error")
                        onError(IllegalStateException("setLocalDescription failed: $error"))
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) { }
                    override fun onCreateFailure(p0: String?) { }
                }, sdp)
            }
            override fun onCreateFailure(error: String?) {
                Timber.e("$TAG createOffer failed: $error")
                onError(IllegalStateException("createOffer failed: $error"))
            }
            override fun onSetSuccess() { }
            override fun onSetFailure(error: String?) { }
        }, constraints)
    }

    private fun listenForOffer() {
        offerListener = firestore
            .collection(Call.CALLS_COLLECTION).document(callID)
            .collection(SIGNALING_COLLECTION).document(OFFER_DOC)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.e(error, "$TAG offer listener error")
                    return@addSnapshotListener
                }
                val sdp = snapshot?.getString("sdp") ?: return@addSnapshotListener
                if (snapshot.getString("senderID") == myUID) return@addSnapshotListener
                handleRemoteOffer(sdp)
            }
    }

    private fun handleRemoteOffer(remoteSdp: String) {
        val pc = peerConnection ?: return
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                flushPendingCandidates()
                val constraints = MediaConstraints()
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        if (answer == null) return
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                writeSdpToFirestore(ANSWER_DOC, answer, "answer") {
                                    Timber.d("$TAG answer written to Firestore")
                                }
                            }
                            override fun onSetFailure(error: String?) {
                                Timber.e("$TAG setLocalDescription(answer) failed: $error")
                                onError(IllegalStateException("setLocalDescription(answer) failed: $error"))
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) { }
                            override fun onCreateFailure(p0: String?) { }
                        }, answer)
                    }
                    override fun onCreateFailure(error: String?) {
                        Timber.e("$TAG createAnswer failed: $error")
                        onError(IllegalStateException("createAnswer failed: $error"))
                    }
                    override fun onSetSuccess() { }
                    override fun onSetFailure(error: String?) { }
                }, constraints)
            }
            override fun onSetFailure(error: String?) {
                Timber.e("$TAG setRemoteDescription(offer) failed: $error")
                onError(IllegalStateException("setRemoteDescription(offer) failed: $error"))
            }
            override fun onCreateSuccess(p0: SessionDescription?) { }
            override fun onCreateFailure(p0: String?) { }
        }, SessionDescription(SessionDescription.Type.OFFER, remoteSdp))
    }

    private fun listenForAnswer() {
        answerListener = firestore
            .collection(Call.CALLS_COLLECTION).document(callID)
            .collection(SIGNALING_COLLECTION).document(ANSWER_DOC)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.e(error, "$TAG answer listener error")
                    return@addSnapshotListener
                }
                val sdp = snapshot?.getString("sdp") ?: return@addSnapshotListener
                if (snapshot.getString("senderID") == myUID) return@addSnapshotListener
                handleRemoteAnswer(sdp)
            }
    }

    private fun handleRemoteAnswer(remoteSdp: String) {
        val pc = peerConnection ?: return
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                flushPendingCandidates()
                Timber.d("$TAG remote answer set")
            }
            override fun onSetFailure(error: String?) {
                Timber.e("$TAG setRemoteDescription(answer) failed: $error")
                onError(IllegalStateException("setRemoteDescription(answer) failed: $error"))
            }
            override fun onCreateSuccess(p0: SessionDescription?) { }
            override fun onCreateFailure(p0: String?) { }
        }, SessionDescription(SessionDescription.Type.ANSWER, remoteSdp))
    }

    private fun writeSdpToFirestore(
        docID: String,
        sdp: SessionDescription,
        type: String,
        onSuccess: () -> Unit
    ) {
        firestore
            .collection(Call.CALLS_COLLECTION).document(callID)
            .collection(SIGNALING_COLLECTION).document(docID)
            .set(mapOf(
                "sdp" to sdp.description,
                "type" to type,
                "senderID" to myUID,
                "createdAt" to System.currentTimeMillis()
            ))
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e ->
                Timber.e(e, "$TAG $docID write failed")
                onError(e)
            }
    }

    // -------------------------------------------------------------------------
    // Signaling — ICE candidate exchange
    // -------------------------------------------------------------------------

    private fun sendIceCandidateToFirestore(candidate: IceCandidate) {
        // Path: calls/{callID}/signaling/candidates/items/{autoID}
        // "candidates" is a phantom partition doc; "items" is the actual
        // subcollection where each doc has a senderID field so the remote
        // side can filter to "only candidates from the OTHER user".
        firestore
            .collection(Call.CALLS_COLLECTION).document(callID)
            .collection(SIGNALING_COLLECTION).document(CANDIDATES_COLLECTION)
            .collection(CANDIDATES_SUBCOLLECTION)
            .add(mapOf(
                "sdp" to candidate.sdp,
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "senderID" to myUID,
                "createdAt" to System.currentTimeMillis()
            ))
            .addOnFailureListener { e: Exception ->
                Timber.e(e, "$TAG failed to write ICE candidate")
            }
    }

    private fun listenForRemoteIceCandidates() {
        remoteCandidatesListener = firestore
            .collection(Call.CALLS_COLLECTION).document(callID)
            .collection(SIGNALING_COLLECTION).document(CANDIDATES_COLLECTION)
            .collection(CANDIDATES_SUBCOLLECTION)
            .whereEqualTo("senderID", otherUserID)
            .addSnapshotListener { snapshot: com.google.firebase.firestore.QuerySnapshot?, error: Exception? ->
                if (error != null) {
                    Timber.e(error, "$TAG remote candidates listener error")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                for (change in snapshot.documentChanges) {
                    if (change.type != DocumentChange.Type.ADDED) continue
                    val doc = change.document
                    val sdp = doc.getString("sdp") ?: continue
                    val sdpMid = doc.getString("sdpMid")
                    val sdpMLineIndex =
                        (doc.get("sdpMLineIndex") as? Long)?.toInt() ?: 0
                    addRemoteCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
                }
            }
    }

    private fun addRemoteCandidate(candidate: IceCandidate) {
        val pc = peerConnection ?: return
        // Buffer until remote SDP is set (libwebrtc doesn't expose this directly,
        // so we track via a flag set by handleRemoteOffer / handleRemoteAnswer).
        if (!remoteDescriptionSet) {
            pendingRemoteCandidates.add(candidate)
            return
        }
        try { pc.addIceCandidate(candidate) }
        catch (e: Exception) { Timber.e(e, "$TAG addIceCandidate failed") }
    }

    @Volatile
    private var remoteDescriptionSet: Boolean = false

    private fun flushPendingCandidates() {
        val pc = peerConnection ?: return
        remoteDescriptionSet = true
        while (true) {
            val candidate = pendingRemoteCandidates.poll() ?: break
            try { pc.addIceCandidate(candidate) }
            catch (e: Exception) { Timber.e(e, "$TAG flush candidate failed") }
        }
    }

    // -------------------------------------------------------------------------
    // Media controls
    // -------------------------------------------------------------------------

    fun setMicrophoneEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
        _isMicrophoneEnabled.value = enabled
    }

    // -------------------------------------------------------------------------
    // Teardown
    // -------------------------------------------------------------------------

    fun end() {
        Timber.d("$TAG end()")
        offerListener?.remove(); offerListener = null
        answerListener?.remove(); answerListener = null
        remoteCandidatesListener?.remove(); remoteCandidatesListener = null

        try { localVideoTrack?.dispose() } catch (_: Exception) {}
        try { localVideoSource?.dispose() } catch (_: Exception) {}
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        try { localAudioTrack?.dispose() } catch (_: Exception) {}
        try { localAudioSource?.dispose() } catch (_: Exception) {}
        try { peerConnection?.close() } catch (_: Exception) {}
        try { peerConnection?.dispose() } catch (_: Exception) {}
        try { adm?.release() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        try { eglBase?.release() } catch (_: Exception) {}

        localVideoTrack = null
        localVideoSource = null
        videoCapturer = null
        localAudioTrack = null
        localAudioSource = null
        peerConnection = null
        adm = null
        factory = null
        eglBase = null

        try {
            firestore.collection(Call.CALLS_COLLECTION).document(callID)
                .collection(SIGNALING_COLLECTION).document(OFFER_DOC).delete()
            firestore.collection(Call.CALLS_COLLECTION).document(callID)
                .collection(SIGNALING_COLLECTION).document(ANSWER_DOC).delete()
            // Delete all ICE candidate items where senderID == myUID.
            firestore.collection(Call.CALLS_COLLECTION).document(callID)
                .collection(SIGNALING_COLLECTION).document(CANDIDATES_COLLECTION)
                .collection(CANDIDATES_SUBCOLLECTION)
                .whereEqualTo("senderID", myUID)
                .get()
                .addOnSuccessListener { snap ->
                    snap.documents.forEach { it.reference.delete() }
                }
        } catch (_: Exception) { }
    }

    private var adm: JavaAudioDeviceModule? = null
}
