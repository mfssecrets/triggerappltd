package com.trigger.app.chats.presentation.chat_details.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trigger.app.core.domain.User
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.repo.moderation.ModerationRepo
import com.trigger.app.core.repo.moderation.ModerationRepoImpl
import com.trigger.app.core.repo.user.UserRepo
import com.trigger.app.core.repo.user.UserRepoImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class ChatDetailsViewModel(
    private val userRepo: UserRepo = UserRepoImpl(),
    private val moderationRepo: ModerationRepo = ModerationRepoImpl()
): ViewModel() {

    private val _otherUser = MutableStateFlow<User?>(null)
    val otherUser: StateFlow<User?> = _otherUser

    private val _blockState = MutableStateFlow<TaskState>(TaskState.NONE)
    val blockState: StateFlow<TaskState> = _blockState


    fun loadOtherUser(userID: String) {
        viewModelScope.launch {
            // FIX: use getPublicUserFromUID (reads from public_users/{uid},
            // signed-in-readable) instead of getUserFromUID (reads from
            // users/{uid}, owner-only — would throw PERMISSION_DENIED for
            // any other user → uncaught exception → app crashes).
            // Also wrap in try/catch so any future failure doesn't crash
            // the app — just shows "user not found" gracefully.
            _otherUser.value = try {
                userRepo.getPublicUserFromUID(userID)
            } catch (e: Exception) {
                Timber.e(e, "loadOtherUser: failed for userID=$userID")
                null
            }
        }
    }

    fun blockUser() = viewModelScope.launch {
        val user = otherUser.value ?: return@launch
        _blockState.value = TaskState.LOADING()
        runCatching { moderationRepo.blockUser(user) }
            .onSuccess { _blockState.value = TaskState.DONE.SUCCESS }
            .onFailure { _blockState.value = TaskState.DONE.ERROR(com.trigger.app.R.string.error_occurred) }
    }

}