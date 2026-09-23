package com.trigger.app.auth.ui.screens.enter_code

import android.app.Activity
import android.os.CountDownTimer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trigger.app.R
import com.trigger.app.auth.PhoneAuthState
import com.trigger.app.auth.VerificationSession
import com.trigger.app.auth.repo.AuthRepo
import com.trigger.app.auth.repo.AuthRepoImpl
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.repo.user.UserRepo
import com.trigger.app.core.repo.user.UserRepoImpl
import com.trigger.app.core.utils.formatDurationInMillis
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

class EnterCodeViewModel(
    private val authRepo: AuthRepo = AuthRepoImpl(),
    private val userRepo: UserRepo = UserRepoImpl()
) : ViewModel() {

    // FIX #1: Single sealed state replaces isAuthenticating + isCodeSent + taskState.
    private val _authState = MutableStateFlow<PhoneAuthState>(PhoneAuthState.Idle)
    val authState: StateFlow<PhoneAuthState> = _authState

    // FIX #4: OTP is strictly 6 digits — filter non-digits + take(6).
    private val _code = MutableStateFlow("")
    val code: StateFlow<String> = _code

    // FIX #8: Resend countdown is SEPARATE from auth state.
    private val _resendCountdown = MutableStateFlow(SMS_TIMEOUT)
    val resendCountdown: StateFlow<Long> = _resendCountdown
    val formattedCountdown = _resendCountdown.map { it.formatDurationInMillis() }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "")

    // FIX #5: VerificationSession held in the VM (survives config changes).
    private var verificationSession: VerificationSession? = null

    private var countDownTimer: CountDownTimer? = null

    companion object {
        const val CODE_LENGTH = 6
        const val SECOND_IN_MILLIS = 1000.toLong()
        const val SMS_TIMEOUT = 60 * SECOND_IN_MILLIS
    }

    // FIX #4: Filter to digits-only + take(6).
    fun updateCode(newCode: String) {
        _code.value = newCode.filter(Char::isDigit).take(6)
    }

    // FIX #8: Timer only updates resendCountdown — doesn't touch authState.
    private fun startTimer() {
        countDownTimer?.cancel()
        _resendCountdown.value = SMS_TIMEOUT
        countDownTimer = object : CountDownTimer(SMS_TIMEOUT, SECOND_IN_MILLIS) {
            override fun onTick(millisUntilFinished: Long) {
                _resendCountdown.value = millisUntilFinished
            }
            override fun onFinish() {
                _resendCountdown.value = 0
            }
        }.also { it.start() }
    }

    fun authenticateWithNumber(phoneNumber: String, activity: Activity?) {
        // FIX #2: Null-safe — no more activity!!
        val act = activity ?: run {
            _authState.value = PhoneAuthState.Error("Activity not available")
            return
        }

        _authState.value = PhoneAuthState.SendingCode
        startTimer()

        // Pass the existing resendToken (if any) so Firebase reuses the session.
        val token = verificationSession?.resendToken
        authRepo.authenticateWithNumber(
            phoneNumber = phoneNumber,
            activity = act,
            onCodeSent = { verificationId, resendToken ->
                // FIX #5: Store the session in the VM.
                verificationSession = VerificationSession(verificationId, resendToken)
                _authState.value = PhoneAuthState.CodeSent(verificationId)
            },
            onResult = { success, errorMessage ->
                // FIX #7: Auto-verification + manual OTP converge here.
                _authState.value = if (success) {
                    PhoneAuthState.Success
                } else {
                    PhoneAuthState.Error(errorMessage ?: "Verification failed")
                }
            },
            resendToken = token
        )
    }

    fun submitCode() {
        if (_authState.value is PhoneAuthState.VerifyingCode) return
        val smsCode = _code.value
        if (smsCode.length != CODE_LENGTH) return

        val session = verificationSession ?: run {
            _authState.value = PhoneAuthState.Error("No verification session. Please request a new code.")
            return
        }

        _authState.value = PhoneAuthState.VerifyingCode

        viewModelScope.launch {
            authRepo.submitSMSCode(
                smsCode = smsCode,
                verificationId = session.verificationId,
                onResult = { success, errorMessage ->
                    _authState.value = if (success) {
                        PhoneAuthState.Success
                    } else {
                        // Clear the code on error so the user can type a fresh one.
                        _code.value = ""
                        PhoneAuthState.Error(errorMessage ?: "Invalid code. Please try again.")
                    }
                }
            )
        }
    }

    suspend fun checkIfUserHasExistingAccount(): Boolean {
        val existingUser = Firebase.auth.uid?.let { userRepo.getUserFromUID(it) }
        return existingUser != null
    }

    fun resetAuthState() {
        _authState.value = PhoneAuthState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        countDownTimer?.cancel()
        // FIX #1: Cancel in-flight Firebase callbacks (lifecycle-safe).
        authRepo.cancel()
    }
}
