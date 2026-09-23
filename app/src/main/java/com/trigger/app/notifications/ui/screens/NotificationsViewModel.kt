package com.trigger.app.notifications.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.toObject
import com.google.firebase.ktx.Firebase
import com.trigger.app.calls.domain.Call
import com.trigger.app.calls.repo.CallsRepo
import com.trigger.app.chats.domain.Chat
import com.trigger.app.chats.repo.chats.ChatRepo
import com.trigger.app.stories.repo.StoryReply
import com.trigger.app.stories.repo.StoryRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Notifications feed — merged live list of:
 *   1. Unread chats (chats where the current user is a participant with
 *      unreadMessagesCount > 0)
 *   2. Story replies on the current user's stories (where read == false)
 *   3. Missed calls (calls where the current user is the callee AND status
 *      == MISSED AND read == false)
 *
 * Each emission replaces the entire list. The list is grouped by section
 * so the UI can render headers.
 *
 * All three sources use Firestore snapshot listeners — they auto-update
 * in real time as the underlying data changes.
 */
class NotificationsViewModel(
    private val chatRepo: ChatRepo,
    private val storyRepo: StoryRepo,
    private val callsRepo: CallsRepo
) : ViewModel() {

    data class NotificationItem(
        val id: String,                // unique ID for item keying
        val type: NotificationType,
        val chatId: String?,           // for UnreadChat — used to nav to ActualChat
        val senderName: String,
        val senderProfilePic: String?,
        val previewText: String,
        val unreadCount: Int,          // for UnreadChat badge; 0 for others
        val timestamp: Long?,
        // Story-reply-specific routing data:
        val storyAuthorUID: String? = null,
        val storyID: String? = null,
        val replyID: String? = null,
        // Call-specific routing data:
        val callID: String? = null
    )

    enum class NotificationType { UnreadChat, StoryReply, MissedCall }

    data class NotificationFeed(
        val isLoading: Boolean,
        val items: List<NotificationItem>
    )

    private val _feed = MutableStateFlow(NotificationFeed(isLoading = true, items = emptyList()))
    val feed: StateFlow<NotificationFeed> = _feed

    private val _unreadChats = MutableStateFlow<List<NotificationItem>>(emptyList())
    private val _storyReplies = MutableStateFlow<List<NotificationItem>>(emptyList())
    private val _missedCalls = MutableStateFlow<List<NotificationItem>>(emptyList())

    init {
        startListeningForUnreadChats()
        startListeningForStoryReplies()
        startListeningForMissedCalls()
        mergeFeeds()
    }

    private fun startListeningForUnreadChats() {
        val uid = Firebase.auth.uid ?: return

        viewModelScope.launch {
            try {
                val firstUserFilter = Filter.equalTo("firstMiniUser.uid", uid)
                val secondUserFilter = Filter.equalTo("secondMiniUser.uid", uid)

                Firebase.firestore.collection(ChatRepo.CHAT_DETAILS)
                    .where(Filter.or(firstUserFilter, secondUserFilter))
                    .orderBy("timeOfLastMessage", Query.Direction.DESCENDING)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Timber.e(error, "startListeningForUnreadChats: snapshot error")
                            return@addSnapshotListener
                        }
                        if (snapshot == null) return@addSnapshotListener

                        val items = snapshot.documents.mapNotNull { doc ->
                            try {
                                val chat = doc.toObject<Chat>() ?: return@mapNotNull null
                                if (chat.isDisabled) return@mapNotNull null
                                if (chat.unreadMessagesCount <= 0) return@mapNotNull null

                                val isCurrentUserFirst = chat.firstMiniUser.uid == uid
                                val otherUser = if (isCurrentUserFirst)
                                    chat.secondMiniUser else chat.firstMiniUser

                                NotificationItem(
                                    id = "chat_${chat.chatID}",
                                    type = NotificationType.UnreadChat,
                                    chatId = chat.chatID,
                                    senderName = otherUser.name,
                                    senderProfilePic = otherUser.profilePic,
                                    previewText = buildMessagePreview(chat),
                                    unreadCount = chat.unreadMessagesCount,
                                    timestamp = chat.timeOfLastMessage
                                )
                            } catch (e: Exception) {
                                Timber.e(e, "startListeningForUnreadChats: parse failure for ${doc.id}")
                                null
                            }
                        }
                        _unreadChats.value = items
                    }
            } catch (e: Exception) {
                Timber.e(e, "startListeningForUnreadChats: failed to set up listener")
            }
        }
    }

    private fun startListeningForStoryReplies() {
        viewModelScope.launch {
            try {
                storyRepo.getMyStoryReplies().collect { replies ->
                    _storyReplies.value = replies.map {
                        NotificationItem(
                            id = "reply_${it.replyID}",
                            type = NotificationType.StoryReply,
                            chatId = null,
                            senderName = it.senderName,
                            senderProfilePic = it.senderProfilePic,
                            previewText = "Replied: ${it.message.take(60)}${if (it.message.length > 60) "..." else ""}",
                            unreadCount = 0,
                            timestamp = it.sentAt,
                            storyAuthorUID = it.storyAuthorUID,
                            storyID = it.storyID,
                            replyID = it.replyID
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "startListeningForStoryReplies: failed")
                _storyReplies.value = emptyList()
            }
        }
    }

    private fun startListeningForMissedCalls() {
        viewModelScope.launch {
            try {
                callsRepo.getMissedCallNotifications().collect { calls ->
                    _missedCalls.value = calls.map { call ->
                        NotificationItem(
                            id = "call_${call.callID}",
                            type = NotificationType.MissedCall,
                            chatId = null,
                            senderName = "Missed call",  // placeholder — sender lookup is future work
                            senderProfilePic = null,
                            previewText = "Missed ${call.callType.firebaseKey.lowercase()} call",
                            unreadCount = 0,
                            timestamp = call.startedAt,
                            callID = call.callID
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "startListeningForMissedCalls: failed")
                _missedCalls.value = emptyList()
            }
        }
    }

    private fun mergeFeeds() {
        viewModelScope.launch {
            combine(_unreadChats, _storyReplies, _missedCalls) { chats, replies, calls ->
                // Order: newest first across all three types.
                val merged = (chats + replies + calls)
                    .sortedByDescending { it.timestamp ?: 0L }

                // Set loading false once we've received at least one emission
                // from each source. (Initial state is emptyList from all three,
                // so the first merge will be empty but loading=false.)
                NotificationFeed(isLoading = false, items = merged)
            }.collect { feed ->
                _feed.value = feed
            }
        }
    }

    /**
     * Mark a story reply as read (after the user taps the row).
     */
    fun markStoryReplyAsRead(item: NotificationItem) {
        val authorUID = item.storyAuthorUID ?: return
        val storyID = item.storyID ?: return
        val replyID = item.replyID ?: return
        viewModelScope.launch {
            storyRepo.markStoryReplyAsRead(authorUID, storyID, replyID)
        }
    }

    /**
     * Mark a missed call as read (after the user taps the row).
     */
    fun markCallAsRead(item: NotificationItem) {
        val callID = item.callID ?: return
        viewModelScope.launch {
            callsRepo.markCallAsRead(callID)
        }
    }

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
