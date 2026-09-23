package com.trigger.app.local

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timeSent DESC")
    fun getMessagesForChat(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timeSent DESC LIMIT 50")
    suspend fun getCachedMessages(chatId: String): List<MessageEntity>

    @Query("UPDATE messages SET messageStatus = :status WHERE messageID = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("UPDATE messages SET messageStatus = :status WHERE chatId = :chatId AND senderID != :currentUid AND messageStatus IN (:fromStatuses)")
    suspend fun batchUpdateStatus(chatId: String, currentUid: String, status: String, fromStatuses: List<String>)

    @Query("DELETE FROM messages WHERE messageID = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteAllForChat(chatId: String)
}

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chats: List<ChatEntity>)

    @Query("SELECT * FROM chats WHERE (firstMiniUserUid = :uid OR secondMiniUserUid = :uid) AND isDisabled = 0 ORDER BY timeOfLastMessage DESC")
    fun getChatsForUser(uid: String): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE chatID = :chatId")
    suspend fun getChat(chatId: String): ChatEntity?

    @Query("UPDATE chats SET unreadMessagesCount = :count, lastMessageStatus = :status WHERE chatID = :chatId")
    suspend fun updateUnreadCount(chatId: String, count: Int, status: String)

    @Query("UPDATE chats SET isDisabled = 1 WHERE chatID = :chatId")
    suspend fun disableChat(chatId: String)
}

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(users: List<UserEntity>)

    @Query("SELECT * FROM users WHERE uid = :uid")
    fun getUser(uid: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE uid = :uid")
    suspend fun getCachedUser(uid: String): UserEntity?

    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun findByUsername(username: String): UserEntity?
}

@Dao
interface MediaFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(media: MediaFileEntity)

    @Query("SELECT * FROM media_files WHERE remoteUrl = :url")
    suspend fun getByUrl(url: String): MediaFileEntity?

    @Query("SELECT * FROM media_files WHERE chatId = :chatId")
    fun getMediaForChat(chatId: String): Flow<List<MediaFileEntity>>

    @Query("DELETE FROM media_files WHERE remoteUrl = :url")
    suspend fun delete(url: String)
}
