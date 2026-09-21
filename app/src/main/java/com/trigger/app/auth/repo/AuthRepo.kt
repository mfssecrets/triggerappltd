package com.trigger.app.auth.repo

import android.app.Activity

/**
 * Contains all methods used in authenticating {registering} the user
 */
interface AuthRepo {

    /**
     * Authenticates using the phone number provided.
     *
     * @param phoneNumber        E.164 formatted phone number (with country code).
     * @param activity           The calling Activity (required by Firebase for reCAPTCHA fallback).
     * @param onCodeSent         Invoked when Firebase has actually sent the SMS — the UI uses this to
     *                           switch out of the "Sending SMS…" state into "Enter the code".
     * @param onVerificationDone Invoked when verification finishes. `true` on success, `false` on
     *                           failure (e.g. invalid credential, auto-verify failure, quota exceeded).
     *
     * If the method had already been called, the stored forceResendingToken is used to resend the SMS.
     */
    fun authenticateWithNumber(
        phoneNumber: String,
        activity: Activity,
        onCodeSent: () -> Unit = {},
        onVerificationDone: (Boolean) -> Unit
    )


    /**
     * In case the user can not be automatically authenticated, the user need to submit the SMS code they've received
     *
     * @param smsCode - SMS code entered by user
     * @param onSignInComplete - (Boolean) is true if the sign in was successful
     */
    suspend fun submitSMSCode(smsCode: String, onSignInComplete: (Boolean) -> Unit)
}
