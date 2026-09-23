package com.trigger.app.chats.repo.unread_messages.models

import com.trigger.app.chats.domain.Message
import com.trigger.app.core.domain.MiniUser

data class UnreadMessages(
    val chatID: String,
    val currentMiniUser: MiniUser?,
    val otherMiniUser: MiniUser?,
    val listOfMessages: List<Message>
)
