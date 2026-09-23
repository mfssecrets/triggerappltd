package com.trigger.app.auth.repo

import android.app.Activity
import com.google.firebase.auth.PhoneAuthProvider

/**
 * Contains all methods used in authenticating {registering} the user.
 *
 * FIX #2: Activity is non-nullable (was Activity? + activity!!).
 * FIX #5: submitSMSCode takes verificationId from the caller (was stored in repo).
 * FIX #6: Error callback passes a human-readable message (was Boolean false).
 * FIX #7: Auto-verification and manual OTP converge into the same onResult callback.
 */
interface AuthRepo {

    /**
     * Authenticates using the phone number provided.
     *
     * @param phoneNumber    E.164 formatted phone number (with country code).
     * @param activity       The calling Activity (non-nullable — Firebase requires it for reCAPTCHA).
     * @param onCodeSent     Invoked when Firebase sends the SMS. Passes the verificationId +
     *                       resendToken so the VM can store them in a VerificationSession.
     * @param onResult       Invoked on success or failure. (true, null) = success.
     *                       (false, errorMessage) = failure with human-readable message.
     * @param resendToken    Optional — if provided, Firebase uses it to resend the SMS
     *                       (avoids consuming the daily SMS quota).
     */
    fun authenticateWithNumber(
        phoneNumber: String,
        activity: Activity,
        onCodeSent: (verificationId: String, resendToken: PhoneAuthProvider.ForceResendingToken?) -> Unit,
        onResult: (success: Boolean, errorMessage: String?) -> Unit,
        resendToken: PhoneAuthProvider.ForceResendingToken? = null
    )

    /**
     * Submits the SMS code the user typed. Uses the verificationId from the
     * caller's VerificationSession (NOT stored in the repo).
     *
     * @param smsCode        6-digit code entered by user.
     * @param verificationId The Firebase verificationId from the VerificationSession.
     * @param onResult       (true, null) = success. (false, errorMessage) = failure.
     */
    suspend fun submitSMSCode(
        smsCode: String,
        verificationId: String,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    )
}
