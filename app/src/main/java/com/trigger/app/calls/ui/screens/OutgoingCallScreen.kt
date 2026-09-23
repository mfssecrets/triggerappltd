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
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
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
import org.koin.androidx.viewmodel.compose.androidxViewModel

/**
 * Outgoing call screen — caller side, waiting for callee to answer.
 *
 * Lifecycle:
 *   - Launched: callID + calleeID passed in via nav route
 *   - On status=ANSWERED → navigate to InCallScreen, pop this off stack
 *   - On status=DECLINED → show "Declined" briefly + pop
 *   - On status=MISSED → show "No answer" briefly + pop
 *   - On status=COMPLETED/FAILED → show "Call ended" + pop
 *   - Cancel button → calls declineOrCancelCall(MISSED) + pops
 */
@Composable
fun OutgoingCallScreen(
    callID: String,
    calleeID: String,
    callType: String,
    navController: NavController
) {
    val viewModel: CallViewModel = androidxViewModel()

    // Caller-side entry — create the call record (status=INITIATED) on first
    // composition. Idempotent: only fires once per callID (VM checks state).
    LaunchedEffect(callID) {
        if (viewModel.state.value == null) {
            viewModel.initiateCall(
                calleeID = calleeID,
                callType = CallType.fromFirebaseKey(callType)
            )
        }
    }

    val state by viewModel.state.collectAsState()

    // Watch status transitions + navigate on ANSWERED.
    LaunchedEffect(state?.status) {
        when (state?.status) {
            CallStatus.ANSWERED -> {
                // Both sides navigate to InCallScreen.
                val s = state ?: return@LaunchedEffect
                navController.navigateSafely(
                    InCall(callID = s.callID, otherUserID = s.otherUserID, callType = s.callType.firebaseKey)
                ) {
                    popUpTo(callID.hashCode()) { inclusive = true }
                }
            }
            CallStatus.DECLINED, CallStatus.MISSED, CallStatus.COMPLETED, CallStatus.FAILED -> {
                // Brief delay so user sees the "no answer" / "declined"
                // message before being popped back to the previous screen.
                kotlinx.coroutines.delay(1500)
                navController.popBackStack()
            }
            else -> Unit  // INITIATED — still ringing
        }
    }

    CallStatusScaffold(
        callType = callType,
        name = state?.otherUserName ?: "Calling...",
        profilePic = state?.otherUserProfilePic,
        statusText = when (state?.status) {
            CallStatus.INITIATED -> "Calling..."
            CallStatus.ANSWERED -> "Connecting..."
            CallStatus.DECLINED -> "Call declined"
            CallStatus.MISSED -> "No answer"
            CallStatus.FAILED -> "Call failed"
            CallStatus.COMPLETED -> "Call ended"
            else -> "Calling..."
        },
        showSpinner = state?.status == CallStatus.INITIATED || state?.status == CallStatus.ANSWERED,
        primaryButton = {
            // Cancel button — caller cancels → mark MISSED + pop.
            CallIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Cancel call",
                backgroundColor = Color.Red,
                onClick = { viewModel.declineOrCancelCall(CallStatus.MISSED) }
            )
        }
    )
}
