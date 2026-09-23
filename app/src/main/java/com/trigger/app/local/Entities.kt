package com.trigger.app.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// =====================================================================
// Room entities for offline-first message/chat/user cache.
// These mirror the Firestore data models but are stored locally in
// SQLite so the UI can render instantly without waiting for network.
// =====================================================================

@Entity(tableName = "messages", indices = [Index("chatId"), Index("timeSent")])
data class MessageEntity(
    @PrimaryKey val messageID: String,
    val chatId: String,
    val senderID: String,
    val messageType: Map<String, Any>,
    val timeSent: Long,
    val messageStatus: String,
    val wasEdited: Boolean = false,
    // Server-set timestamp for conflict resolution (lastWriteWins).
    // Null for pending offline writes (not yet processed by server).
    val serverTime: Long? = null
)

@Entity(tableName = "chats", indices = [Index("chatId"), Index("timeOfLastMessage")])
data class ChatEntity(
    @PrimaryKey val chatID: String,
    val firstMiniUserUid: String,
    val firstMiniUserName: String,
    val firstMiniUserProfilePic: String?,
    val secondMiniUserUid: String,
    val secondMiniUserName: String,
    val secondMiniUserProfilePic: String?,
    val unreadMessagesCount: Int,
    val lastMessageSender: String?,
    val lastMessageStatus: String?,
    val timeOfLastMessage: Long?,
    val lastMessageType: Map<String, Any>?,
    val isDisabled: Boolean = false
)

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val uid: String,
    val name: String,
    val username: String,
    val bio: String,
    val profilePic: String?,
    val number: String,
    val lastSeen: Long,
    val userStatus: String
)

@Entity(tableName = "media_files", indices = [Index("remoteUrl")])
data class MediaFileEntity(
    @PrimaryKey val remoteUrl: String,
    val localPath: String,
    val mediaType: String,  // "image" | "audio" | "video"
    val chatId: String?,
    val downloadedAt: Long,
    val fileSize: Long? = null
)
