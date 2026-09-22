package com.trigger.app.chats.presentation.actual_chat.screens.send_image

import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trigger.app.chats.repo.chats.ChatRepo
import com.trigger.app.chats.repo.chats.ChatRepoImpl
import com.trigger.app.chats.repo.messages.MessagesRepo
import com.trigger.app.chats.repo.messages.MessagesRepoImpl
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.presentation.ui.SendImageIn
import com.trigger.app.core.repo.user.UserRepo
import com.trigger.app.core.repo.user.UserRepoImpl
import com.trigger.app.stories.repo.StoryRepo
import com.trigger.app.stories.repo.StoryRepoImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class SendImageViewModel(
    private val messagesRepo: MessagesRepo = MessagesRepoImpl(ChatRepoImpl()),
    private val userRepo: UserRepo = UserRepoImpl(),
    private val chatRepo: ChatRepo = ChatRepoImpl(),
    private val storyRepo: StoryRepo = StoryRepoImpl(userRepo, chatRepo)
) : ViewModel() {

    private val _typedMessage = MutableStateFlow(TextFieldValue(""))
    val typedMessage: StateFlow<TextFieldValue> = _typedMessage

    private val _sendImageState = MutableStateFlow<TaskState>(TaskState.NONE)
    val sendImageState: StateFlow<TaskState> = _sendImageState

    fun updateMessage(newMessage: TextFieldValue) {
        _typedMessage.value = newMessage
    }

    fun sendMessage(imageUri: String, sendImageIn: SendImageIn) = viewModelScope.launch {
        if (sendImageState.value is TaskState.LOADING) return@launch

        _sendImageState.value = TaskState.LOADING()

        try {
            if (sendImageIn is SendImageIn.Chat) {
                // Send image as a chat message
                sendImageIn.chatId?.let { chatID ->
                    messagesRepo.sendImageMessage(chatID, imageUri, typedMessage.value.text)
                }
            } else {
                // Post to the user's story. The new signature is
                // `postStory(localImageUri, storyCaption): String?` — no
                // `currentUserID` parameter (the impl reads Firebase.auth.uid
                // as the single source of truth).
                val newStoryID = storyRepo.postStory(
                    localImageUri = imageUri.toUri(),
                    storyCaption = typedMessage.value.text
                )
                if (newStoryID == null) {
                    _sendImageState.value = TaskState.DONE.ERROR(
                        com.trigger.app.R.string.error_occurred
                    )
                    return@launch
                }
            }
            _sendImageState.value = TaskState.DONE.SUCCESS
        } catch (e: Exception) {
            Timber.e(e, "sendMessage: failed")
            _sendImageState.value = TaskState.DONE.ERROR(
                com.trigger.app.R.string.error_occurred
            )
        }
    }

    fun resetSendState() {
        _sendImageState.value = TaskState.NONE
    }
}
