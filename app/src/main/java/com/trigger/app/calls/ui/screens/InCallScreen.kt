package com.trigger.app.calls.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.trigger.app.calls.domain.CallStatus
import com.trigger.app.calls.ui.CallViewModel
import com.trigger.app.core.presentation.ui.components.UserIcon
import org.koin.androidx.compose.koinViewModel
import java.util.concurrent.TimeUnit

/**
 * In-call screen — both sides, after the callee has answered.
 *
 * Shows the active call UI: other user's avatar + name + duration timer +
 * control buttons (mute / speaker / hangup).
 *
 * NOTE: This is a "fake call" — there's no actual audio/video stream yet.
 * Both sides will see "00:05" ticking up + buttons working, but no audio.
 * Real WebRTC integration is a separate phase (see CallViewModel comments).
 *
 * Lifecycle:
 *   - Launched: callID + otherUserID passed in
 *   - On status=COMPLETED → pop (the hangup-er already wrote the duration)
 *   - Hangup button → calls hangupCall() → status=COMPLETED → pop
 */
@Composable
fun InCallScreen(
    callID: String,
    otherUserID: String,
    callType: String,
    navController: NavController
) {
    val viewModel: CallViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()

    // Live-updating duration display.
    var durationSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(state?.status, state?.startedAt) {
        val s = state ?: return@LaunchedEffect
        if (s.status == CallStatus.ANSWERED || s.status == CallStatus.COMPLETED) {
            while (true) {
                val now = System.currentTimeMillis()
                durationSeconds = (now - s.startedAt) / 1000
                kotlinx.coroutines.delay(1000)
                if (s.status == CallStatus.COMPLETED) break
            }
        }
    }

    // On COMPLETED/DECLINED/MISSED — pop the screen after a brief delay so
    // the user can see "Call ended".
    LaunchedEffect(state?.status) {
        when (state?.status) {
            CallStatus.COMPLETED, CallStatus.DECLINED, CallStatus.MISSED, CallStatus.FAILED -> {
                kotlinx.coroutines.delay(1500)
                navController.popBackStack()
            }
            else -> Unit
        }
    }

    val s = state
    if (s == null) {
        // Loading state — shouldn't normally happen since the call doc was
        // already loaded by OutgoingCallScreen / IncomingCallScreen.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Text("Loading call...", color = MaterialTheme.colorScheme.onBackground)
        }
        return
    }

    CallStatusScaffold(
        callType = callType,
        name = s.otherUserName.ifEmpty { "Call" },
        profilePic = s.otherUserProfilePic,
        statusText = formatDuration(durationSeconds),
        showSpinner = false,
        primaryButton = {
            // Four control buttons in a row.
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallIconButton(
                    icon = if (s.isMicrophoneMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    contentDescription = if (s.isMicrophoneMuted) "Unmute" else "Mute",
                    backgroundColor = if (s.isMicrophoneMuted) Color.Gray else MaterialTheme.colorScheme.surface,
                    iconTint = MaterialTheme.colorScheme.onSurface,
                    onClick = { viewModel.toggleMicrophone() }
                )
                CallIconButton(
                    icon = if (s.isSpeakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                    contentDescription = "Toggle speaker",
                    backgroundColor = if (s.isSpeakerOn) MaterialTheme.colorScheme.surface else Color.Gray,
                    iconTint = MaterialTheme.colorScheme.onSurface,
                    onClick = { viewModel.toggleSpeaker() }
                )
                // Hang up — big red button.
                CallIconButton(
                    icon = Icons.Rounded.CallEnd,
                    contentDescription = "Hang up",
                    backgroundColor = Color.Red,
                    iconTint = Color.White,
                    size = 64.dp,
                    onClick = { viewModel.hangupCall() }
                )
            }
        }
    )
}


private fun formatDuration(totalSeconds: Long): String {
    val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds)
    val seconds = totalSeconds - TimeUnit.MINUTES.toSeconds(minutes)
    return String.format("%02d:%02d", minutes, seconds)
}
