package com.trigger.app.auth.repo

import android.app.Activity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.concurrent.TimeUnit

class AuthRepoImpl : AuthRepo {

    // FIX #1: Lifecycle flag — when the VM is cleared, callbacks check this
    // before updating state. Prevents stale callbacks from a dead VM.
    @Volatile
    private var isActive = true

    fun cancel() {
        isActive = false
    }

    // FIX #5: No storedVerificationId or resendToken here — the VM holds the
    // VerificationSession. This fixes the "verificationId lost on VM recreation"
    // bug (the repo was default-constructed on VM recreation, losing the session).

    override fun authenticateWithNumber(
        phoneNumber: String,
        activity: Activity,
        onCodeSent: (verificationId: String, resendToken: PhoneAuthProvider.ForceResendingToken?) -> Unit,
        onResult: (success: Boolean, errorMessage: String?) -> Unit,
        resendToken: PhoneAuthProvider.ForceResendingToken?
    ) {
        val phoneAuthOptions = PhoneAuthOptions.newBuilder()
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(getVerificationCallbacks(onCodeSent, onResult))
            .apply {
                resendToken?.let { setForceResendingToken(it) }
            }
            .build()

        PhoneAuthProvider.verifyPhoneNumber(phoneAuthOptions)
    }

    private fun getVerificationCallbacks(
        onCodeSent: (verificationId: String, resendToken: PhoneAuthProvider.ForceResendingToken?) -> Unit,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

        // FIX #7: Auto-verification converges into the SAME onResult callback.
        // FIX #1: Check isActive before updating state (lifecycle-safe).
        // FIX #3: Use .await() in try-catch instead of addOnCompleteListener + await.
        override fun onVerificationCompleted(authCredential: PhoneAuthCredential) {
            if (!isActive) return  // VM is dead — don't update state
            kotlinx.coroutines.runBlocking {
                try {
                    Firebase.auth.signInWithCredential(authCredential).await()
                    Timber.d("onVerificationCompleted: success")
                    onResult(true, null)
                } catch (e: Exception) {
                    Timber.e(e, "onVerificationCompleted: failed")
                    onResult(false, mapFirebaseError(e))
                }
            }
        }

        // FIX #6: Map Firebase error codes to human-readable messages.
        override fun onVerificationFailed(firebaseException: FirebaseException) {
            if (!isActive) return  // VM is dead
            Timber.e(firebaseException)
            onResult(false, mapFirebaseError(firebaseException))
        }

        override fun onCodeSent(
            verificationId: String,
            forceResendingToken: PhoneAuthProvider.ForceResendingToken
        ) {
            if (!isActive) return  // VM is dead
            super.onCodeSent(verificationId, forceResendingToken)
            Timber.d("onCodeSent: verificationId=${verificationId.take(8)}...")
            onCodeSent(verificationId, forceResendingToken)
        }
    }

    // FIX #6: Explicit Firebase error code mapping.
    private fun mapFirebaseError(e: Exception): String {
        return when (e) {
            is FirebaseAuthException -> when (e.errorCode) {
                "ERROR_INVALID_PHONE_NUMBER" -> "Invalid phone number. Please check and try again."
                "ERROR_TOO_MANY_REQUESTS" -> "Too many verification attempts. Please try again later."
                "ERROR_QUOTA_EXCEEDED" -> "SMS quota exceeded. Please contact support."
                "ERROR_SESSION_EXPIRED" -> "Verification session expired. Please request a new code."
                "ERROR_INVALID_VERIFICATION_CODE" -> "Invalid verification code. Please check and try again."
                "ERROR_NETWORK_REQUEST_FAILED" -> "Network error. Please check your internet connection."
                "ERROR_APP_NOT_AUTHORIZED" -> "App not authorized for Firebase Authentication."
                "ERROR_CREDENTIAL_EXPIRED" -> "The verification credential has expired. Please request a new code."
                "ERROR_USER_DISABLED" -> "This account has been disabled. Please contact support."
                "ERROR_OPERATION_NOT_ALLOWED" -> "Phone authentication is not enabled for this project."
                // FIX #2: Log the raw Firebase code, show generic message to user.
                else -> {
                    Timber.e("Unmapped FirebaseAuthException: ${e.errorCode}")
                    "Something went wrong. Please try again."
                }
            }
            else -> "Error: ${e.message ?: "unknown error"}"
        }
    }

    // FIX #3: Clean coroutine-based — no addOnCompleteListener + await mix.
    // FIX #1: Check isActive before returning result.
    override suspend fun submitSMSCode(
        smsCode: String,
        verificationId: String,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        if (!isActive) return  // VM is dead
        try {
            val authCredential = PhoneAuthProvider.getCredential(verificationId, smsCode)
            Firebase.auth.signInWithCredential(authCredential).await()
            Timber.d("submitSMSCode: success")
            if (isActive) onResult(true, null)
        } catch (e: Exception) {
            Timber.e(e, "submitSMSCode: failed")
            if (isActive) onResult(false, mapFirebaseError(e))
        }
    }
}
