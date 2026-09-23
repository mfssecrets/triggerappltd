package com.trigger.app.chats.repo.contacts

import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.Contacts
import com.trigger.app.chats.domain.Chat
import com.trigger.app.chats.repo.chats.ChatRepo
import com.trigger.app.chats.repo.contacts.local.dao.LocalContactDao
import com.trigger.app.chats.repo.contacts.local.toLocalContacts
import com.trigger.app.core.domain.User
import com.trigger.app.core.domain.toMiniUser
import com.trigger.app.core.repo.user.UserRepo
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber

class ContactsRepoImpl(
    private val localContactDao: LocalContactDao,
    private val userRepo: UserRepo
) : ContactsRepo {

    private val firestore = Firebase.firestore

    override val contactsOnTriggerApp: Flow<List<User>?> =
        localContactDao.getContacts().map { list -> list?.filterNot { it.uid == Firebase.auth.uid } }


    override suspend fun getContactFromUID(userUID: String): User? =
        localContactDao.getContact(userUID) ?: userRepo.getUserFromUID(userUID)

    override suspend fun searchUserByUsername(username: String): User? =
        userRepo.getUserByUsername(username.lowercase())
            ?.takeUnless { it.uid == Firebase.auth.uid }


    override suspend fun checkForPreExistingChat(contactToCheck: User): String? {
        val otherContact = contactToCheck.toMiniUser()
        val myContact = userRepo.getUserFromUID(Firebase.auth.uid!!)?.toMiniUser()


        val firstUserFilter = Filter.and(Filter.equalTo(Chat::firstMiniUser.name, myContact), Filter.equalTo(Chat::secondMiniUser.name, otherContact))
        val secondUserFilter = Filter.and(Filter.equalTo(Chat::secondMiniUser.name, myContact), Filter.equalTo(Chat::firstMiniUser.name, otherContact))

        /**
         * In the chat details, the current user can be the first user and the otherContact be the second user
         * OR vice-versa
         *
         * The two methods below check for both
         */
        val chatExists = Firebase.firestore.collection(ChatRepo.CHAT_DETAILS)
            .where(Filter.or(firstUserFilter, secondUserFilter))
            .get()
            .await()
            .toObjects(Chat::class.java)
            .firstOrNull()

        Timber.d("Chat exists: $chatExists")

        return chatExists?.chatID
//        val chatWithOtherUserAsFirstUser = Firebase.firestore.collection(ChatRepo.CHAT_DETAILS)
//            .whereEqualTo(Chat::firstMiniUser.name, otherContact)
//            .whereEqualTo(Chat::secondMiniUser.name, myContact)
//            .get()
//            .await()
//            .toObjects(Chat::class.java)
//            .firstOrNull()
//
//        val chatWithCurrentUserAsFirstUser = Firebase.firestore.collection(ChatRepo.CHAT_DETAILS)
//            .whereEqualTo(Chat::firstMiniUser.name, myContact)
//            .whereEqualTo(Chat::secondMiniUser.name, otherContact)
//            .get()
//            .await()
//            .toObjects(Chat::class.java)
//            .firstOrNull()
//
//
//
//        return chatWithCurrentUserAsFirstUser?.chatID ?: chatWithOtherUserAsFirstUser?.chatID
    }

    /**
     * Gets all the contacts on the user's phone, breaks them into groups of 30 (maximum for Firebase queries) and
     * searches for them on Firebase Firestore
     */
    override suspend fun refreshContactsOnTriggerApp(context: Context) {
        // FIX: getContactsOnPhone(context) was called WITHOUT a try/catch.
        // If the user has NOT granted READ_CONTACTS permission, the
        // contentResolver.query() call inside throws SecurityException
        // → propagates to viewModelScope.launch in SelectContactsViewModel
        // → uncaught → app crashes ("Trigger App keeps stopping").
        //
        // Now we wrap it: on SecurityException (or any other failure),
        // return an empty list → the UI shows "None of your contacts is on
        // Trigger App" instead of crashing. The user can still search by
        // username (which doesn't need READ_CONTACTS).
        val phoneContacts = try {
            getContactsOnPhone(context).values.toMutableList()
        } catch (e: SecurityException) {
            Timber.e(e, "refreshContactsOnTriggerApp: READ_CONTACTS permission not granted — returning empty list")
            mutableListOf()
        } catch (e: Exception) {
            Timber.e(e, "refreshContactsOnTriggerApp: getContactsOnPhone failed unexpectedly")
            mutableListOf()
        }

        val numOfLists = phoneContacts.count() / 30 + 1
        val shorterPhoneContacts = (0..numOfLists).map { index ->
            val lastIndex =
                if ((phoneContacts.count() - 1) < 0) 0
                else phoneContacts.count() - 1

            val fromIndex = (index * 30).coerceAtLeast(0).coerceAtMost(lastIndex)
            val toIndex =
                ((index + 1) * 30).coerceAtLeast(0).coerceAtMost(lastIndex)

            if (toIndex > fromIndex)
                phoneContacts.subList(fromIndex, toIndex)
            else
                listOf()
        }.filter { it.isNotEmpty() }

        val contactsOnTriggerApp = mutableListOf<User>()

        // The whereIn query on users/{uid}.number is permission-denied under the
        // new owner-only-read rules. Wrap each batch in try-catch so a
        // permission-denied error doesn't crash the app — just return an empty
        // list (the user will see "No contacts on Trigger App" instead of a
        // crash). The proper fix is a Cloud Function that takes phone numbers
        // and returns matching UIDs (server-side, not subject to rules).
        shorterPhoneContacts.forEach { listToCheck ->
            try {
                contactsOnTriggerApp.addAll(
                    firestore
                        .collection(UserRepo.USERS_COLLECTION)
                        .whereIn(User::number.name, listToCheck)
                        .get()
                        .await()
                        .toObjects(User::class.java)
                )
            } catch (e: Exception) {
                Timber.e(e, "refreshContactsOnTriggerApp: whereIn batch failed (permission-denied under new rules)")
            }
        }

        try {
            localContactDao.refreshContacts(contactsOnTriggerApp.toLocalContacts())
        } catch (e: Exception) {
            Timber.e(e, "refreshContactsOnTriggerApp: Room refresh failed")
        }

        Timber.d("contactsOnTriggerApp is $contactsOnTriggerApp")
    }


    private suspend fun getContactsOnPhone(context: Context): Map<String, String> =
        withContext(Dispatchers.IO) {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                CONTACTS_PROJECTION,
                null, // SELECTION,
                null, //SELECTION_ARGS,
                Contacts.DISPLAY_NAME
            )

            val contacts = mutableMapOf<String, String>()

            cursor?.let {
                while (it.moveToNext()) {
                    val contactName =
                        it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY))
                    val contactNumber =
                        it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER))

                    try {
                        contacts[contactName] = contactNumber
                    } catch (_: NullPointerException) {
                    }
                }
            }

            cursor?.close()
            return@withContext contacts
        }


    companion object {
        val CONTACTS_PROJECTION = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
        )
    }

}