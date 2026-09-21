package com.trigger.app.auth.ui.screens.create_username

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trigger.app.R
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.repo.user.UserRepo
import com.trigger.app.core.repo.user.UserRepoImpl
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class UsernameAvailability {
    UNKNOWN,
    CHECKING,
    AVAILABLE,
    TAKEN,
    INVALID
}

class CreateUsernameViewModel(
    private val userRepo: UserRepo = UserRepoImpl()
) : ViewModel() {
    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username

    private val _availability = MutableStateFlow(UsernameAvailability.UNKNOWN)
    val availability: StateFlow<UsernameAvailability> = _availability

    private val _taskState = MutableStateFlow<TaskState>(TaskState.NONE)
    val taskState: StateFlow<TaskState> = _taskState

    private var availabilityJob: Job? = null

    fun updateUsername(value: String) {
        val normalized = value.removePrefix("@").lowercase()
        _username.value = normalized
        availabilityJob?.cancel()

        if (!isValidUsername(normalized)) {
            _availability.value = if (normalized.isBlank()) UsernameAvailability.UNKNOWN else UsernameAvailability.INVALID
            return
        }

        _availability.value = UsernameAvailability.CHECKING
        availabilityJob = viewModelScope.launch {
            delay(350)
            _availability.value = try {
                if (userRepo.isUsernameAvailable(normalized))
                    UsernameAvailability.AVAILABLE
                else
                    UsernameAvailability.TAKEN
            } catch (_: Exception) {
                UsernameAvailability.UNKNOWN
            }
        }
    }

    fun createUserProfile(
        name: String,
        bio: String,
        phoneNumber: String,
        profilePic: String?
    ) {
        if (_availability.value != UsernameAvailability.AVAILABLE) return
        _taskState.value = TaskState.LOADING()
        viewModelScope.launch {
            userRepo.createUser(
                name = name,
                bio = bio,
                username = username.value,
                phoneNumber = phoneNumber,
                profilePicLocalUri = profilePic?.let(Uri::parse),
                onComplete = { _taskState.value = it }
            )
        }
    }

    companion object {
        private val USERNAME_PATTERN = Regex("^[a-z0-9_]{5,24}$")

        fun isValidUsername(value: String) = USERNAME_PATTERN.matches(value)
    }
}