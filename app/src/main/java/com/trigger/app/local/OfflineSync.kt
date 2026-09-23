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
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.toObjects
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.io.File
import java.util.Date
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
    wasEdited = wasEdited,
    serverTime = serverTime?.time  // Date → Long (milliseconds)
)

fun MessageEntity.toMessage() = Message(
    senderID = senderID,
    messageID = messageID,
    messageType = messageType,
    timeSent = timeSent,
    messageStatus = try { MessageStatus.valueOf(messageStatus) } catch (_: Exception) { MessageStatus.NOT_SENT },
    wasEdited = wasEdited,
    serverTime = serverTime?.let { Date(it) }  // Long → Date
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
    lastMessageType = lastMessageType ?: emptyMap(),
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
    // FIX #1: DocumentSnapshot cursor (was Long timeSent — could skip/duplicate
    // messages with the same millisecond). DocumentSnapshot uses the document's
    // actual position in the query index, guaranteeing no skips/duplicates.
    private val paginationCursors = mutableMapOf<String, DocumentSnapshot?>()
    private val loadingOlder = mutableSetOf<String>()
    private val reachedBeginning = mutableSetOf<String>()
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

                    // FIX #1: Cursor is NOT initialized here — the snapshot listener
                    // returns List<Message>, not QuerySnapshot, so we can't get
                    // DocumentSnapshot. The cursor is established lazily in
                    // loadOlderMessages() on the first scroll-up.
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

    /**
     * OFFLINE-FIRST SEND: writes the message to Room IMMEDIATELY (instant UI
     * with NOT_SENT status), then attempts the Firestore write in the background.
     *
     * If online: Firestore processes the write → snapshot listener fires →
     * Room is updated with SENT status → UI shows "sent".
     *
     * If offline: Firestore queues the write via its built-in persistence.
     * The message stays as NOT_SENT in Room (UI shows "sending"). When the
     * network returns, Firestore processes the pending write → snapshot
     * listener fires → Room updated → UI shows "sent".
     *
     * If the app is killed while offline: Room cache still has the NOT_SENT
     * message. On next launch, syncMessages() starts → Firestore snapshot
     * listener starts → if the pending write was processed, it appears with
     * SENT. If the pending write was evicted, call retryPendingMessages().
     */
    suspend fun sendOfflineMessage(
        chatID: String,
        messageType: com.trigger.app.chats.domain.MessageType,
        finalMessageStatus: com.trigger.app.chats.domain.MessageStatus = com.trigger.app.chats.domain.MessageStatus.SENT
    ): String? {
        val messageID = java.util.UUID.randomUUID().toString()
        val message = com.trigger.app.chats.domain.Message(
            senderID = Firebase.auth.uid ?: "",
            messageID = messageID,
            messageType = messageType.toFirebaseMap(),
            timeSent = System.currentTimeMillis(),
            messageStatus = com.trigger.app.chats.domain.MessageStatus.NOT_SENT  // NOT_SENT → shows "sending" in UI
        )

        // 1. Write to Room IMMEDIATELY — UI shows the message instantly.
        messageDao.upsertAll(listOf(message.toEntity(chatID)))

        // 2. Attempt Firestore write (will be queued by Firestore if offline).
        //    The existing sendMessage() uses writeBatch for atomic message +
        //    chat_details update.
        return try {
            val firestoreID = messagesRepo.sendMessage(chatID, messageType, finalMessageStatus)
            if (firestoreID == null) {
                // Firestore write failed — message stays as NOT_SENT in Room.
                // Firestore's built-in persistence may retry later.
                Timber.w("sendOfflineMessage: Firestore write failed for $messageID — stays NOT_SENT in Room")
            }
            // If successful, the Firestore snapshot listener will fire and
            // update Room with the correct status. We don't need to manually
            // update Room here — the sync flow handles it.
            firestoreID ?: messageID
        } catch (e: Exception) {
            Timber.e(e, "sendOfflineMessage: Firestore write failed (offline?) — message queued in Room as NOT_SENT")
            messageID
        }
    }

    /**
     * OFFLINE-FIRST IMAGE SEND: same pattern as sendOfflineMessage but for
     * images. Writes a NOT_SENT message to Room (with local URI as preview),
     * then attempts the Storage upload + Firestore update in the background.
     */
    suspend fun sendOfflineImageMessage(
        chatID: String,
        imageUri: String,
        messageText: String?
    ): String? {
        val imageType = com.trigger.app.chats.domain.MessageType.Image(messageText ?: "", imageUri)
        val messageID = java.util.UUID.randomUUID().toString()
        val message = com.trigger.app.chats.domain.Message(
            senderID = Firebase.auth.uid ?: "",
            messageID = messageID,
            messageType = imageType.toFirebaseMap(),
            timeSent = System.currentTimeMillis(),
            messageStatus = com.trigger.app.chats.domain.MessageStatus.NOT_SENT
        )

        // 1. Write to Room immediately (shows local image instantly).
        messageDao.upsertAll(listOf(message.toEntity(chatID)))

        // 2. Attempt Firestore + Storage upload in background.
        return try {
            messagesRepo.sendImageMessage(chatID, imageUri, messageText) ?: messageID
        } catch (e: Exception) {
            Timber.e(e, "sendOfflineImageMessage: upload failed — queued as NOT_SENT in Room")
            messageID
        }
    }

    /**
     * OFFLINE-FIRST AUDIO SEND: same pattern for audio messages.
     */
    suspend fun sendOfflineAudioMessage(
        chatID: String,
        audioUri: String,
        duration: Long
    ): String? {
        val audioType = com.trigger.app.chats.domain.MessageType.Audio(duration, "")
        val messageID = java.util.UUID.randomUUID().toString()
        val message = com.trigger.app.chats.domain.Message(
            senderID = Firebase.auth.uid ?: "",
            messageID = messageID,
            messageType = audioType.toFirebaseMap(),
            timeSent = System.currentTimeMillis(),
            messageStatus = com.trigger.app.chats.domain.MessageStatus.NOT_SENT
        )

        // 1. Write to Room immediately.
        messageDao.upsertAll(listOf(message.toEntity(chatID)))

        // 2. Attempt Firestore + Storage upload in background.
        return try {
            messagesRepo.sendAudioMessage(chatID, audioUri, duration) ?: messageID
        } catch (e: Exception) {
            Timber.e(e, "sendOfflineAudioMessage: upload failed — queued as NOT_SENT in Room")
            messageID
        }
    }

    /**
     * RETRY: queries Room for all NOT_SENT messages in a chat and retries
     * the Firestore write for each. Called on:
     *   - Screen load (catch stuck messages from killed-app-while-offline)
     *   - Network restored (via NetworkObserver)
     *
     * Uses the existing messagesRepo.retrySendMessage() which re-uploads
     * failed image/audio from the local URI.
     */
    suspend fun retryPendingMessages(chatID: String) {
        try {
            // Query Room for all NOT_SENT messages in this chat.
            val cached = messageDao.getCachedMessages(chatID)
            val pending = cached.filter { it.messageStatus == "NOT_SENT" }

            if (pending.isEmpty()) return

            Timber.d("retryPendingMessages: found ${pending.size} pending messages for chat $chatID")

            for (entity in pending) {
                try {
                    val success = messagesRepo.retrySendMessage(chatID, entity.messageID)
                    if (success) {
                        // Update Room status to SENT immediately (Firestore
                        // snapshot listener will also update, but this gives
                        // instant feedback).
                        messageDao.updateStatus(entity.messageID, "SENT")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "retryPendingMessages: retry failed for ${entity.messageID}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "retryPendingMessages: failed for chat $chatID")
        }
    }

    // ================================================================
    // CURSOR PAGINATION — load older messages on scroll-up
    // ================================================================

    /**
     * Load the next page of 50 OLDER messages from Firestore using the cursor
     * (the oldest timeSent in the current page). Upserts to Room — the Room
     * flow re-emits and the UI shows the older messages automatically.
     *
     * Safe to call multiple times — guarded by loadingOlder + reachedBeginning.
     * New sent messages don't disturb the cursor (cursor is only updated by
     * this method, not by the live snapshot listener).
     */
    suspend fun loadOlderMessages(chatID: String) {
        if (loadingOlder.contains(chatID)) return
        if (reachedBeginning.contains(chatID)) return
        loadingOlder.add(chatID)

        try {
            val baseQuery = ChatRepo.getMessagesCollectionRef(chatID)
                .where(
                    Filter.or(
                        Filter.notEqualTo(Message::messageStatus.name, MessageStatus.NOT_SENT),
                        Filter.equalTo(Message::senderID.name, Firebase.auth.uid ?: "")
                    )
                )
                .orderBy(Message::timeSent.name, Query.Direction.DESCENDING)

            val cursor = paginationCursors[chatID]

            if (cursor == null) {
                // FIX #1: First scroll-up — establish the cursor via a one-time query.
                // The snapshot listener already loaded the latest 50 into Room.
                // This query gets the SAME 50 (for the DocumentSnapshot cursor)
                // then immediately queries the NEXT 50 (older messages).
                val initialSnapshot = baseQuery.limit(50).get().await()
                if (initialSnapshot.isEmpty || initialSnapshot.size() < 50) {
                    reachedBeginning.add(chatID)
                    loadingOlder.remove(chatID)
                    return
                }
                // Store the last document of the latest 50 as the cursor.
                val initialCursor = initialSnapshot.documents.lastOrNull()
                paginationCursors[chatID] = initialCursor

                // Query the NEXT 50 older messages.
                if (initialCursor != null) {
                    val olderSnapshot = baseQuery.startAfter(initialCursor).limit(50).get().await()
                    if (olderSnapshot.isEmpty) {
                        reachedBeginning.add(chatID)
                    } else {
                        val messages = olderSnapshot.toObjects(Message::class.java)
                        messageDao.upsertAll(messages.map { it.toEntity(chatID) })
                        paginationCursors[chatID] = olderSnapshot.documents.lastOrNull()
                        Timber.d("loadOlderMessages: loaded ${messages.size} older (initial)")
                    }
                }
            } else {
                // Subsequent scroll-up — use the stored DocumentSnapshot cursor.
                val snapshot = baseQuery.startAfter(cursor).limit(50).get().await()
                if (snapshot.isEmpty) {
                    reachedBeginning.add(chatID)
                    Timber.d("loadOlderMessages: reached beginning for chat $chatID")
                } else {
                    val messages = snapshot.toObjects(Message::class.java)
                    messageDao.upsertAll(messages.map { it.toEntity(chatID) })
                    paginationCursors[chatID] = snapshot.documents.lastOrNull()
                    Timber.d("loadOlderMessages: loaded ${messages.size} older for chat $chatID")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "loadOlderMessages: failed for chatID=$chatID")
        } finally {
            loadingOlder.remove(chatID)
        }
    }

    fun hasMoreMessages(chatID: String): Boolean = !reachedBeginning.contains(chatID)

    fun resetPagination(chatID: String) {
        paginationCursors.remove(chatID)
        loadingOlder.remove(chatID)
        reachedBeginning.remove(chatID)
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
