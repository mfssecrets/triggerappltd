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
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.trigger.app.calls.domain.CallStatus
import com.trigger.app.calls.domain.CallType
import com.trigger.app.calls.ui.CallViewModel
import com.trigger.app.core.presentation.ui.InCall
import com.trigger.app.core.presentation.ui.components.UserIcon
import com.trigger.app.core.presentation.ui.navigateSafely
import org.koin.androidx.compose.koinViewModel

/**
 * Incoming call screen — callee side.
 *
 * Triggered by TriggerMessagingService when an FCM with type="call" arrives.
 * The service launches this screen directly (fullScreenIntent on Android 14+,
 * plain Intent on older versions).
 *
 * Lifecycle:
 *   - Launched: callID + callerID passed in
 *   - Accept button → calls acceptCall() → status=ANSWERED → navigate to InCallScreen
 *   - Decline button → calls declineOrCancelCall(DECLINED) → pop
 *   - On status=MISSED (timeout) → show "Missed" briefly + pop
 *   - On status=COMPLETED/DECLINED → show "Call ended" briefly + pop
 */
@Composable
fun IncomingCallScreen(
    callID: String,
    callerID: String,
    callType: String,
    navController: NavController
) {
    val viewModel: CallViewModel = koinViewModel()

    // Callee-side entry — load the call doc + start listening.
    LaunchedEffect(callID) {
        if (viewModel.state.value == null) {
            viewModel.joinIncomingCall(
                callID = callID,
                callerID = callerID,
                callType = CallType.fromFirebaseKey(callType)
            )
        }
    }

    val state by viewModel.state.collectAsState()

    LaunchedEffect(state?.status) {
        when (state?.status) {
            CallStatus.ANSWERED -> {
                // Both sides navigate to InCallScreen.
                val s = state ?: return@LaunchedEffect
                navController.navigate(
                    InCall(callID = s.callID, otherUserID = s.otherUserID, callType = s.callType.firebaseKey)
                )
            }
            CallStatus.DECLINED, CallStatus.MISSED, CallStatus.COMPLETED, CallStatus.FAILED -> {
                kotlinx.coroutines.delay(1500)
                navController.popBackStack()
            }
            else -> Unit
        }
    }

    CallStatusScaffold(
        callType = callType,
        name = state?.otherUserName ?: "Incoming call",
        profilePic = state?.otherUserProfilePic,
        statusText = when (state?.status) {
            CallStatus.INITIATED -> "Incoming call"
            CallStatus.ANSWERED -> "Connecting..."
            CallStatus.MISSED -> "Missed call"
            CallStatus.DECLINED -> "Call declined"
            CallStatus.COMPLETED -> "Call ended"
            else -> "Incoming call"
        },
        showSpinner = false,
        primaryButton = {
            // Two buttons side-by-side: Accept (green) + Decline (red).
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                CallIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = "Decline",
                    backgroundColor = Color.Red,
                    onClick = { viewModel.declineOrCancelCall(CallStatus.DECLINED) }
                )
                CallIconButton(
                    icon = Icons.Rounded.Call,
                    contentDescription = "Accept",
                    backgroundColor = Color(0xFF4CAF50),  // green
                    onClick = { viewModel.acceptCall() }
                )
            }
        }
    )
}
