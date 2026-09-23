package com.trigger.app.local

import android.content.Context
import com.trigger.app.chats.domain.Chat
import com.trigger.app.chats.domain.Message
import com.trigger.app.chats.domain.MessageStatus
import com.trigger.app.chats.repo.chats.ChatRepo
import com.trigger.app.chats.repo.messages.MessagesRepo
import com.trigger.app.core.domain.MiniUser
import com.trigger.app.core.domain.User
import com.trigger.app.core.domain.UserStatus
import com.trigger.app.core.repo.user_details.UserDetailsRepo
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.io.File
import java.util.UUID

// =====================================================================
// Mappers: Firestore domain models ↔ Room entities
// =====================================================================

fun Message.toEntity(chatId: String) = MessageEntity(
    messageID = messageID,
    chatId = chatId,
    senderID = senderID,
    messageType = messageType,
    timeSent = timeSent,
    messageStatus = messageStatus.name,
    wasEdited = wasEdited
)

fun MessageEntity.toMessage() = Message(
    senderID = senderID,
    messageID = messageID,
    messageType = messageType,
    timeSent = timeSent,
    messageStatus = try { MessageStatus.valueOf(messageStatus) } catch (_: Exception) { MessageStatus.NOT_SENT },
    wasEdited = wasEdited
)

fun Chat.toEntity() = ChatEntity(
    chatID = chatID,
    firstMiniUserUid = firstMiniUser.uid,
    firstMiniUserName = firstMiniUser.name,
    firstMiniUserProfilePic = firstMiniUser.profilePic,
    secondMiniUserUid = secondMiniUser.uid,
    secondMiniUserName = secondMiniUser.name,
    secondMiniUserProfilePic = secondMiniUser.profilePic,
    unreadMessagesCount = unreadMessagesCount,
    lastMessageSender = lastMessageSender,
    lastMessageStatus = lastMessageStatus?.name,
    timeOfLastMessage = timeOfLastMessage,
    lastMessageType = lastMessageType,
    isDisabled = isDisabled
)

fun ChatEntity.toChat() = Chat(
    chatID = chatID,
    firstMiniUser = MiniUser(firstMiniUserName, firstMiniUserUid, firstMiniUserProfilePic),
    secondMiniUser = MiniUser(secondMiniUserName, secondMiniUserUid, secondMiniUserProfilePic),
    unreadMessagesCount = unreadMessagesCount,
    lastMessageSender = lastMessageSender,
    lastMessageStatus = lastMessageStatus?.let { try { MessageStatus.valueOf(it) } catch (_: Exception) { null } },
    timeOfLastMessage = timeOfLastMessage,
    lastMessageType = lastMessageType,
    isDisabled = isDisabled
)

fun User.toEntity() = UserEntity(
    uid = uid,
    name = name,
    username = username,
    bio = bio,
    profilePic = profilePic,
    number = number,
    lastSeen = lastSeen,
    userStatus = userStatus.name
)

fun UserEntity.toUser() = User(
    uid = uid,
    name = name,
    username = username,
    bio = bio,
    profilePic = profilePic,
    number = number,
    lastSeen = lastSeen,
    userStatus = try { UserStatus.valueOf(userStatus) } catch (_: Exception) { UserStatus.HasDataButNotInApp }
)


// =====================================================================
// MessageSyncRepository — Firestore → Room sync for messages
// UI reads from Room (instant cache) while Firestore syncs in background.
// =====================================================================

class MessageSyncRepository(
    private val messagesRepo: MessagesRepo,
    private val messageDao: MessageDao
) {
    /**
     * Returns a Flow that:
     *   1. Immediately emits cached messages from Room (offline-ready)
     *   2. Starts a Firestore snapshot listener in the background
     *   3. On each Firestore emission, upserts messages into Room
     *   4. Room flow re-emits with the synced data
     *   5. When the flow is cancelled (screen destroyed), the Firestore
     *      listener is also cancelled.
     */
    fun syncMessages(chatID: String): Flow<List<Message>> = channelFlow {
        // Launch Firestore → Room sync in background.
        val syncJob = launch {
            try {
                messagesRepo.getMessagesFromChatID(chatID).collect { messages ->
                    // Upsert all messages to Room.
                    messageDao.upsertAll(messages.map { it.toEntity(chatID) })
                }
            } catch (e: Exception) {
                Timber.e(e, "MessageSyncRepository: Firestore sync failed for chatID=$chatID (non-fatal — Room cache still serves)")
            }
        }

        // Forward Room flow to the UI (emits immediately from cache).
        val roomJob = launch {
            messageDao.getMessagesForChat(chatID).collect { entities ->
                trySend(entities.map { it.toMessage() })
            }
        }

        // Cancel both jobs when the flow is cancelled.
        awaitClose {
            syncJob.cancel()
            roomJob.cancel()
        }
    }
}


// =====================================================================
// ChatSyncRepository — Firestore → Room sync for chat list
// =====================================================================

class ChatSyncRepository(
    private val chatRepo: ChatRepo,
    private val chatDao: ChatDao
) {
    fun syncChats(uid: String): Flow<List<Chat>> = channelFlow {
        val syncJob = launch {
            try {
                chatRepo.getChatsForUser(uid).collect { chats ->
                    if (chats != null) {
                        chatDao.upsertAll(chats.map { it.toEntity() })
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "ChatSyncRepository: Firestore sync failed (non-fatal — Room cache still serves)")
            }
        }

        val roomJob = launch {
            chatDao.getChatsForUser(uid).collect { entities ->
                trySend(entities.map { it.toChat() })
            }
        }

        awaitClose {
            syncJob.cancel()
            roomJob.cancel()
        }
    }
}


// =====================================================================
// UserSyncRepository — Firestore → Room sync for user profile
// =====================================================================

class UserSyncRepository(
    private val userDetailsRepo: UserDetailsRepo,
    private val userDao: UserDao
) {
    fun syncUser(uid: String): Flow<User?> = channelFlow {
        val syncJob = launch {
            try {
                userDetailsRepo.getUserProfileFlow.collect { user ->
                    if (user != null) {
                        userDao.upsert(user.toEntity())
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "UserSyncRepository: Firestore sync failed (non-fatal — Room cache still serves)")
            }
        }

        val roomJob = launch {
            userDao.getUser(uid).collect { entity ->
                trySend(entity?.toUser())
            }
        }

        awaitClose {
            syncJob.cancel()
            roomJob.cancel()
        }
    }
}


// =====================================================================
// MediaDownloadManager — Storage → local file → Room MediaFileEntity
// Previously viewed media remains available offline.
// =====================================================================

class MediaDownloadManager(
    private val mediaFileDao: MediaFileDao,
    private val context: Context
) {
    private val storage = Firebase.storage

    /**
     * Returns the local file path for a remote Storage URL.
     *   1. Check Room cache — if localPath exists and file is present, return immediately.
     *   2. Download from Firebase Storage to local file.
     *   3. Cache the mapping in Room (remoteUrl → localPath).
     *   4. Return the local path.
     * On failure, returns null (caller falls back to loading the remote URL).
     */
    suspend fun getOrDownload(remoteUrl: String, mediaType: String = "image"): String? {
        // 1. Check Room cache.
        val cached = mediaFileDao.getByUrl(remoteUrl)
        if (cached != null) {
            val file = File(cached.localPath)
            if (file.exists()) return cached.localPath
        }

        // 2. Download from Storage.
        return try {
            val ref = storage.getReferenceFromUrl(remoteUrl)
            val ext = when (mediaType) {
                "audio" -> "m4a"
                "video" -> "mp4"
                else -> "jpg"
            }
            val fileName = "media_${UUID.randomUUID()}.$ext"
            val localFile = File(context.filesDir, "media/$fileName")
            localFile.parentFile?.mkdirs()

            ref.getFile(localFile).await()

            // 3. Cache in Room.
            mediaFileDao.upsert(MediaFileEntity(
                remoteUrl = remoteUrl,
                localPath = localFile.absolutePath,
                mediaType = mediaType,
                chatId = null,
                downloadedAt = System.currentTimeMillis(),
                fileSize = localFile.length()
            ))

            localFile.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "MediaDownloadManager: download failed for $remoteUrl")
            null
        }
    }

    /**
     * Pre-download all media for a chat (background sync when chat is opened).
     */
    suspend fun preDownloadChatMedia(chatID: String, messageEntities: List<MessageEntity>) {
        for (message in messageEntities) {
            val type = message.messageType["type"]?.toString()
            val url = when (type) {
                "Image" -> message.messageType["imageUrl"]?.toString()
                "Audio" -> message.messageType["audioUrl"]?.toString()
                else -> null
            }
            if (url != null) {
                getOrDownload(url, when (type) { "Audio" -> "audio"; else -> "image" })
            }
        }
    }
}
