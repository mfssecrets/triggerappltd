package com.trigger.app.calls.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import timber.log.Timber

/**
 * Manages Android AudioManager state during a call:
 *   - Sets MODE_IN_COMMUNICATION (routes audio to earpiece / speaker /
 *     bluetooth headset based on what's connected).
 *   - Requests audio focus with AUDIOFOCUS_GAIN_TRANSIENT — pauses
 *     other apps' media playback during the call.
 *   - Toggles speakerphone on/off.
 *
 * Lifecycle:
 *   - onCallStart() → set MODE_IN_COMMUNICATION + request focus.
 *   - onCallEnd()   → restore MODE_NORMAL + abandon focus.
 */
class CallAudioManager(private val context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var audioFocusRequest: AudioFocusRequest? = null
    private var isSpeakerOn = false
    private var isMicrophoneMuted = false

    fun onCallStart() {
        try {
            // MODE_IN_COMMUNICATION tells the AudioManager to route audio
            // to the earpiece (default) or speaker (if enabled below).
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // Request audio focus so other apps (Spotify, YouTube, etc.)
            // pause their playback while the call is active.
            requestAudioFocus()

            // Default = earpiece (NOT speakerphone) for phone-call UX.
            audioManager.isSpeakerphoneOn = false
            isSpeakerOn = false

            Timber.d("CallAudioManager: onCallStart — MODE_IN_COMMUNICATION + focus requested")
        } catch (e: Exception) {
            Timber.e(e, "CallAudioManager: onCallStart failed")
        }
    }

    fun onCallEnd() {
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            isSpeakerOn = false
            abandonAudioFocus()
            Timber.d("CallAudioManager: onCallEnd — restored MODE_NORMAL")
        } catch (e: Exception) {
            Timber.e(e, "CallAudioManager: onCallEnd failed")
        }
    }

    fun setSpeakerOn(enabled: Boolean) {
        isSpeakerOn = enabled
        audioManager.isSpeakerphoneOn = enabled
        Timber.d("CallAudioManager: speaker=$enabled")
    }

    fun toggleSpeaker(): Boolean {
        setSpeakerOn(!isSpeakerOn)
        return isSpeakerOn
    }

    fun setMicrophoneMuted(muted: Boolean) {
        isMicrophoneMuted = muted
        audioManager.isMicrophoneMute = muted
        Timber.d("CallAudioManager: microphoneMuted=$muted")
    }

    fun toggleMicrophone(): Boolean {
        setMicrophoneMuted(!isMicrophoneMuted)
        return isMicrophoneMuted
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ).setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest?.let {
                audioManager.requestAudioFocus(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
