package com.trigger.app.chats.repo.contacts

import android.content.Context
import com.trigger.app.core.domain.User
import kotlinx.coroutines.flow.Flow

interface ContactsRepo {

    /**
     * A flow with contacts on Trigger App
     */
    val contactsOnTriggerApp: Flow<List<User>?>

    /**
     * Checks if the contact uid is in the user's chats already { There is a pre-existing convo }
     *
     * @param contactToCheck - the user to check
     *
     * @return chatID if existing chat is present; else, returns null
     */
    suspend fun checkForPreExistingChat(contactToCheck: User): String?


    /**
     * Fetches a list of the users contacts who are on Trigger App and stores in Room DB
     */
    suspend fun refreshContactsOnTriggerApp(context: Context)


    /**
     * Gets the user details of a certain UID already stored in Room DB
     */
    suspend fun getContactFromUID(userUID: String): User?

    suspend fun searchUserByUsername(username: String): User?

}