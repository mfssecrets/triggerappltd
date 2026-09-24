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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

class SelectContactsViewModel(
    private val contactsRepo: ContactsRepo
) : ViewModel() {

    val contactsOnTriggerApp = contactsRepo.contactsOnTriggerApp
    val inviteToTriggerAppList = mutableStateListOf<User>()

    private val _shouldNavigateToActualChat = MutableStateFlow<ActualChat?>(null)
    val shouldNavigateToActualChat: StateFlow<ActualChat?> = _shouldNavigateToActualChat

    private val _usernameSearchResult = MutableStateFlow<User?>(null)
    val usernameSearchResult: StateFlow<User?> = _usernameSearchResult

    /**
     * Status of the username search — drives the UI feedback below the
     * search field so the user knows what's happening:
     *   IDLE        → no search yet (initial state OR field cleared)
     *   TOO_SHORT   → username < 5 chars (rule violation)
     *   SEARCHING   → 350ms debounce running OR Firestore call in-flight
     *   FOUND       → username found, ContactPreview shown
     *   NOT_FOUND   → username doesn't exist (or it's the current user's own)
     *   ERROR       → network / permission error during search
     */
    enum class SearchStatus { IDLE, TOO_SHORT, SEARCHING, FOUND, NOT_FOUND, ERROR }

    private val _searchStatus = MutableStateFlow(SearchStatus.IDLE)
    val searchStatus: StateFlow<SearchStatus> = _searchStatus

    private var usernameSearchJob: Job? = null

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

    fun searchByUsername(value: String) {
        val username = value.removePrefix("@").lowercase()
        usernameSearchJob?.cancel()

        // Empty input → reset to IDLE.
        if (username.isBlank()) {
            _usernameSearchResult.value = null
            _searchStatus.value = SearchStatus.IDLE
            return
        }

        // Username must be at least 5 chars (Firestore rules require this).
        if (username.length < 5) {
            _usernameSearchResult.value = null
            _searchStatus.value = SearchStatus.TOO_SHORT
            return
        }

        // Clear previous result + mark as searching during the 350ms debounce
        // + the network call.
        _usernameSearchResult.value = null
        _searchStatus.value = SearchStatus.SEARCHING

        usernameSearchJob = viewModelScope.launch {
            delay(350)  // debounce — avoids spamming Firestore on every keystroke
            try {
                val result = contactsRepo.searchUserByUsername(username)
                if (result == null) {
                    // Could be: (a) username doesn't exist, (b) it's the current
                    // user's own username (intentionally hidden — can't chat with
                    // yourself). Either way, NOT_FOUND.
                    _searchStatus.value = SearchStatus.NOT_FOUND
                } else {
                    _usernameSearchResult.value = result
                    _searchStatus.value = SearchStatus.FOUND
                }
            } catch (e: Exception) {
                Timber.e(e, "searchByUsername: failed for username=$username")
                _searchStatus.value = SearchStatus.ERROR
            }
        }
    }

    fun resetShouldNavigateToActualChat() {
        _shouldNavigateToActualChat.value = null
    }
}
