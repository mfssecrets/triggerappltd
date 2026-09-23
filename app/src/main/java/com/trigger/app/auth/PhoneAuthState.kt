package com.trigger.app.auth

import com.google.firebase.auth.PhoneAuthProvider

// =====================================================================
// FIX #1: Sealed interface replacing multiple booleans + TaskState.
// Eliminates contradictory UI states (e.g. isCodeSent=true + taskState=ERROR).
// =====================================================================

sealed interface PhoneAuthState {
    data object Idle : PhoneAuthState
    data object SendingCode : PhoneAuthState
    data class CodeSent(val verificationId: String) : PhoneAuthState
    data object VerifyingCode : PhoneAuthState
    data object Success : PhoneAuthState
    data class Error(val message: String) : PhoneAuthState
}

// =====================================================================
// FIX #5: VerificationSession — preserves Firebase verification state
// (verificationId + resendToken) in the ViewModel, NOT the repo.
// Survives config changes because the VM survives. Was lost when the
// repo was default-constructed on VM recreation.
// =====================================================================

data class VerificationSession(
    val verificationId: String,
    val resendToken: PhoneAuthProvider.ForceResendingToken?
)
