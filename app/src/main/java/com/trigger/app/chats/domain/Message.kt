package com.trigger.app.chats.domain

import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

data class Message(
    val senderID: String,
    val messageID: String,
    var messageType: Map<String, Any>,
    var timeSent: Long,
    var messageStatus: MessageStatus,
    var wasEdited: Boolean = false,
    // FIX: @ServerTimestamp — Firestore SDK automatically replaces this with the
    // server's authoritative timestamp on write. Cannot be spoofed by the client.
    // Used for conflict resolution (lastWriteWins): when the sync layer compares
    // local vs server versions, it uses serverTime (not timeSent).
    // timeSent stays Long (client-side) for immediate UI display while offline.
    @ServerTimestamp var serverTime: Date? = null
) {

    constructor() : this(
        "",
        "",
        MessageType.Text("").toFirebaseMap(),
        0,
        MessageStatus.SENT,
        false,
        null
    )


    companion object {
        val TEST_MY_Message = Message(
            senderID = "me",
            messageID = "message01",
            messageType = MessageType.Text("Hey there").toFirebaseMap(),
            timeSent = 12345678,
            messageStatus = MessageStatus.SENT
        )
        val LONG_TEST_MY_Message = Message(
            senderID = "me",
            messageID = "message01",
            messageType = MessageType.Text("Hey there. I'm Andrew. We met during the joint prefect's hike at Ngong hills.").toFirebaseMap(),
            timeSent = 12345678,
            messageStatus = MessageStatus.SENT
        )
        val TEST_OTHER_Message =
            Message(
                senderID = "you",
                messageID = "message01",
                messageType = MessageType.Text("Hey there").toFirebaseMap(),
                timeSent = 12345678,
                messageStatus = MessageStatus.SENT
            )
        val LONG_TEST_OTHER_Message = Message(
            senderID = "you",
            messageID = "message01",
            messageType = MessageType.Text("Hey there. I'm Andrew. We met during the joint prefect's hike at Ngong hills.").toFirebaseMap(),
            timeSent = 12345678,
            messageStatus = MessageStatus.SENT
        )

        val TEST_LIST_OF_CHATS = listOf(
            TEST_MY_Message, TEST_OTHER_Message,
            LONG_TEST_MY_Message, TEST_OTHER_Message, LONG_TEST_OTHER_Message,
            TEST_MY_Message
        )
    }

}

enum class MessageStatus {
    NOT_SENT,
    SENT,
    RECEIVED,
    OPENED
}
