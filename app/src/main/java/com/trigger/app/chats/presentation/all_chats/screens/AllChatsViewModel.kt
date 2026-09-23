package com.trigger.app.chats.presentation.all_chats.screens

import androidx.lifecycle.ViewModel
import com.trigger.app.chats.repo.chats.ChatRepo
import com.trigger.app.chats.repo.chats.ChatRepoImpl
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.trigger.app.local.ChatSyncRepository

class AllChatsViewModel(
    private val chatRepo: ChatRepo = ChatRepoImpl(),
    private val chatSyncRepository: ChatSyncRepository? = null
) : ViewModel() {

    // Offline-first: reads from Room cache (instant) with Firestore sync in background.
    // Falls back to direct Firestore flow if sync repo is null (backward compat).
    val chats = chatSyncRepository?.syncChats(Firebase.auth.uid ?: "")
        ?: chatRepo.getChatsForUser(Firebase.auth.uid)

}
