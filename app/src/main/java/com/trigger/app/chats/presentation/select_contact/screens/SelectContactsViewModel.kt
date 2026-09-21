package com.trigger.app.chats.presentation.select_contact.screens

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trigger.app.chats.repo.contacts.ContactsRepo
import com.trigger.app.core.domain.User
import com.trigger.app.core.presentation.ui.ActualChat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class SelectContactsViewModel(
    private val contactsRepo: ContactsRepo
) : ViewModel() {

    val contactsOnTriggerApp = contactsRepo.contactsOnTriggerApp
    val inviteToTriggerAppList = mutableStateListOf<User>()

    private val _shouldNavigateToActualChat = MutableStateFlow<ActualChat?>(null)
    val shouldNavigateToActualChat: StateFlow<ActualChat?> = _shouldNavigateToActualChat

    /**
     * If the user selects someone they are already talking with, return them to their ongoing coveration
     * { in either scenario, make sure the NavStack is popped to the start }
     */
    fun startOrResumeConversation(newContact: User) = viewModelScope.launch {
        val existingChatID = contactsRepo.checkForPreExistingChat(newContact)
        Timber.d("startOrResumeConversation.existingChatID is $existingChatID")

        _shouldNavigateToActualChat.value = if (existingChatID != null)
            ActualChat(chatId = existingChatID, newContact = null)
        else
            ActualChat(chatId = null, newContact = newContact.uid)
    }

    suspend fun fetchContactsOnTriggerApp(context: Context) {
        contactsRepo.refreshContactsOnTriggerApp(context)
    }


    fun resetShouldNavigateToActualChat() {
        _shouldNavigateToActualChat.value = null
    }
}