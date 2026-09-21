package com.trigger.app.settings.presentation.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trigger.app.chats.repo.chats.ChatRepo
import com.trigger.app.chats.repo.chats.ChatRepoImpl
import com.trigger.app.core.repo.user.UserRepo
import com.trigger.app.core.repo.user.UserRepoImpl
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class SettingsViewModel(
    private val userRepo: UserRepo = UserRepoImpl(),
    private val chatRepo: ChatRepo = ChatRepoImpl(userRepo)
) : ViewModel() {

    private val _isDeletingAccount = MutableStateFlow(false)
    val isDeletingAccount: StateFlow<Boolean> = _isDeletingAccount

    fun signOut() { Firebase.auth.signOut() }

    /**
     * Full account-deletion pipeline.
     *
     * Order:
     *   1. Fetch the current user's profile (to read the username — needed to
     *      free `usernames/{username}` reservation; Firestore rules require
     *      caller == owner).
     *   2. Disable all chats where this user is a participant (sets
     *      `chat_details/{chatID}.isDisabled = true` AND removes both
     *      participants' `personalized_chats` entries for those chats).
     *   3. Run the full `UserRepo.deleteUserCompletely` pipeline — removes the
     *      blockedUsers subcollection, Storage profile pic, users/{uid} doc,
     *      public_users/{uid} projection, usernames/{username} reservation,
     *      stories, and finally `Firebase.auth.currentUser.delete()`.
     *   4. Locally sign out (defensive — deleteUserCompletely already signs out).
     *
     * Any per-step error is logged via Timber and the pipeline continues to the
     * next step so a partial failure doesn't leave the user in a worse state.
     */
    fun deleteAccount() = viewModelScope.launch {
        _isDeletingAccount.value = true

        val uid = Firebase.auth.uid
        if (uid != null) {
            try {
                // Look up the user's username BEFORE deleting anything — we need it
                // to free the `usernames/{username}` reservation.
                val user = userRepo.getUserFromUID(uid)
                val username = user?.username.orEmpty()

                // Disable chats + remove both participants' personalized_chats entries.
                chatRepo.disableChatsForUser(uid)

                // Run the full cleanup pipeline (Firestore docs + Storage + Auth user).
                val cleanupSuccessful = userRepo.deleteUserCompletely(uid, username)
                Timber.d("deleteUserCompletely returned $cleanupSuccessful")
            } catch (e: Exception) {
                Timber.e(e, "deleteAccount pipeline threw; continuing with sign-out")
            } finally {
                // Defensive: ensure local Auth state is cleared even if a step above threw.
                Firebase.auth.signOut()
            }
        }

        _isDeletingAccount.value = false
    }
}
