package com.trigger.app.auth.ui.screens.enter_code

import android.app.Activity
import android.os.CountDownTimer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trigger.app.R
import com.trigger.app.auth.repo.AuthRepo
import com.trigger.app.auth.repo.AuthRepoImpl
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.repo.user.UserRepo
import com.trigger.app.core.repo.user.UserRepoImpl
import com.trigger.app.core.utils.formatDurationInMillis
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

class EnterCodeViewModel(
    private val authRepo: AuthRepo = AuthRepoImpl(),
    private val userRepo: UserRepo = UserRepoImpl()
) : ViewModel() {

    private val _code = MutableStateFlow("")
    val code: StateFlow<String> = _code

    private val _taskState = MutableStateFlow<TaskState>(TaskState.NONE)
    val taskState: StateFlow<TaskState> = _taskState

    private val _isCodeSent = MutableStateFlow(false)
    val isCodeSent: StateFlow<Boolean> = _isCodeSent

    private val _timeLeftInMillis = MutableStateFlow(SMS_TIMEOUT)
    val timeLeftInMillis: StateFlow<Long> = _timeLeftInMillis

    val formattedTimeLeft = timeLeftInMillis.map { it.formatDurationInMillis() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Has the SMS actually been delivered yet? UI uses this to show
     * "Sending SMS…" vs "Enter the code".
     */
    val isSendingSms: StateFlow<Boolean> = _taskState
        .map { it is TaskState.NONE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * The app moves to Chromes to verify if the user is real
     * Using isAuthenticating prevents double authentication
     */
    private var isAuthenticating = false


    companion object {
        const val CODE_LENGTH = 6

        const val SECOND_IN_MILLIS = 1000.toLong()
        const val SMS_TIMEOUT = 60 * SECOND_IN_MILLIS   // 60-second resend window (was 30)
    }




    fun updateCode(newCode: String) {
        _code.value = newCode
    }


    private fun startTimer() {
        _timeLeftInMillis.value = SMS_TIMEOUT

        val timer = object : CountDownTimer(SMS_TIMEOUT, SECOND_IN_MILLIS) {
            override fun onTick(millisUntilFinished: Long) {
                _timeLeftInMillis.value = millisUntilFinished
            }

            override fun onFinish() {
                isAuthenticating = false
            }
        }
        timer.start()
    }


    fun authenticateWithNumber(phoneNumber: String, activity: Activity?) {
        if (!isAuthenticating) {
            isAuthenticating = true
            startTimer()

            authRepo.authenticateWithNumber(
                phoneNumber = phoneNumber,
                activity = activity!!,
                onCodeSent = {
                    // SMS actually reached the user's phone — flip the UI.
                    _isCodeSent.value = true
                },
                onVerificationDone = { isSuccess ->
                    if (isSuccess)
                        _taskState.value = TaskState.DONE.SUCCESS
                    // If verification failed here, the user can still try submitting
                    // the SMS code manually; we don't blow away the UI.
                }
            )
        }
    }

    suspend fun checkIfUserHasExistingAccount(): Boolean {
        val existingUser = Firebase.auth.uid?.let { userRepo.getUserFromUID(it) }
        val isExistingAccount = existingUser != null
        return isExistingAccount
    }

    fun submitCode() {
        if (_taskState.value is TaskState.LOADING) return   // prevent double-submit

        _taskState.value = TaskState.LOADING()

        viewModelScope.launch {
            try {
                authRepo.submitSMSCode(code.value) { isSuccess ->
                    Timber.d("isSuccess in authRepo.submitSMSCode(code.value) is $isSuccess")

                    viewModelScope.launch {
                        if (isSuccess) {
                            _taskState.value = TaskState.DONE.SUCCESS
                        } else {
                            // Wrong OTP — keep the user on this screen so they can retry.
                            _taskState.value = TaskState.DONE.ERROR(R.string.invalid_code)
                            // Clear the wrong code so they can type a fresh one.
                            _code.value = ""
                        }
                    }
                }
            } catch (e: Exception) { // Thrown when the wrong OTP is entered
                _taskState.value = TaskState.DONE.ERROR(R.string.wrong_otp_entered)
                _code.value = ""
            }
        }
    }

    /**
     * Reset back to the "Enter the code" state — used by the UI after showing
     * the error snackbar so the user can retry. Does NOT trigger a re-send.
     */
    fun resetTaskState() {
        _taskState.value = TaskState.NONE
    }

}
