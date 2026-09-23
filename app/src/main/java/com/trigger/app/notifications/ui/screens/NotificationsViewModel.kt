package com.trigger.app.notifications.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.toObject
import com.google.firebase.ktx.Firebase
import com.trigger.app.chats.domain.Chat
import com.trigger.app.chats.repo.chats.ChatRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Notifications feed — live list of unread chats with new messages.
 *
 * For now this is a simple implementation: snapshots `chat_details` where the
 * current user is a participant AND `unreadMessagesCount > 0`, ordered by
 * timeOfLastMessage descending. Each item renders as:
 *   [avatar] name  "last message preview"
 *   Tap → opens the ActualChat for that chatID.
 *
 * Future enhancements (out of scope for this fix):
 *  - Missed calls row
 *  - Story replies
 *  - Message requests count badge
 *  - Mark-as-read on tap
 */
class NotificationsViewModel(
    private val chatRepo: ChatRepo
) : ViewModel() {

    data class NotificationItem(
        val chatId: String,
        val senderName: String,
        val senderProfilePic: String?,
        val lastMessagePreview: String,
        val unreadCount: Int,
        val timestamp: Long?
    )

    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val notifications: StateFlow<List<NotificationItem>> = _notifications

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        startListening()
    }

    private fun startListening() {
        val uid = Firebase.auth.uid ?: run {
            _isLoading.value = false
            return
        }

        viewModelScope.launch {
            try {
                // Snapshot chats where current user is first OR second participant
                // AND there are unread messages for them.
                //
                // Firestore supports `where` Filter.or — used to query both sides
                // of the chat partnership in a single round-trip.
                val firestore = Firebase.firestore
                val firstUserFilter = Filter.equalTo(
                    "firstMiniUser.uid", uid
                )
                val secondUserFilter = Filter.equalTo(
                    "secondMiniUser.uid", uid
                )

                firestore.collection(ChatRepo.CHAT_DETAILS)
                    .where(Filter.or(
                        firstUserFilter, secondUserFilter
                    ))
                    .orderBy("timeOfLastMessage", Query.Direction.DESCENDING)
                    .addSnapshotListener { snapshot, error ->
                        _isLoading.value = false
                        if (error != null) {
                            Timber.e(error, "NotificationsViewModel: snapshot listener error")
                            return@addSnapshotListener
                        }
                        if (snapshot == null) {
                            _notifications.value = emptyList()
                            return@addSnapshotListener
                        }

                        val items = snapshot.documents.mapNotNull { doc ->
                            try {
                                val chat = doc.toObject<Chat>() ?: return@mapNotNull null
                                if (chat.isDisabled) return@mapNotNull null

                                // Figure out which side is the current user — the
                                // OTHER side is whose name + pic we display.
                                val isCurrentUserFirst = chat.firstMiniUser.uid == uid
                                val otherUser = if (isCurrentUserFirst)
                                    chat.secondMiniUser else chat.firstMiniUser

                                // Only show chats with unread > 0 (the user has
                                // notifications for unread msgs only — read chats
                                // aren't notifications).
                                if (chat.unreadMessagesCount <= 0) return@mapNotNull null

                                val preview = buildMessagePreview(chat)
                                NotificationItem(
                                    chatId = chat.chatID,
                                    senderName = otherUser.name,
                                    senderProfilePic = otherUser.profilePic,
                                    lastMessagePreview = preview,
                                    unreadCount = chat.unreadMessagesCount,
                                    timestamp = chat.timeOfLastMessage
                                )
                            } catch (e: Exception) {
                                Timber.e(e, "NotificationsViewModel: parse failure for doc ${doc.id}")
                                null
                            }
                        }

                        _notifications.value = items
                    }
            } catch (e: Exception) {
                Timber.e(e, "NotificationsViewModel: failed to set up snapshot listener")
                _isLoading.value = false
                _notifications.value = emptyList()
            }
        }
    }

    /**
     * Build a short preview string from the chat's lastMessageType map. Returns
     * "New message" as a generic fallback if parsing fails.
     */
    private fun buildMessagePreview(chat: Chat): String {
        return try {
            val typeMap = chat.lastMessageType
            val type = typeMap["type"] as? String ?: return "New message"
            val msg = typeMap["message"] as? String ?: ""
            when (type) {
                "Text" -> if (msg.length > 80) msg.substring(0, 77) + "..." else msg.ifEmpty { "New message" }
                "Image" -> "Photo"
                "Audio" -> "Voice message"
                "Video" -> "Video"
                else -> "New message"
            }
        } catch (_: Exception) {
            "New message"
        }
    }
}
