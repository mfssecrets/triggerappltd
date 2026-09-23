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

        // FIX #7: Auto-verification converges into the SAME onResult callback
        // as manual OTP. No separate auth path. Both call signInWithCredential
        // → onResult(true, null) or onResult(false, errorMessage).
        override fun onVerificationCompleted(authCredential: PhoneAuthCredential) {
            Firebase.auth.signInWithCredential(authCredential).addOnCompleteListener { task ->
                Timber.d("onVerificationCompleted: task.isSuccessful=${task.isSuccessful}")
                if (task.isSuccessful) {
                    onResult(true, null)
                } else {
                    val msg = task.exception?.let { mapFirebaseError(it) } ?: "Auto-verification failed"
                    onResult(false, msg)
                }
            }
        }

        // FIX #6: Map Firebase error codes to human-readable messages.
        override fun onVerificationFailed(firebaseException: FirebaseException) {
            Timber.e(firebaseException)
            val message = mapFirebaseError(firebaseException)
            onResult(false, message)
        }

        override fun onCodeSent(
            verificationId: String,
            forceResendingToken: PhoneAuthProvider.ForceResendingToken
        ) {
            super.onCodeSent(verificationId, forceResendingToken)
            Timber.d("onCodeSent: verificationId=${verificationId.take(8)}...")
            // Pass verificationId + resendToken to the VM so it can store them
            // in a VerificationSession.
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
                else -> "Authentication error: ${e.errorCode}"
            }
            else -> "Error: ${e.message ?: "unknown error"}"
        }
    }

    // FIX #5: Takes verificationId from the caller's VerificationSession.
    // No longer reads from a stored field that gets lost on VM recreation.
    override suspend fun submitSMSCode(
        smsCode: String,
        verificationId: String,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        try {
            val authCredential = PhoneAuthProvider.getCredential(verificationId, smsCode)
            Firebase.auth
                .signInWithCredential(authCredential)
                .addOnCompleteListener { task ->
                    Timber.d("submitSMSCode: task.isSuccessful=${task.isSuccessful}")
                    if (task.isSuccessful) {
                        onResult(true, null)
                    } else {
                        val msg = task.exception?.let { mapFirebaseError(it) } ?: "Verification failed"
                        onResult(false, msg)
                    }
                }
                .await()
        } catch (e: Exception) {
            // Thrown when the wrong OTP is entered (e.g. FirebaseAuthInvalidCredentialsException)
            val msg = mapFirebaseError(e)
            onResult(false, msg)
        }
    }
}
