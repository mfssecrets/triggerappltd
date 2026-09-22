package com.trigger.app.settings.presentation.screens.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.repo.user_details.UserDetailsRepo
import com.trigger.app.core.repo.user_details.UserDetailsRepoImpl
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val userDetailsRepo: UserDetailsRepo = UserDetailsRepoImpl()
) : ViewModel() {

    val user = userDetailsRepo.getUserProfileFlow

    private val _taskState = MutableStateFlow<TaskState>(TaskState.NONE)
    val taskState: StateFlow<TaskState> = _taskState

    fun updateName(newName: String) = viewModelScope.launch {
        if (newName.isBlank()) return@launch

        val uid = Firebase.auth.uid ?: return@launch
        userDetailsRepo.updateUserName(uid, newName)
    }

    fun updateBio(newBio: String) = viewModelScope.launch {
        if (newBio.isBlank()) return@launch

        val uid = Firebase.auth.uid ?: return@launch
        userDetailsRepo.updateUserBio(uid, newBio)
    }

    /**
     * Upload the profile pic to Storage, then patch `users/{uid}` + `public_users/{uid}`
     * + every `chat_details` MiniUser reference with the resulting download URL.
     *
     * Uses `viewModelScope.launch` + `collect` (NOT `GlobalScope` + `collectLatest`)
     * because `collectLatest` cancels the upstream on every byte-progress emission —
     * which was cancelling the Storage upload mid-flight and the photo never finished
     * uploading. The previous code also had a magic `delay(1300)` to "buy time for
     * the upload to finish" — that was a band-aid for the cancellation race; removing
     * it now that we collect (not collectLatest).
     */
    fun updateProfilePic(localUri: Uri) {
        val uid = Firebase.auth.uid ?: return
        viewModelScope.launch {
            userDetailsRepo.updateUserProfilePic(uid, localUri).collect { task ->
                _taskState.value = task
            }
        }
    }
}
