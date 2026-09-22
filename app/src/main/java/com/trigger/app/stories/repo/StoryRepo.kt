package com.trigger.app.stories.repo

import android.net.Uri
import com.trigger.app.stories.domain.Story
import com.trigger.app.stories.domain.StoryPreview
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.Flow

interface StoryRepo {

    companion object {
        const val STORY = "story"

        private const val STORY_DETAILS_COLLECTION_REF = "story_details"
        private const val STORY_CONTENT_COLLECTION_REF = "content"
        private const val STORY_VIEWERS_COLLECTION_REF = "viewers"
        private const val STORY_REPLIES_COLLECTION_REF = "replies"

        fun getStoryDetailsCollection(userID: String) =
            Firebase.firestore.collection(STORY_DETAILS_COLLECTION_REF).document(userID)

        fun getStoryCollection(userID: String) =
            Firebase.firestore.collection(STORY)
                .document(STORY_CONTENT_COLLECTION_REF)
                .collection(userID)

        fun getStoryViewersCollection(userID: String) =
            Firebase.firestore.collection(STORY)
                .document(STORY_VIEWERS_COLLECTION_REF)
                .collection(userID)

        /**
         * Per-story viewers subcollection: `story/viewers/{authorID}/{storyID}/{viewerUID}`.
         * Each doc represents a single viewer; creating one is idempotent
         * (same viewerID = same doc, just re-set).
         */
        fun getStoryViewersSubcollection(authorID: String, storyID: String) =
            getStoryViewersCollection(authorID).document(storyID)
                .collection(STORY_VIEWERS_COLLECTION_REF)

        /**
         * Per-story replies subcollection: `story/replies/{authorID}/{storyID}/{replyID}`.
         * Each reply doc has {senderID, senderName, message, sentAt}.
         */
        fun getStoryRepliesCollection(authorID: String, storyID: String) =
            Firebase.firestore.collection(STORY)
                .document(STORY_REPLIES_COLLECTION_REF)
                .collection(authorID)
                .document(storyID)
                .collection(STORY_REPLIES_COLLECTION_REF)
    }


    // Gets all the NON-EXPIRED stories of the given user, ordered newest → oldest.
    suspend fun loadStories(authorID: String): List<Story>

    fun getMyStoryPreview(): Flow<StoryPreview?>
    fun getStoryPreviews(): Flow<List<StoryPreview>?>

    /**
     * Fetches the stories for a given userID
     */
    suspend fun getStoriesForUID(userID: String): List<Story>

    /**
     * Posts an image to the current user's story.
     *
     * Implementation MUST:
     *   - Upload the image to Storage with explicit content-type
     *   - Write the Story doc to `story/content/{uid}/{storyID}` inside a Firestore transaction
     *   - Maintain `story_details/{uid}` via `FieldValue.increment(1)` on `storyCount` +
     *     update `previewImage`/`timeUploaded` atomically (no read-modify-write race)
     *   - Use `Firebase.auth.uid` as the source of truth (no caller-passed `currentUserID`)
     *
     * @return the new storyID, or null on failure
     */
    suspend fun postStory(localImageUri: Uri, storyCaption: String): String?

    /**
     * Mark a story as viewed by the current user. Idempotent: viewing twice
     * doesn't bump the count.
     */
    suspend fun watchStory(storyAuthor: String, storyID: String)

    /**
     * Delete a story owned by the current user. Removes:
     *   - The Storage object at `story/{authorID}/{storyID}`
     *   - The Story doc at `story/content/{authorID}/{storyID}`
     *   - Decrements `story_details/{authorID}.storyCount` (atomic, via FieldValue.increment(-1))
     *   - If `storyCount` hits 0, deletes the `story_details/{authorID}` doc entirely
     *   - Removes the viewer subcollection `story/viewers/{authorID}/{storyID}/*`
     */
    suspend fun deleteStory(storyID: String): Boolean

    /**
     * Send a reply to a story. Writes to `story/replies/{authorID}/{storyID}/{autoID}`.
     */
    suspend fun sendStoryReply(
        storyAuthor: String,
        storyID: String,
        replyText: String
    ): Boolean

    /**
     * Get the list of viewer UIDs for an author's stories. Returns a map of
     * storyID → list of viewerUIDs.
     */
    suspend fun getStoryViewers(authorID: String, storyID: String): List<String>
}
