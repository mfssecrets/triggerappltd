package com.trigger.app.di

import android.content.Context
import androidx.room.Room
import com.trigger.app.chats.repo.contacts.local.AppDatabase
import com.trigger.app.chats.repo.contacts.local.dao.LocalContactDao
import com.trigger.app.local.ChatDao
import com.trigger.app.local.MediaFileDao
import com.trigger.app.local.MessageDao
import com.trigger.app.local.TriggerDatabase
import com.trigger.app.local.UserDao
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val localModule = module {

    single<AppDatabase> { providesAppDatabase(androidContext()) }
    single<LocalContactDao> { providesLocalContactDao(get()) }

    // Offline-first cache database — stores messages, chats, users, media paths.
    single<TriggerDatabase> { providesTriggerDatabase(androidContext()) }
    single<MessageDao> { get<TriggerDatabase>().messageDao() }
    single<ChatDao> { get<TriggerDatabase>().chatDao() }
    single<UserDao> { get<TriggerDatabase>().userDao() }
    single<MediaFileDao> { get<TriggerDatabase>().mediaFileDao() }
}

fun providesLocalContactDao(appDatabase: AppDatabase) = appDatabase.localContactDao


fun providesAppDatabase(context: Context) =
    Room.databaseBuilder(
        context = context,
        klass = AppDatabase::class.java,
        name = "AppDatabase"
    ).fallbackToDestructiveMigration().build()


fun providesTriggerDatabase(context: Context) =
    Room.databaseBuilder(
        context = context,
        klass = TriggerDatabase::class.java,
        name = TriggerDatabase.DATABASE_NAME
    ).fallbackToDestructiveMigration().build()