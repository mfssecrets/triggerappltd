package com.trigger.app.notifications

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.trigger.app.R
import com.trigger.app.chats.repo.unread_messages.UnreadMessagesRepo
import com.trigger.app.chats.repo.unread_messages.UnreadMessagesRepoImpl
import com.trigger.app.chats.repo.unread_messages.models.UnreadMessages
import com.trigger.app.core.repo.user.UserRepo
import com.trigger.app.core.repo.user.UserRepoImpl
import com.trigger.app.notifications.models.toNotiMessage
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber


class ReplyService(
    private val userRepo: UserRepo = UserRepoImpl(),
    private val unreadMessagesRepo: UnreadMessagesRepo = UnreadMessagesRepoImpl()
) : Service() {

    private val appNotificationManager: AppNotificationManager by lazy {
        AppNotificationManager(applicationContext)
    }

    private val job = Job()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + job)


    private var latestUnreadMessages = listOf<UnreadMessages>()


    override fun onBind(intent: Intent?): IBinder? = null


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand on!!")

        // Per the foreground-service contract on API 26+, we MUST call
        // startForeground() within 5 seconds of being started via
        // startForegroundService(). Otherwise the OS throws
        // ForegroundServiceDidNotStartInTimeException.
        startForegroundSafely()

        coroutineScope.launch {
            // Confirm UID has an account (user finished setting up his profile)
            val isUserCompletelySignedIn = Firebase.auth.uid?.let { userRepo.getUserFromUID(it) } != null
            if (!isUserCompletelySignedIn)
                return@launch

            try {
                Timber.d("Reply service still activated")

                unreadMessagesRepo.getUnreadMessages().collectLatest { unreadMessagesList ->
                    Timber.d("unreadMessagesRepo.getUnreadMessages().collectLatest is $unreadMessagesList")

                    unreadMessagesList.forEach { unreadMessages ->
                        appNotificationManager.sendNotification(
                            chatID = unreadMessages.chatID,
                            listOfNotiMessage = unreadMessages.listOfMessages.map {
                                it.toNotiMessage(
                                    unreadMessages.chatID
                                )
                            },
                            currentUser = unreadMessages.currentMiniUser,
                            otherUser = unreadMessages.otherMiniUser
                        )
                    }
                }
            } catch (fe: FirebaseFirestoreException) {
                Timber.e(fe)
            }
        }

        return START_STICKY
    }

    /**
     * Calls [startForeground] with a low-priority "Trigger App is running"
     * notification. Required on API 26+ where [Context.startForegroundService]
     * was used to start this service.
     */
    private fun startForegroundSafely() {
        // Ensure the notification channel exists BEFORE we try to post a
        // notification to it. Otherwise startForeground() throws
        // BadNotificationException on Android 8+.
        val notificationManagerCompat = androidx.core.app.NotificationManagerCompat.from(this)
        val channel = androidx.core.app.NotificationChannelCompat.Builder(
            AppNotificationManager.CHAT_MESSAGES_CHANNEL_ID,
            android.app.NotificationManager.IMPORTANCE_LOW
        )
            .setName(getString(R.string.chat_messages))
            .setDescription(getString(R.string.channel_for_direct_messages))
            .build()
        if (notificationManagerCompat.getNotificationChannel(AppNotificationManager.CHAT_MESSAGES_CHANNEL_ID) == null) {
            notificationManagerCompat.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(
            this,
            AppNotificationManager.CHAT_MESSAGES_CHANNEL_ID
        )
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.foreground_service_reply_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        // Android 14 (API 34) requires the foregroundServiceType to be passed
        // to startForeground(). We use FOREGROUND_SERVICE_TYPE_DATA_SYNC which
        // fits the "listen for incoming Firestore changes" use case.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                REPLY_FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(REPLY_FOREGROUND_NOTIFICATION_ID, notification)
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        coroutineScope.cancel()
    }

    companion object {
        private const val REPLY_FOREGROUND_NOTIFICATION_ID = 4242
    }
}
