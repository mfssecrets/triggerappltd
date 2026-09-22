package com.trigger.app.stories.ui.screens.view_story

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trigger.app.core.domain.User
import com.trigger.app.core.repo.user.UserRepo
import com.trigger.app.stories.domain.Story
import com.trigger.app.stories.repo.StoryRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class ViewStoryViewModel(
    private val userRepo: UserRepo,
    private val storyRepo: StoryRepo
) : ViewModel() {

    private val _typedMessage = MutableStateFlow<String>("")
    val typedMessage: StateFlow<String> = _typedMessage

    private val _author = MutableStateFlow<User?>(null)
    val author: StateFlow<User?> = _author

    private val _stories = MutableStateFlow<List<Story>?>(null)
    val stories: StateFlow<List<Story>?> = _stories

    private val _storyIndex = MutableStateFlow(0)
    val storyIndex: StateFlow<Int> = _storyIndex

    /**
     * If the user has reached the end of the author's stories, hide the story view
     */
    private val _hideStory = MutableStateFlow(false)
    val hideStory: StateFlow<Boolean> = _hideStory

    // Track which stories we've already marked as viewed to avoid duplicate
    // write attempts on the same Story doc.
    private val viewedStoryIDs = mutableSetOf<String>()


    fun loadUser(authorID: String) = viewModelScope.launch {
        _author.value = userRepo.getUserFromUID(authorID)
    }

    fun loadStories(authorID: String) = viewModelScope.launch {
        _stories.value = storyRepo.loadStories(authorID)
        // Mark the first story as viewed immediately.
        _stories.value?.firstOrNull()?.let { story ->
            markStoryViewed(story)
        }
    }


    fun moveToNextStory(currentIndex: Int) {
        val newIndex = currentIndex + 1
        val lastIndex = stories.value?.lastIndex ?: return

        if (newIndex > lastIndex)
            _hideStory.value = true
        else {
            _storyIndex.value = newIndex
            // Mark the new story as viewed when it becomes current.
            stories.value?.getOrNull(newIndex)?.let { markStoryViewed(it) }
        }
    }

    fun moveToPreviousStory(currentIndex: Int) {
        val newIndex = currentIndex - 1

        if (newIndex < 0)
            _hideStory.value = true
        else {
            _storyIndex.value = newIndex
            stories.value?.getOrNull(newIndex)?.let { markStoryViewed(it) }
        }
    }


    fun updateTypedMessage(newText: String) {
        _typedMessage.value = newText
    }

    fun resetTypedMessage() {
        _typedMessage.value = ""
    }

    fun resetHideStory() {
        _storyIndex.value = 0
        _hideStory.value = false
        viewedStoryIDs.clear()
    }


    /**
     * Mark a story as viewed by the current user. Idempotent — re-viewing the
     * same story doesn't bump the count (the Firestore transaction in
     * StoryRepoImpl.watchStory checks for that).
     */
    private fun markStoryViewed(story: Story) {
        val authorID = story.authorUID
        val storyID = story.storyID
        if (viewedStoryIDs.contains(storyID)) return
        viewedStoryIDs.add(storyID)

        viewModelScope.launch {
            try {
                storyRepo.watchStory(authorID, storyID)
            } catch (e: Exception) {
                Timber.e(e, "markStoryViewed: failed for storyID=$storyID (non-fatal)")
            }
        }
    }


    /**
     * Send a reply to the current story. Returns true on success.
     * Caller should clear the input field on success.
     */
    fun sendReply(onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val story = stories.value?.getOrNull(storyIndex.value)
        if (story == null) {
            onResult(false)
            return@launch
        }
        val text = _typedMessage.value
        if (text.isBlank()) {
            onResult(false)
            return@launch
        }

        val success = try {
            storyRepo.sendStoryReply(story.authorUID, story.storyID, text)
        } catch (e: Exception) {
            Timber.e(e, "sendReply: failed")
            false
        }
        if (success) resetTypedMessage()
        onResult(success)
    }


    /**
     * Delete the current story (only valid if the viewer == author).
     * Returns true on success.
     */
    fun deleteCurrentStory(onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val story = stories.value?.getOrNull(storyIndex.value)
        if (story == null) {
            onResult(false)
            return@launch
        }
        // Only the author can delete. The repo will also enforce this via rules.
        if (story.authorUID != com.google.firebase.auth.ktx.auth.let {
                com.google.firebase.ktx.Firebase.auth.uid
            }) {
            onResult(false)
            return@launch
        }

        val success = try {
            storyRepo.deleteStory(story.storyID)
        } catch (e: Exception) {
            Timber.e(e, "deleteCurrentStory: failed")
            false
        }

        if (success) {
            // Remove the deleted story from the in-memory list and either advance
            // to the next or hide the viewer if none remain.
            val updated = _stories.value?.filterNot { it.storyID == story.storyID }
            _stories.value = updated
            if (updated.isNullOrEmpty()) {
                _hideStory.value = true
            } else {
                _storyIndex.value = _storyIndex.value.coerceAtMost(updated.lastIndex)
            }
        }
        onResult(success)
    }
}
