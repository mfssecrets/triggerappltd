package com.trigger.app.di

import com.trigger.app.chats.presentation.actual_chat.screens.ActualChatViewModel
import com.trigger.app.chats.presentation.all_chats.screens.AllChatsViewModel
import com.trigger.app.chats.presentation.select_contact.screens.SelectContactsViewModel
import com.trigger.app.notifications.ui.screens.NotificationsViewModel
import com.trigger.app.stories.ui.screens.all_stories.StoriesViewModel
import com.trigger.app.stories.ui.screens.view_story.ViewStoryViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {

    viewModel { SelectContactsViewModel(get()) }

    // Offline-first: messageSyncRepository injected → messages read from Room
    // cache (instant) with Firestore sync in background.
    viewModel { ActualChatViewModel(contactsRepo = get(), messageSyncRepository = get()) }

    // Offline-first: chatSyncRepository injected → chats read from Room cache.
    viewModel { AllChatsViewModel(chatRepo = get(), chatSyncRepository = get()) }

    viewModel { StoriesViewModel(get(), get(), get()) }
    viewModel { ViewStoryViewModel(get(), get()) }

    // Notifications feed VM — listens to chat_details + story replies + missed calls.
    viewModel { NotificationsViewModel(get(), get(), get()) }
}
