package com.trigger.app.core.repo.moderation

import com.trigger.app.core.domain.User
import kotlinx.coroutines.flow.Flow

interface ModerationRepo {
    fun getBlockedUsers(): Flow<List<User>>
    suspend fun blockUser(user: User)
    suspend fun unblockUser(userID: String)
    suspend fun reportUser(userID: String, reason: String)
}
