package com.trigger.app

import android.app.Application
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.firestoreSettings
import com.google.firebase.ktx.Firebase
import com.trigger.app.di.initKoin
import com.trigger.app.notifications.AppNotificationManager
import timber.log.Timber

class App : Application() {

    private val appNotificationManager by lazy {
        AppNotificationManager(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())

        // FIX #10: Enable Firestore offline persistence explicitly with a larger
        // cache (50MB). Firestore persistence is ON by default on Android, but
        // setting it explicitly + bumping the cache size ensures offline reads
        // work reliably for chats with many messages.
        val settings = firestoreSettings {
            isPersistenceEnabled = true
            cacheSizeBytes = 50L * 1024 * 1024  // 50 MB
        }
        Firebase.firestore.firestoreSettings = settings

        initKoin()

        appNotificationManager.clearNotifications()
    }
}