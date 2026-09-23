package com.trigger.app.auth.repo

import android.app.Activity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * FIX (aba6585 follow-up): Removed `runBlocking` from onVerificationCompleted().
 * The Firebase callback now launches its signInWithCredential coroutine on the
 * caller-supplied [coroutineScope] (the VM's viewModelScope). When the VM is
 * cleared, the scope is cancelled → the in-flight signIn coroutine is cancelled
 * → no stale state updates and no thread blocking.
 *
 * Koin scope note: AuthRepoImpl is registered as a `factory` (NOT `single`) in
 * RepoModule.kt. Each VM gets its own repo instance → the `isActive` flag is
 * correctly scoped to the VM. If anyone refactors to inject this via Koin,
 * `factory` keeps the per-VM lifecycle intact. (Even though EnterCodeViewModel
 * currently uses the default-arg constructor, the `factory` registration is
 * the safe-by-default choice.)
 */
class AuthRepoImpl : AuthRepo {

    // Lifecycle flag — when the VM is cleared, callbacks check this before
    // updating state. Belt-and-suspenders with scope cancellation: even if
    // the scope is cancelled mid-flight, the isActive check guards the
    // onResult lambda call.
    @Volatile
    private var isActive = true

    fun cancel() {
        isActive = false
    }

    override fun authenticateWithNumber(
        phoneNumber: String,
        activity: Activity,
        coroutineScope: CoroutineScope,
        onCodeSent: (verificationId: String, resendToken: PhoneAuthProvider.ForceResendingToken?) -> Unit,
        onResult: (success: Boolean, errorMessage: String?) -> Unit,
        resendToken: PhoneAuthProvider.ForceResendingToken?
    ) {
        val phoneAuthOptions = PhoneAuthOptions.newBuilder()
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(getVerificationCallbacks(coroutineScope, onCodeSent, onResult))
            .apply {
                resendToken?.let { setForceResendingToken(it) }
            }
            .build()

        PhoneAuthProvider.verifyPhoneNumber(phoneAuthOptions)
    }

    private fun getVerificationCallbacks(
        coroutineScope: CoroutineScope,
        onCodeSent: (verificationId: String, resendToken: PhoneAuthProvider.ForceResendingToken?) -> Unit,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

        // Auto-verification path: Firebase already has the credential (e.g. instant
        // verification on some devices / SMS retriever API). We must call
        // signInWithCredential ourselves to complete the auth.
        //
        // CRITICAL: This is a Firebase callback running on Firebase's thread.
        // Do NOT runBlocking. Instead, launch a coroutine on the caller's scope
        // (viewModelScope). When the VM is cleared, the scope is cancelled and
        // this coroutine is cancelled — no stale onResult invocation.
        override fun onVerificationCompleted(authCredential: PhoneAuthCredential) {
            if (!isActive) {
                Timber.d("onVerificationCompleted: repo already cancelled, ignoring")
                return
            }
            coroutineScope.launch {
                if (!isActive) return@launch
                try {
                    Firebase.auth.signInWithCredential(authCredential).await()
                    Timber.d("onVerificationCompleted: signIn success")
                    if (isActive) onResult(true, null)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Scope was cancelled (VM cleared). This is expected — don't
                    // call onResult, the VM is dead anyway.
                    Timber.d("onVerificationCompleted: coroutine cancelled (VM cleared)")
                    throw e  // re-throw to respect cancellation
                } catch (e: Exception) {
                    Timber.e(e, "onVerificationCompleted: signIn failed")
                    if (isActive) onResult(false, mapFirebaseError(e))
                }
            }
        }

        override fun onVerificationFailed(firebaseException: FirebaseException) {
            if (!isActive) return
            Timber.e(firebaseException)
            onResult(false, mapFirebaseError(firebaseException))
        }

        override fun onCodeSent(
            verificationId: String,
            forceResendingToken: PhoneAuthProvider.ForceResendingToken
        ) {
            if (!isActive) return
            super.onCodeSent(verificationId, forceResendingToken)
            Timber.d("onCodeSent: verificationId=${verificationId.take(8)}...")
            onCodeSent(verificationId, forceResendingToken)
        }
    }

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
                // Log the raw Firebase code; show generic message to user.
                else -> {
                    Timber.e("Unmapped FirebaseAuthException: ${e.errorCode}")
                    "Something went wrong. Please try again."
                }
            }
            else -> {
                Timber.e(e, "Non-FirebaseAuthException during auth")
                "Something went wrong. Please try again."
            }
        }
    }

    override suspend fun submitSMSCode(
        smsCode: String,
        verificationId: String,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        if (!isActive) return
        try {
            val authCredential = PhoneAuthProvider.getCredential(verificationId, smsCode)
            Firebase.auth.signInWithCredential(authCredential).await()
            Timber.d("submitSMSCode: success")
            if (isActive) onResult(true, null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            Timber.d("submitSMSCode: coroutine cancelled (VM cleared)")
            throw e
        } catch (e: Exception) {
            Timber.e(e, "submitSMSCode: failed")
            if (isActive) onResult(false, mapFirebaseError(e))
        }
    }
}
