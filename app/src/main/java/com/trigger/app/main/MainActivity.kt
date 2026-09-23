package com.trigger.app.main

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.trigger.app.auth.ui.screens.create_profile.CreateProfileScreen
import com.trigger.app.auth.ui.screens.create_username.CreateUsernameScreen
import com.trigger.app.auth.ui.screens.enter_code.EnterCodeScreen
import com.trigger.app.auth.ui.screens.enter_number.EnterNumberScreen
import com.trigger.app.calls.ui.screens.CallsScreen
import com.trigger.app.chats.presentation.actual_chat.screens.ActualChatScreen
import com.trigger.app.chats.presentation.actual_chat.screens.send_image.SendImageScreen
import com.trigger.app.chats.presentation.actual_chat.screens.view_image.ViewImageScreen
import com.trigger.app.chats.presentation.all_chats.screens.AllChatsScreen
import com.trigger.app.chats.presentation.chat_details.screens.ChatDetailsScreen
import com.trigger.app.chats.presentation.select_contact.screens.SelectContactScreen
import com.trigger.app.chats.presentation.message_requests.MessageRequestsScreen
import com.trigger.app.notifications.ui.screens.NotificationsScreen
import com.trigger.app.core.presentation.ui.ActualChat
import com.trigger.app.core.presentation.ui.AllChats
import com.trigger.app.core.presentation.ui.Calls
import com.trigger.app.core.presentation.ui.ChatDetails
import com.trigger.app.core.presentation.ui.CreateProfile
import com.trigger.app.core.presentation.ui.CreateUsername
import com.trigger.app.core.presentation.ui.EnterCode
import com.trigger.app.core.presentation.ui.EnterNumber
import com.trigger.app.core.presentation.ui.Groups
import com.trigger.app.core.presentation.ui.Help
import com.trigger.app.core.presentation.ui.MyProfile
import com.trigger.app.core.presentation.ui.Notifications
import com.trigger.app.core.presentation.ui.MessageRequests
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
import com.trigger.app.settings.presentation.screens.privacy.BlockedUsersScreen
import com.trigger.app.settings.presentation.screens.privacy.CreatePageScreen
import com.trigger.app.settings.presentation.screens.privacy.PrivacySettingsScreen
import com.trigger.app.settings.presentation.screens.privacy.ReportUserScreen
import com.trigger.app.settings.presentation.screens.privacy.VerifiedBadgeScreen
import com.trigger.app.settings.presentation.screens.privacy.HelpScreen
import com.trigger.app.core.presentation.ui.PrivacySettings
import com.trigger.app.core.presentation.ui.BlockedUsers
import com.trigger.app.core.presentation.ui.VerifiedBadge
import com.trigger.app.core.presentation.ui.CreateMyPage
import com.trigger.app.core.presentation.ui.ReportUser
import com.trigger.app.stories.ui.screens.all_stories.StoriesScreen
import com.trigger.app.welcome.WelcomeScreen
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private var userStatusMoniter = UserStatusMoniter()

    val viewModel: MainViewModel by viewModels()

    /**
     * Auth-ready gate. Set true once the [FirebaseAuth.AuthStateListener] has
     * reported the current Auth state for the first time. This prevents the
     * `startDestination` race where `Firebase.auth.uid` was read synchronously
     * at composition — which on cold launch can return null for ~100ms while
     * Firebase Auth restores the current user from disk, causing the user to
     * briefly see the Welcome screen even if they're signed in.
     */
    private val authReady = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val isSignedIn = kotlinx.coroutines.flow.MutableStateFlow(false)

    // FIX #1: Store reference to remove in onDestroy (was leaking — listener
    // accumulated on every Activity recreation).
    private val authStateListener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { auth ->
        val uid = auth.currentUser?.uid
        isSignedIn.value = (uid != null)
        authReady.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Keep the splash screen visible until auth state is ready.
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !authReady.value }

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Register the AuthStateListener (removed in onDestroy).
        Firebase.auth.addAuthStateListener(authStateListener)

        setContent {
            AppTheme {
                val snackbarHostState = SnackbarHostState()

                val coroutineScope = rememberCoroutineScope()
                val navController = rememberNavController()

                val statusBars by viewModel.statusBars.collectAsState()
                val view = LocalView.current
                LaunchedEffect(key1 = statusBars) {
                    changeStatusBarColor(view, statusBars?.barColor, statusBars?.useDarkIcons)
                }

                // Gate the entire NavHost on authReady — splash screen stays
                // up until Firebase Auth has reported its first state.
                val ready by authReady.collectAsState()
                val signedIn by isSignedIn.collectAsState()

                if (!ready) {
                    // Splash screen is still up; render an empty box as a placeholder.
                    Box(Modifier.fillMaxSize())
                    return@AppTheme
                }

                // Capture the initial start destination ONCE, the first time
                // auth becomes ready. We must NOT make `startDestination` reactive
                // to `signedIn` — that was the bug. When a new user completed phone
                // auth mid-session, `isSignedIn` flipped from false → true, the
                // NavHost recomposed with the new `startDestination = AllChats`,
                // and the back stack was re-initialised to AllChats — silently
                // bypassing the entire onboarding flow (CreateProfile → CreateUsername)
                // and dumping the new user on the dashboard with no profile doc.
                //
                // After this point, `signedIn` is only used for the splash gate
                // and the post-auth profile-check LaunchedEffect below — never
                // for `startDestination`.
                val initialStartRoute = remember { mutableStateOf<Any?>(null) }
                LaunchedEffect(ready) {
                    if (ready && initialStartRoute.value == null) {
                        initialStartRoute.value =
                            if (Firebase.auth.uid != null) AllChats else Welcome
                    }
                }
                val startRoute = initialStartRoute.value
                if (startRoute == null) {
                    // First frame after `ready=true` — wait one more frame for
                    // `initialStartRoute` to be populated by the LaunchedEffect
                    // above.
                    Box(Modifier.fillMaxSize())
                    return@AppTheme
                }

                // Post-auth profile-completeness check: if the user is signed in
                // but has no `users/{uid}` doc yet (signed up but didn't finish
                // onboarding), send them to CreateProfile. This fires only ONCE
                // per Activity composition.
                LaunchedEffect(key1 = Unit) {
                    if (Firebase.auth.uid != null) {
                        val currentUser = viewModel.fetchCurrentUser()
                        if (currentUser?.uid == null) {
                            val phone = Firebase.auth.currentUser?.phoneNumber ?: ""
                            navController.navigateSafely(CreateProfile(phone))
                        }
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
                            startDestination = startRoute
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

                            composable<CreateUsername> {
                                val args = it.toRoute<CreateUsername>()

                                CreateUsernameScreen(
                                    navController = navController,
                                    route = args,
                                    snackbarHostState = snackbarHostState
                                )
                            }


                            composable<SelectContact> {
                                SelectContactScreen(
                                    context = LocalContext.current,
                                    navController = navController
                                )
                            }
                            composable<MessageRequests> {
                                MessageRequestsScreen(navController)
                            }


                            composable<Settings> {
                                SettingsScreen(navController = navController)
                            }
                            composable<MyProfile> {
                                ProfileScreen(navController = navController)
                            }
                            composable<Help> {
                                HelpScreen(navController)
                            }
                            composable<PrivacySettings> {
                                PrivacySettingsScreen(navController)
                            }
                            composable<BlockedUsers> {
                                BlockedUsersScreen(navController)
                            }
                            composable<VerifiedBadge> {
                                VerifiedBadgeScreen(navController)
                            }
                            composable<CreateMyPage> {
                                CreatePageScreen(navController)
                            }
                            composable<ReportUser> {
                                ReportUserScreen(navController, it.toRoute<ReportUser>().userID)
                            }


                            composable<Groups> {
                                GroupsScreen(navController = navController)
                            }
                            composable<Calls> {
                                CallsScreen(navController = navController)
                            }
                            composable<Notifications> {
                                // FIX: was routing to MessageRequestsScreen — that's
                                // a DIFFERENT feature (incoming chat requests from
                                // non-contacts). Notifications tab now routes to the
                                // dedicated NotificationsScreen which will hold the
                                // notification feed (new messages, missed calls,
                                // story replies, etc.).
                                NotificationsScreen(navController)
                            }


                            composable<Stories> {
                                StoriesScreen(navController = navController, coroutineScope = coroutineScope)
                            }


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

                            composable<SendImage> {
                                val args = it.toRoute<SendImage>()
                                val chatID = args.chatId

                                SendImageScreen(
                                    navController = navController,
                                    imageUri = args.imageUri,
                                    sendImageIn = if (chatID != null) SendImageIn.Chat(chatID) else SendImageIn.Story
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

        // Migrated from startService() to startForegroundService() on API 26+.
        // ReplyService + UnreadMessagesService each call startForeground() within
        // 5s of onCreate, which is required for foreground-service starts from a
        // backgrounded Activity (otherwise the OS throws IllegalStateException).

        // Stop the ReplyService that was (likely) started in the previous onStop()
        // — we're now in the foreground, so the user-visible notification flow
        // takes over from the background listener.
        stopService(Intent(this, ReplyService::class.java))

        // Start the UnreadMessagesService in foreground mode (it will be stopped
        // in onStop()).
        startForegroundServiceSafely(UnreadMessagesService::class.java)
    }

    override fun onStop() {
        super.onStop()
        userStatusMoniter.removeMoniter()

        // Stop the UnreadMessagesService started in onStart().
        stopService(Intent(this, UnreadMessagesService::class.java))

        // Start the ReplyService in foreground mode (it will be stopped in
        // onStart() when the user comes back to the foreground).
        startForegroundServiceSafely(ReplyService::class.java)
    }

    /**
     * Helper that uses [startForegroundService] on API 26+ (Oreo) and falls back
     * to the deprecated [startService] on older API levels. The target service
     * MUST call `startForeground(id, notification)` within 5 seconds of being
     * started, otherwise the OS throws ForegroundServiceDidNotStartInTimeException.
     */
    private fun startForegroundServiceSafely(serviceClass: Class<out android.app.Service>) {
        val intent = Intent(this, serviceClass)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // FIX #1: Remove the AuthStateListener to prevent memory leak.
    // Without this, every Activity recreation adds another listener that
    // never gets removed.
    override fun onDestroy() {
        super.onDestroy()
        Firebase.auth.removeAuthStateListener(authStateListener)
    }
}
