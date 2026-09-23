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
            _otherUser.value = userRepo.getUserFromUID(userID)
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