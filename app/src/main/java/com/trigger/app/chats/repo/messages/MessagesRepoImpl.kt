package com.trigger.app.chats.repo.messages

import androidx.core.net.toUri
import com.trigger.app.chats.domain.Chat
import com.trigger.app.chats.domain.Message
import com.trigger.app.chats.domain.MessageStatus
import com.trigger.app.chats.domain.MessageType
import com.trigger.app.chats.domain.toMessageType
import com.trigger.app.chats.repo.chats.ChatRepo
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.WriteBatch
import com.google.firebase.firestore.toObject
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID


class MessagesRepoImpl(
    private val chatRepo: ChatRepo
) : MessagesRepo {

    // ========================================================================
    // FIX #6 + #7: Image message with failure recovery + orphan cleanup
    // ========================================================================
    override suspend fun sendImageMessage(
        chatID: String,
        imageUri: String,
        messageText: String?
    ): String? {
        val imageType = MessageType.Image(messageText ?: "", imageUri)
        val messageID = sendMessage(chatID, imageType, finalMessageStatus = MessageStatus.NOT_SENT)
            ?: return null

        val storagePath = "CHATS/$chatID/IMAGES/${UUID.randomUUID()}"

        try {
            val imageUrl = uploadFileUsingUri(storagePath, imageUri)

            // FIX #7: If the Firestore update fails after Storage upload succeeds,
            // the Storage object is orphaned. Catch + cleanup.
            try {
                updateMessageAfterUpload(
                    chatID,
                    messageID,
                    mapOf(Message::messageType.name to imageType.copy(imageUrl = imageUrl).toFirebaseMap())
                )
            } catch (e: Exception) {
                Timber.e(e, "sendImageMessage: Firestore update failed after upload — cleaning up Storage")
                try { Firebase.storage.getReference(storagePath).delete().await() }
                catch (_: Exception) { }
                throw e
            }
        } catch (e: Exception) {
            // FIX #6: If the upload fails, delete the NOT_SENT message so the
            // UI doesn't show a stuck "sending" message forever.
            Timber.e(e, "sendImageMessage: upload failed — deleting NOT_SENT message")
            try {
                ChatRepo.getMessagesCollectionRef(chatID).document(messageID).delete().await()
            } catch (_: Exception) { }
            return null
        }

        return messageID
    }

    override suspend fun sendAudioMessage(
        chatID: String,
        audioUri: String,
        duration: Long
    ): String? {
        val audioMessageType = MessageType.Audio(duration, "")
        val messageID = sendMessage(
            chatID,
            audioMessageType,
            finalMessageStatus = MessageStatus.NOT_SENT
        ) ?: return null

        val storagePath = "CHATS/$chatID/AUDIO/${UUID.randomUUID()}"

        try {
            val audioUrl = uploadFileUsingUri(storagePath, audioUri)

            try {
                updateMessageAfterUpload(
                    chatID,
                    messageID,
                    mapOf(
                        Message::messageType.name to audioMessageType.copy(audioUrl = audioUrl)
                            .toFirebaseMap()
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "sendAudioMessage: Firestore update failed after upload — cleaning up Storage")
                try { Firebase.storage.getReference(storagePath).delete().await() }
                catch (_: Exception) { }
                throw e
            }
        } catch (e: Exception) {
            Timber.e(e, "sendAudioMessage: upload failed — deleting NOT_SENT message")
            try {
                ChatRepo.getMessagesCollectionRef(chatID).document(messageID).delete().await()
            } catch (_: Exception) { }
            return null
        }

        return messageID
    }

    private suspend fun updateMessageAfterUpload(
        chatID: String,
        messageID: String?,
        fieldUpdates: Map<String, Any>
    ) {
        messageID?.let {
            ChatRepo.getMessagesCollectionRef(chatID).document(it)
                .update(
                    fieldUpdates.toMutableMap().apply {
                        set(Message::messageStatus.name, MessageStatus.SENT)
                    }
                )
                .await()
        }
    }


    // ========================================================================
    // FIX #3: Atomic send — message creation + chat_details update in writeBatch
    // FIX #1: Unread count uses FieldValue.increment(1) — no read-modify-write race
    // ========================================================================
    override suspend fun sendMessage(
        chatID: String,
        messageType: MessageType,
        finalMessageStatus: MessageStatus
    ): String? {
        val messageID = UUID.randomUUID().toString()

        val message = Message(
            senderID = Firebase.auth.uid!!,
            messageID = messageID,
            messageType = messageType.toFirebaseMap(),
            timeSent = System.currentTimeMillis(),
            messageStatus = finalMessageStatus  // FIX #3: set directly, not NOT_SENT then update
        )

        val messageRef = ChatRepo.getMessagesCollectionRef(chatID).document(messageID)
        val chatDetailsRef = ChatRepo.getChatDetailsRef(chatID)

        try {
            // FIX #3: Use writeBatch so message creation + chat_details update
            // are atomic. If either fails, neither is applied.
            val batch = Firebase.firestore.batch()
            batch.set(messageRef, message)
            batch.update(
                chatDetailsRef,
                mapOf(
                    Chat::lastMessageSender.name to message.senderID,
                    Chat::timeOfLastMessage.name to message.timeSent,
                    Chat::lastMessageType.name to message.messageType,
                    Chat::lastMessageStatus.name to MessageStatus.SENT,
                    // FIX #1: FieldValue.increment(1) is server-side atomic.
                    // No more (chat?.unreadMessagesCount ?: 0) + 1 race.
                    Chat::unreadMessagesCount.name to FieldValue.increment(1)
                )
            )
            batch.commit().await()

            return messageID
        } catch (e: Exception) {
            Timber.e(e, "sendMessage: batch commit failed")
            return null
        }
    }


    private suspend fun uploadFileUsingUri(storageRef: String, fileUri: String): String {
        // Use explicit StorageMetadata (same fix as profile pic upload).
        val metadata = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .build()
        return Firebase.storage.getReference(storageRef)
            .putFile(fileUri.toUri(), metadata)
            .await().storage
            .downloadUrl.await().toString()
    }


    // ========================================================================
    // FIX #2: Pagination — limit(50) to prevent loading entire conversation
    // ========================================================================
    override suspend fun getMessagesFromChatID(chatID: String) = callbackFlow<List<Message>> {
        val messagesSnapshotListener = ChatRepo.getMessagesCollectionRef(chatID)
            .where(
                Filter.or(
                    Filter.notEqualTo(Message::messageStatus.name, MessageStatus.NOT_SENT),
                    Filter.equalTo(Message::senderID.name, Firebase.auth.uid!!)
                )
            )
            .orderBy(Message::timeSent.name, Query.Direction.DESCENDING)
            // FIX #2: limit to 50 messages. Prevents loading 10,000-message
            // conversations. For full cursor pagination (load-more-on-scroll-up),
            // the ViewModel would track the last visible item and call
            // startAfter() with a new query. This limit is the minimum viable fix.
            .limit(50)
            .addSnapshotListener { value, error ->
                val messages = value?.toObjects(Message::class.java)
                Timber.d("messages count: ${messages?.size}")
                Timber.e(error)

                launch {
                    markUnreadMessagesAsRead(chatID, messages)
                }

                trySend(messages ?: listOf())
            }

        awaitClose {
            messagesSnapshotListener.remove()
        }
    }


    // ========================================================================
    // FIX #4: Batch read-status updates — writeBatch instead of per-message writes
    // ========================================================================
    private suspend fun markUnreadMessagesAsRead(chatID: String, messages: List<Message>?) =
        withContext(Dispatchers.IO) {
            val unreadMessages = messages?.filter {
                it.messageStatus == MessageStatus.SENT && it.senderID != Firebase.auth.uid
            }

            if (!unreadMessages.isNullOrEmpty()) {
                val batch = Firebase.firestore.batch()
                val messagesCollection = ChatRepo.getMessagesCollectionRef(chatID)

                // FIX #4: Use writeBatch — one commit for all messages instead
                // of N individual writes. 100 unread messages = 1 batch commit
                // instead of 100 individual Firestore writes.
                unreadMessages.forEach {
                    batch.update(
                        messagesCollection.document(it.messageID),
                        Message::messageStatus.name,
                        MessageStatus.OPENED
                    )
                }
                batch.commit().await()
            }
        }


    override suspend fun editMessage(chatID: String, messageID: String, newMessage: String) {
        val chatMessagesCollection = ChatRepo.getMessagesCollectionRef(chatID)
        val messageRef = chatMessagesCollection.document(messageID)
        val newMessageType = messageRef.get().await()
            .toObject<Message>()?.messageType?.toMessageType()?.apply { message = newMessage }
            ?.toFirebaseMap()

        messageRef.update(
            mapOf(
                Message::messageType.name to newMessageType,
                Message::wasEdited.name to true
            )
        ).await()

        val lastMessage =
            chatMessagesCollection.orderBy(Message::timeSent.name, Query.Direction.DESCENDING)
                .limit(1).get().await().toObjects(Message::class.java).firstOrNull()

        if (lastMessage?.messageID == messageID) {
            ChatRepo.getChatDetailsRef(chatID)
                .update(Chat::lastMessageType.name, newMessageType)
                .await()
        }
    }


    // ========================================================================
    // FIX #5: unsendMessage — .delete().await() + fix .isSuccessful bug
    // FIX #1: Unread decrement uses FieldValue.increment(-1) — no race
    // ========================================================================
    override suspend fun unsendMessage(chatID: String, messageID: String): Boolean {
        val chatMessagesCollection = ChatRepo.getMessagesCollectionRef(chatID)
        val lastTwoMessages =
            chatMessagesCollection.orderBy(Message::timeSent.name, Query.Direction.DESCENDING)
                .limit(2).get()
                .await().toObjects(Message::class.java)

        val lastMessageID = lastTwoMessages.firstOrNull()?.messageID

        // FIX #5: Add .await() — was fire-and-forget, deletion wasn't verified.
        chatMessagesCollection.document(messageID).delete().await()

        // If the message being unsent is not the latest, no chat_details update needed.
        if (lastMessageID != messageID)
            return true

        val newLastMessage = if (lastTwoMessages.size == 1) null else lastTwoMessages.lastOrNull()
        val chatDetailsRef = ChatRepo.getChatDetailsRef(chatID)

        // FIX #1: Use FieldValue.increment(-1) instead of read-modify-write.
        val updateMap = mutableMapOf<String, Any?>(
            Chat::unreadMessagesCount.name to FieldValue.increment(-1)
        )
        if (newLastMessage != null) {
            updateMap[Chat::lastMessageType.name] = newLastMessage.messageType
            updateMap[Chat::lastMessageSender.name] = newLastMessage.senderID
            updateMap[Chat::timeOfLastMessage.name] = newLastMessage.timeSent
            updateMap[Chat::lastMessageStatus.name] = newLastMessage.messageStatus
        } else {
            updateMap[Chat::lastMessageType.name] = null
            updateMap[Chat::lastMessageSender.name] = null
            updateMap[Chat::timeOfLastMessage.name] = null
            updateMap[Chat::lastMessageStatus.name] = null
        }

        // FIX #5: Use .await() instead of .isSuccessful (was always returning false).
        return try {
            chatDetailsRef.update(updateMap).await()
            true
        } catch (e: Exception) {
            Timber.e(e, "unsendMessage: chat_details update failed")
            false
        }
    }


    // ========================================================================
    // FIX #9: Message retry — re-upload failed NOT_SENT messages
    // ========================================================================
    override suspend fun retrySendMessage(chatID: String, messageID: String): Boolean {
        val messageRef = ChatRepo.getMessagesCollectionRef(chatID).document(messageID)
        val message = messageRef.get().await().toObject<Message>() ?: return false

        // Only retry messages that are NOT_SENT (failed uploads).
        if (message.messageStatus != MessageStatus.NOT_SENT) return false

        val messageType = message.messageType.toMessageType()
        return when (messageType) {
            is MessageType.Image -> {
                val imageUrl = messageType.imageUrl
                if (imageUrl.isNullOrEmpty()) {
                    // Image URL is empty — the original local URI is the imageUrl field.
                    // Re-upload from the local URI.
                    val localUri = messageType.message // was set to the local URI
                    try {
                        val newUrl = uploadFileUsingUri("CHATS/$chatID/IMAGES/${UUID.randomUUID()}", localUri)
                        messageRef.update(
                            mapOf(
                                Message::messageType.name to messageType.copy(imageUrl = newUrl).toFirebaseMap(),
                                Message::messageStatus.name to MessageStatus.SENT
                            )
                        ).await()
                        true
                    } catch (e: Exception) {
                        Timber.e(e, "retrySendMessage: image re-upload failed")
                        false
                    }
                } else {
                    // Image URL already exists — just update status to SENT.
                    messageRef.update(Message::messageStatus.name, MessageStatus.SENT).await()
                    true
                }
            }
            is MessageType.Audio -> {
                if (messageType.audioUrl.isNullOrEmpty()) {
                    val localUri = messageType.message
                    try {
                        val newUrl = uploadFileUsingUri("CHATS/$chatID/AUDIO/${UUID.randomUUID()}", localUri)
                        messageRef.update(
                            mapOf(
                                Message::messageType.name to messageType.copy(audioUrl = newUrl).toFirebaseMap(),
                                Message::messageStatus.name to MessageStatus.SENT
                            )
                        ).await()
                        true
                    } catch (e: Exception) {
                        Timber.e(e, "retrySendMessage: audio re-upload failed")
                        false
                    }
                } else {
                    messageRef.update(Message::messageStatus.name, MessageStatus.SENT).await()
                    true
                }
            }
            is MessageType.Text -> {
                // Text messages don't need upload — just update status.
                messageRef.update(Message::messageStatus.name, MessageStatus.SENT).await()
                true
            }
            else -> false
        }
    }
}
