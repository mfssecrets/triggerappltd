package com.trigger.app.di

import com.trigger.app.chats.repo.chats.ChatRepo
import com.trigger.app.chats.repo.chats.ChatRepoImpl
import com.trigger.app.chats.repo.contacts.ContactsRepo
import com.trigger.app.chats.repo.contacts.ContactsRepoImpl
import com.trigger.app.chats.repo.messages.MessagesRepo
import com.trigger.app.chats.repo.messages.MessagesRepoImpl
import com.trigger.app.core.repo.user.UserRepo
import com.trigger.app.core.repo.user.UserRepoImpl
import com.trigger.app.core.repo.user_details.UserDetailsRepo
import com.trigger.app.core.repo.user_details.UserDetailsRepoImpl
import com.trigger.app.stories.repo.StoryRepo
import com.trigger.app.stories.repo.StoryRepoImpl
import org.koin.dsl.module


val repoModule = module {

    // Users
    single<UserRepo> { UserRepoImpl() }
    single<UserDetailsRepo> { UserDetailsRepoImpl() }
    single<ContactsRepo> { ContactsRepoImpl(get(), get()) }

    // Chats
    single<ChatRepo> { ChatRepoImpl(get()) }
    single<MessagesRepo> { MessagesRepoImpl(get()) }


    // Story
    single<StoryRepo> { StoryRepoImpl(get(), get()) }

}