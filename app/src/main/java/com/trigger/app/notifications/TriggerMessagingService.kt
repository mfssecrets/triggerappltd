package com.trigger.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import com.trigger.app.main.MainActivity
import timber.log.Timber

/**
 * Receives FCM push notifications from Cloud Functions.
 *
 * Payload types handled:
 *   - "message" — new chat message. Shows a notification with sender name +
 *     preview. Tapping opens the chat.
 *   - "call" — incoming audio/video call. Posts a high-priority notification
 *     with a full-screen intent that opens IncomingCallScreen.
 *
 * On token refresh, writes the new FCM token to `users/{uid}/deviceTokens`
 * (owner-only — audit fix #7 from the original messaging flow).
 */
class TriggerMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "CHAT_MESSAGES_CHANNEL_ID"
        const val CALL_CHANNEL_ID = "CALLS_CHANNEL_ID"
        const val NOTIFICATION_CHAT_ID = "com.trigger.app.NOTIFICATION_CHAT_ID"

        // Intent extras passed to MainActivity when the user taps / accepts
        // an incoming-call notification.
        const val EXTRA_INCOMING_CALL_ID = "com.trigger.app.EXTRA_INCOMING_CALL_ID"
        const val EXTRA_INCOMING_CALLER_ID = "com.trigger.app.EXTRA_INCOMING_CALLER_ID"
        const val EXTRA_INCOMING_CALL_TYPE = "com.trigger.app.EXTRA_INCOMING_CALL_TYPE"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Timber.d("FCM token refreshed: $token")

        // FIX #7: Write FCM token to users/{uid}/deviceTokens/{token} (owner-only)
        // instead of public_users/{uid}.fcmToken (public-readable). The old
        // location leaked device push tokens to any signed-in user.
        val uid = Firebase.auth.currentUser?.uid ?: return

        // Use the token itself as the document ID — idempotent (re-writing the
        // same token just re-sets the same doc).
        Firebase.firestore
            .collection("users")
            .document(uid)
            .collection("deviceTokens")
            .document(token)
            .set(mapOf("token" to token, "updatedAt" to System.currentTimeMillis()))
            .addOnFailureListener { e ->
                Timber.e(e, "Failed to write FCM token to deviceTokens")
            }

        // Also clean up the old fcmToken from public_users if it exists.
        Firebase.firestore
            .collection("public_users")
            .document(uid)
            .update("fcmToken", com.google.firebase.firestore.FieldValue.delete())
            .addOnFailureListener { /* non-fatal — field may not exist */ }
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
            "call" -> {
                // FIX: real incoming-call FCM handling. Was: unknown FCM type
                // → silently dropped. Now: launches IncomingCallScreen via a
                // full-screen notification intent.
                //
                // Data payload from onCallInitiate Cloud Function:
                //   callID, callerID, callerName, callerProfilePic, callType
                val callID = data["callID"] ?: return
                val callerID = data["callerID"] ?: return
                val callType = data["callType"] ?: "AUDIO"
                val callerName = data["callerName"] ?: "Unknown"
                val callerProfilePic = data["callerProfilePic"]?.ifEmpty { null }

                showIncomingCallNotification(callID, callerID, callerName, callerProfilePic, callType)
            }
            else -> {
                Timber.d("Unknown FCM type: $type")
            }
        }
    }

    /**
     * Show a high-priority notification for an incoming call with a full-screen
     * intent that opens [IncomingCallScreen] directly.
     *
     * On Android 14+ requires `android.permission.USE_FULL_SCREEN_INTENT` to be
     * declared in the manifest (and granted by the user via the system prompt).
     * On older versions, falls back to a regular lock-screen notification with
     * "Answer" / "Decline" actions (TODO — currently just taps open the screen).
     */
    private fun showIncomingCallNotification(
        callID: String,
        callerID: String,
        callerName: String,
        callerProfilePic: String?,
        callType: String
    ) {
        ensureCallNotificationChannel()

        // Build the Intent that opens IncomingCallScreen via MainActivity's
        // deep-link handling. MainActivity reads the extra and navigates to
        // the IncomingCall route.
        val callIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_INCOMING_CALL_ID, callID)
            putExtra(EXTRA_INCOMING_CALLER_ID, callerID)
            putExtra(EXTRA_INCOMING_CALL_TYPE, callType)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            callID.hashCode(),
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Incoming $callType call".lowercase().replaceFirstChar { it.uppercase() })
            .setContentText("$callerName is calling")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)  // can't be swiped away — must answer/decline
            .build()

        try {
            NotificationManagerCompat.from(this)
                .notify(callID.hashCode(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted on Android 13+. Still try to
            // launch the call screen directly — the full-screen intent may
            // still fire because it uses a separate permission.
            Timber.e(e, "showIncomingCallNotification: notification post failed")
            fullScreenPendingIntent.send()
        }
    }

    private fun ensureCallNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CALL_CHANNEL_ID)
            if (existing != null) return

            val channel = NotificationChannel(
                CALL_CHANNEL_ID,
                "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming audio and video calls"
                enableVibration(true)
                enableLights(true)
                // Bypass DND — calls are critical.
                setBypassDnd(true)
                // Lock-screen visibility — show caller name on lock screen.
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
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
