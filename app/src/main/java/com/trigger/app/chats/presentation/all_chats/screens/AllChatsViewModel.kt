package com.trigger.app.chats.presentation.all_chats.screens

import androidx.lifecycle.ViewModel
import com.trigger.app.chats.repo.chats.ChatRepo
import com.trigger.app.chats.repo.chats.ChatRepoImpl
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class AllChatsViewModel(
    private val chatRepo: ChatRepo = ChatRepoImpl()
) : ViewModel() {

    val chats = chatRepo.getChatsForUser(Firebase.auth.uid)

}