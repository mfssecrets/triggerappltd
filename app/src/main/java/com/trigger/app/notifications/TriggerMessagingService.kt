package com.trigger.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.trigger.app.R
import timber.log.Timber

/**
 * Receives FCM push notifications from the Cloud Function `onNewChatMessage`.
 *
 * Two types of payloads:
 *   - "message" — a new chat message arrived. Shows a notification with the
 *     sender's name + message preview. Tapping opens the chat.
 *   - "default" — system / keep-alive. Silently processed.
 *
 * On token refresh, writes the new FCM token to `public_users/{uid}.fcmToken`
 * so the Cloud Function can look it up to send pushes.
 */
class TriggerMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "CHAT_MESSAGES_CHANNEL_ID"
        const val NOTIFICATION_CHAT_ID = "com.trigger.app.NOTIFICATION_CHAT_ID"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Timber.d("FCM token refreshed: $token")

        // Write the new token to public_users/{uid}.fcmToken so the Cloud
        // Function can look it up to send pushes. Using public_users (not users)
        // because the Firestore rules allow owner write on public_users.
        val uid = Firebase.auth.currentUser?.uid ?: return
        Firebase.firestore
            .collection("public_users")
            .document(uid)
            .update("fcmToken", token)
            .addOnFailureListener { e ->
                // If the doc doesn't exist yet (e.g., user just signed up),
                // create it with just the token. The full projection will be
                // filled in by UserRepoImpl.createUser.
                Firebase.firestore
                    .collection("public_users")
                    .document(uid)
                    .set(mapOf("fcmToken" to token, "uid" to uid), com.google.firebase.firestore.SetOptions.merge())
                    .addOnFailureListener { Timber.e(it, "Failed to write FCM token") }
            }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Timber.d("FCM message received from: ${remoteMessage.from}")

        val data = remoteMessage.data
        val type = data["type"] ?: return

        when (type) {
            "message" -> {
                val chatID = data["chatID"] ?: return
                val senderName = data["senderName"] ?: "New message"
                val messagePreview = data["messagePreview"] ?: ""
                val senderProfilePic = data["senderProfilePic"]

                showChatMessageNotification(chatID, senderName, messagePreview, senderProfilePic)
            }
            else -> {
                Timber.d("Unknown FCM type: $type")
            }
        }
    }

    private fun showChatMessageNotification(
        chatID: String,
        senderName: String,
        messagePreview: String,
        senderProfilePic: String?
    ) {
        // Ensure the notification channel exists (required on API 26+).
        ensureNotificationChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(senderName)
            .setContentText(messagePreview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messagePreview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this)
                .notify(chatID.hashCode(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted on Android 13+. Silently skip —
            // the foreground service (UnreadMessagesService) still fires
            // in-app notifications.
            Timber.e(e, "showChatMessageNotification: POST_NOTIFICATIONS denied")
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.chat_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_for_direct_messages)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
