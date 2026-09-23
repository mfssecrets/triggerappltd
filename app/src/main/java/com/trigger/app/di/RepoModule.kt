package com.trigger.app.di

import com.trigger.app.auth.repo.AuthRepo
import com.trigger.app.auth.repo.AuthRepoImpl
import com.trigger.app.chats.repo.chats.ChatRepo
import com.trigger.app.chats.repo.chats.ChatRepoImpl
import com.trigger.app.chats.repo.contacts.ContactsRepo
import com.trigger.app.chats.repo.contacts.ContactsRepoImpl
import com.trigger.app.chats.repo.messages.MessagesRepo
import com.trigger.app.chats.repo.messages.MessagesRepoImpl
import com.trigger.app.chats.repo.unread_messages.UnreadMessagesRepo
import com.trigger.app.chats.repo.unread_messages.UnreadMessagesRepoImpl
import com.trigger.app.core.repo.moderation.ModerationRepo
import com.trigger.app.core.repo.moderation.ModerationRepoImpl
import com.trigger.app.core.repo.user.UserRepo
import com.trigger.app.core.repo.user.UserRepoImpl
import com.trigger.app.core.repo.user_details.UserDetailsRepo
import com.trigger.app.core.repo.user_details.UserDetailsRepoImpl
import com.trigger.app.local.ChatSyncRepository
import com.trigger.app.local.MediaDownloadManager
import com.trigger.app.local.MessageSyncRepository
import com.trigger.app.local.UserSyncRepository
import com.trigger.app.stories.repo.StoryRepo
import com.trigger.app.stories.repo.StoryRepoImpl
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module


val repoModule = module {

    // Users
    single<UserRepo> { UserRepoImpl() }
    single<UserDetailsRepo> { UserDetailsRepoImpl() }
    single<ContactsRepo> { ContactsRepoImpl(get(), get()) }

    // Auth
    single<AuthRepo> { AuthRepoImpl() }

    // Moderation
    single<ModerationRepo> { ModerationRepoImpl() }

    // Chats
    single<ChatRepo> { ChatRepoImpl(get()) }
    single<MessagesRepo> { MessagesRepoImpl(get()) }
    single<UnreadMessagesRepo> { UnreadMessagesRepoImpl(get(), get()) }

    // Story
    single<StoryRepo> { StoryRepoImpl(get(), get(), androidContext()) }

    // Offline-first sync layer (Firestore snapshot → Room cache → UI)
    single<MessageSyncRepository> { MessageSyncRepository(get(), get()) }
    single<ChatSyncRepository> { ChatSyncRepository(get(), get()) }
    single<UserSyncRepository> { UserSyncRepository(get(), get()) }
    single<MediaDownloadManager> { MediaDownloadManager(get(), androidContext()) }
}
