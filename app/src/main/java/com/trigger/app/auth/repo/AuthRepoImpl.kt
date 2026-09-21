package com.trigger.app.auth.repo

import android.app.Activity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.concurrent.TimeUnit

class AuthRepoImpl : AuthRepo {

    private var storedVerificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null


    /**
     * Provides the verification callbacks needed to create PhoneAuthOptions instance
     *
     * @param onVerificationDone - (Boolean) is true if the verification was successful
     */
    private fun getVerificationCallbacks(
        onCodeSent: () -> Unit,
        onVerificationDone: (Boolean) -> Unit
    ) = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

        // Auto-verification (instant) — Firebase retrieved the SMS itself.
        override fun onVerificationCompleted(authCredential: PhoneAuthCredential) {
            Firebase.auth.signInWithCredential(authCredential).addOnCompleteListener { task ->
                Timber.d("task.isSuccessful is ${task.isSuccessful}")
                onVerificationDone(task.isSuccessful)
            }
        }

        override fun onVerificationFailed(firebaseException: FirebaseException) {
            Timber.e(firebaseException)
            onVerificationDone(false)
        }

        // SMS was actually delivered to the user's phone. Time to switch the UI
        // from "Sending…" to "Enter the code".
        override fun onCodeSent(
            verificationId: String,
            forceResendingToken: PhoneAuthProvider.ForceResendingToken
        ) {
            super.onCodeSent(verificationId, forceResendingToken)

            storedVerificationId = verificationId
            resendToken = forceResendingToken

            onCodeSent()
        }
    }

    override fun authenticateWithNumber(
        phoneNumber: String,
        activity: Activity,
        onCodeSent: () -> Unit,
        onVerificationDone: (Boolean) -> Unit
    ) {
        val phoneAuthOptions = PhoneAuthOptions.newBuilder()
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)         // 60-second Firebase timeout (was 30s)
            .setActivity(activity)
            .setCallbacks(getVerificationCallbacks(onCodeSent, onVerificationDone))
            .apply {
                resendToken?.let {
                    setForceResendingToken(it)
                }
            }
            .build()

        PhoneAuthProvider.verifyPhoneNumber(phoneAuthOptions)
    }


    override suspend fun submitSMSCode(smsCode: String, onSignInComplete: (Boolean) -> Unit) {
        storedVerificationId?.let {
            val authCredential = PhoneAuthProvider.getCredential(it, smsCode)

            Firebase.auth
                .signInWithCredential(authCredential)
                .addOnCompleteListener { task ->
                    Timber.d("task.isSuccessful is ${task.isSuccessful}")
                    onSignInComplete(task.isSuccessful)
                }
                .await()
        } ?: onSignInComplete(false)
    }
}
