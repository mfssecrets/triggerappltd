package com.trigger.app.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.trigger.app.auth.ui.screens.create_profile.CreateProfileScreen
import com.trigger.app.auth.ui.screens.enter_code.EnterCodeScreen
import com.trigger.app.auth.ui.screens.enter_number.EnterNumberScreen
import com.trigger.app.calls.ui.screens.CallsScreen
import com.trigger.app.chats.presentation.actual_chat.screens.ActualChatScreen
import com.trigger.app.chats.presentation.actual_chat.screens.send_image.SendImageScreen
import com.trigger.app.chats.presentation.actual_chat.screens.view_image.ViewImageScreen
import com.trigger.app.chats.presentation.all_chats.screens.AllChatsScreen
import com.trigger.app.chats.presentation.chat_details.screens.ChatDetailsScreen
import com.trigger.app.chats.presentation.select_contact.screens.SelectContactScreen
import com.trigger.app.core.presentation.ui.ActualChat
import com.trigger.app.core.presentation.ui.AllChats
import com.trigger.app.core.presentation.ui.Calls
import com.trigger.app.core.presentation.ui.ChatDetails
import com.trigger.app.core.presentation.ui.CreateProfile
import com.trigger.app.core.presentation.ui.EnterCode
import com.trigger.app.core.presentation.ui.EnterNumber
import com.trigger.app.core.presentation.ui.Groups
import com.trigger.app.core.presentation.ui.MyProfile
import com.trigger.app.core.presentation.ui.SelectContact
import com.trigger.app.core.presentation.ui.SendImage
import com.trigger.app.core.presentation.ui.SendImageIn
import com.trigger.app.core.presentation.ui.Settings
import com.trigger.app.core.presentation.ui.Stories
import com.trigger.app.core.presentation.ui.ViewImage
import com.trigger.app.core.presentation.ui.Welcome
import com.trigger.app.core.presentation.ui.navigateSafely
import com.trigger.app.core.presentation.ui.theme.AppTheme
import com.trigger.app.core.presentation.ui.theme.changeStatusBarColor
import com.trigger.app.groups.ui.screens.GroupsScreen
import com.trigger.app.network_moniter.UserStatusMoniter
import com.trigger.app.notifications.AppNotificationManager
import com.trigger.app.notifications.ReplyService
import com.trigger.app.notifications.UnreadMessagesService
import com.trigger.app.settings.presentation.screens.profile.ProfileScreen
import com.trigger.app.settings.presentation.screens.settings.SettingsScreen
import com.trigger.app.stories.ui.screens.all_stories.StoriesScreen
import com.trigger.app.welcome.WelcomeScreen
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private var userStatusMoniter = UserStatusMoniter()
    private val replyService by lazy { ReplyService() }
    private val unreadMessagesService by lazy { UnreadMessagesService() }


    val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            AppTheme {
                val snackbarHostState = SnackbarHostState()

                val coroutineScope = rememberCoroutineScope()
                val navController = rememberNavController()

                // Controls the status bars
                val statusBars by viewModel.statusBars.collectAsState()
                val view = LocalView.current
                LaunchedEffect(key1 = statusBars) {
                    changeStatusBarColor(view, statusBars?.barColor, statusBars?.useDarkIcons)
                }


                LaunchedEffect(key1 = Unit) {
//                    if (Firebase.auth.uid == null)
//                        navController.navigate(Welcome)

                    val currentUser = viewModel.fetchCurrentUser()

                    if (currentUser?.uid == null && Firebase.auth.uid != null) { // User registered but didn't create their profile
                        // Navigate to the CreateProfile screen
                        val phone = Firebase.auth.currentUser?.phoneNumber ?: ""
                        navController.navigateSafely(CreateProfile(phone))
                    }
                }

                val notificationChatID = remember { intent?.getStringExtra(AppNotificationManager.NOTIFICATION_CHAT_ID) }
                LaunchedEffect(key1 = Unit) {
                    Timber.d("notificationChatID is $notificationChatID")
                    if (notificationChatID != null)
                        navController.navigate(ActualChat(notificationChatID, null))
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
                ) { innerPadding ->

                    Column(Modifier.padding(innerPadding)) {

                        NavHost(
                            navController = navController,
                            startDestination = if (Firebase.auth.uid == null) Welcome else AllChats
//                            startDestination = AllChats// if (Firebase.auth.uid == null) Welcome else AllChats
                        ) {

                            composable<Welcome> {
                                WelcomeScreen(navController = navController, viewModel::updateStatusBar)
                            }

                            composable<EnterNumber> {
                                EnterNumberScreen(navController, viewModel::updateStatusBar)
                            }

                            composable<EnterCode> {
                                val args = it.toRoute<EnterCode>()

                                EnterCodeScreen(
                                    navController = navController,
                                    phoneNumberWithCountryCode = args.phoneNumberWithCountryCode,
                                    snackbarHostState = snackbarHostState
                                )
                            }

                            composable<CreateProfile> {
                                val args = it.toRoute<CreateProfile>()

                                CreateProfileScreen(
                                    navController = navController,
                                    snackbarHostState = snackbarHostState,
                                    phoneNumber = args.phoneNumber
                                )
                            }


                            composable<SelectContact> {
                                SelectContactScreen(
                                    context = LocalContext.current,
                                    navController = navController
                                )
                            }


                            composable<Settings> {
                                SettingsScreen(navController = navController)
                            }
                            composable<MyProfile> {
                                ProfileScreen(navController = navController)
                            }


                            composable<Groups> {
                                GroupsScreen(navController = navController)
                            }
                            composable<Calls> {
                                CallsScreen(navController = navController)
                            }


                            composable<Stories> {
                                StoriesScreen(navController = navController, coroutineScope = coroutineScope)
                            }
//                            composable<ViewStory> {
//                                val args = it.toRoute<ViewStory>()
//                                ViewStoryScreen(authorID = args.authorID)
//                            }



                            composable<AllChats> {
                                AllChatsScreen(
                                    navController = navController,
                                    snackbarHostState = snackbarHostState,
                                    coroutineScope = coroutineScope,
                                    updateStatusBar = viewModel::updateStatusBar
                                )
                            }
                            composable<ActualChat> {
                                val args = it.toRoute<ActualChat>()

                                ActualChatScreen(
                                    navController = navController,
                                    chatID = args.chatId,
                                    newContact = args.newContact,
                                    coroutineScope = coroutineScope,
                                    snackbarHostState = snackbarHostState,
                                    updateStatusBar = viewModel::updateStatusBar
                                )
                            }


                            composable<ChatDetails> {
                                val args = it.toRoute<ChatDetails>()

                                ChatDetailsScreen(
                                    chatID = args.chatId,
                                    otherUserID = args.otherUserId,
                                    navController = navController,
                                    updateStatusBar = viewModel::updateStatusBar
                                )
                            }

                            composable<SendImage>(
//                                TODO: Find a way to change this
//                                typeMap = mapOf(typeOf<SendImageIn>() to parcelableType<SendImageIn>())
                            ) {
                                val args = it.toRoute<SendImage>()
                                val chatID = args.chatId

                                SendImageScreen(
                                    navController = navController,
                                    imageUri = args.imageUri,
                                    sendImageIn = if (chatID != null) SendImageIn.Chat(chatID) else SendImageIn.Story
//                                    onSendImage = args.onSendImage
                                )
                            }


                            composable<ViewImage> {
                                val args = it.toRoute<ViewImage>()
                                ViewImageScreen(imageUrl = args.imageUrl, navController = navController)
                            }

                        }
                    }
                }
            }
        }
    }


    override fun onStart() {
        super.onStart()
        userStatusMoniter.moniter()

        replyService.stopSelf()
        startService(Intent(this, UnreadMessagesService::class.java))
    }

    override fun onStop() {
        super.onStop()
        userStatusMoniter.removeMoniter()

        startService(Intent(this, ReplyService::class.java))
        unreadMessagesService.stopSelf()
    }
}
