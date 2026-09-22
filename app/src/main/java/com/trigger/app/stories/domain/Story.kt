package com.trigger.app.stories.domain

data class Story(
    val storyID: String,
    val authorUID: String,
    val imageUrl: String,
    val storyCaption: String,
    val timeUploaded: Long = System.currentTimeMillis(),
    val expiresAt: Long = timeUploaded + (STORY_TTL_HOURS * 60L * 60L * 1000L),
    val totalViewers: Int = 0,
    val viewersIDs: List<String> = listOf()
) {

    constructor(): this(
        storyID = "",
        authorUID = "",
        imageUrl = "",
        storyCaption = "",
        timeUploaded = 0,
        expiresAt = 0,
        totalViewers = 0,
        viewersIDs = listOf()
    )

    companion object {
        /** Story time-to-live in hours. After this elapses, the story is
         *  eligible for deletion by the scheduled Cloud Function. */
        const val STORY_TTL_HOURS = 24L
    }
}
