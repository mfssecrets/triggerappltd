package com.trigger.app.chats.presentation.all_chats.screens

import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.trigger.app.R
import com.trigger.app.chats.presentation.all_chats.components.ChatPreview
import com.trigger.app.chats.presentation.view_profile_pic.ControlBlurOnScreen
import com.trigger.app.core.presentation.ui.ActualChat
import com.trigger.app.core.presentation.ui.SelectContact
import com.trigger.app.core.presentation.ui.MessageRequests
import com.trigger.app.core.presentation.ui.Groups
import com.trigger.app.core.presentation.ui.Settings
import com.trigger.app.core.presentation.ui.components.AppBottomBar
import com.trigger.app.core.presentation.ui.components.BottomBars
import com.trigger.app.core.presentation.ui.components.DefaultScreen
import com.trigger.app.core.presentation.ui.components.TintedAppBarIcon
import com.trigger.app.core.presentation.ui.navigateSafely
import com.trigger.app.core.presentation.ui.theme.AppTheme
import com.trigger.app.core.presentation.ui.theme.LocalAppColors
import com.trigger.app.core.presentation.ui.theme.QuickSand
import com.trigger.app.core.presentation.ui.theme.StatusBars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun AllChatsScreen(
    navController: NavController,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
    updateStatusBar: (StatusBars) -> Unit
) {
    val viewModel = viewModel<AllChatsViewModel>()
    val context = LocalContext.current

    val chats by viewModel.chats.collectAsState(initial = null)
    var isProfilePicFullScreen by remember {
        mutableStateOf<String?>(null)
    }


    val appColors = LocalAppColors.current

    // Reset the status bar color to the background color
    LaunchedEffect(key1 = Unit) {
        updateStatusBar(StatusBars(appColors.blueCardColor, false))
    }

    // Permission launcher declared at the top of the composable so the top app bar
    // can launch it from the new "Start a chat" (+) icon. Previously it was
    // declared inside the DefaultScreen content lambda and triggered by a FAB
    // in the bottom bar; the bottom bar no longer has a FAB.
    val permissionRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted)
            navController.navigateSafely(SelectContact)
        else
            coroutineScope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.read_contacts_permission))
            }
    }


    DefaultScreen(
        appBar = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
            ) {

                TintedAppBarIcon(
                    modifier = Modifier.align(Alignment.CenterStart),
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = stringResource(R.string.open_menu),
                    onClick = {
                        navController.navigateSafely(
                            route = Settings
                        )
                    }
                )

                // Top-right action group: Groups (sub-page) + Mail (Message Requests) + Add (Start a chat).
                // Groups has been moved here from the bottom nav per the new 5-tab layout
                // (Chats | Stories | Calls | Notifications | Profile). The + action used to
                // be a center-docked FAB in the bottom bar; it has moved here too.
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Groups sub-page icon — uses R.drawable.groups painter
                    // (Icons.Rounded.Group/Groups not in core Material icons set).
                    Icon(
                        painter = painterResource(id = R.drawable.groups),
                        contentDescription = stringResource(R.string.groups),
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(LocalAppColors.current.blueCardColor)
                            .padding(5.dp)
                            .clickable { navController.navigateSafely(Groups) },
                        tint = MaterialTheme.colorScheme.onSurface
                    )

                    // Wrap Mail icon + unread badge in a Box so Modifier.align works.
                    Box {
                        TintedAppBarIcon(
                            imageVector = Icons.Rounded.Email,
                            contentDescription = stringResource(R.string.message_requests),
                            onClick = {
                                navController.navigateSafely(MessageRequests)
                            }
                        )

                        // Unread message count badge on the Mail icon.
                        val unreadCount = chats?.sumOf { it.unreadMessagesCount } ?: 0
                        if (unreadCount > 0) {
                            androidx.compose.material3.Badge(
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) {
                                Text(
                                    text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                                    fontSize = 10.sp,
                                    fontFamily = QuickSand,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    TintedAppBarIcon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.start_a_chat),
                        onClick = {
                            permissionRequestLauncher.launch(Manifest.permission.READ_CONTACTS)
                        }
                    )
                }


                Text(
                    text = stringResource(id = R.string.messages),
                    fontFamily = QuickSand,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.Center)
                )

//                TODO: Implement search functionality
//                TintedAppBarIcon(
//                    imageVector = Icons.Rounded.Search,
//                    contentDescription = stringResource(R.string.search),
//                    onClick = {
//
//                    }
//                )
            }
        },
        backgroundColor = LocalAppColors.current.mainBackground
    ) {

        ControlBlurOnScreen(
            isPictureOnFullScreen = isProfilePicFullScreen != null,
            profilePic = isProfilePicFullScreen,
            dismissPicture = { isProfilePicFullScreen = null }
        ) {
            Column(Modifier.fillMaxSize()) {

                Box(
                    modifier = Modifier
                        .weight(1f)
                ) {
                    // Loading state — chats haven't been fetched yet (initial value is null).
                    // Show a spinner instead of a blank screen.
                    if (chats == null) {
                        androidx.compose.foundation.layout.Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                    // The user has NO CHATS
                    else if (chats?.isEmpty() == true) {
                        // Truly centered empty state — Column fills the Box and centers
                        // its children both vertically and horizontally. Previously the
                        // Column only had `.align(Alignment.Center)` without `fillMaxSize`,
                        // which biased the icon+text combo slightly upward (icon at ~40%
                        // from top, text at ~50%) — looked top-heavy.
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.start_chat),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(60.dp),
                                colorFilter = ColorFilter.tint(LocalAppColors.current.appThemeTextColor)
                            )

                            Text(
                                text = stringResource(R.string.click_the_button_to_start_a_conversation),
                                fontFamily = QuickSand,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(0.85f),
                                modifier = Modifier
                                    .fillMaxWidth(0.7f)
                                    .padding(top = 24.dp),
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    // The user has CHATS
                    else if (chats?.isNotEmpty() == true) {
                        LazyColumn(
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(chats!!) { chat ->
                                ChatPreview(
                                    chat = chat,
                                    modifier = Modifier,
                                    openProfilePic = { profilePic ->
                                        isProfilePicFullScreen = profilePic ?: ""
                                    },
                                    openChat = {
                                        navController.navigateSafely(
                                            ActualChat(chat.chatID, null)
                                        )
                                    }
                                )
                            }
                        }
                    }


//                    Card(
//                        modifier = Modifier
//                            .align(Alignment.BottomEnd)
//                            .padding(horizontal = 8.dp, vertical = 16.dp)
//                            .clickable {
//                                permissionRequestLauncher.launch(Manifest.permission.READ_CONTACTS)
//                            },
//                        shape = RoundedCornerShape(12.dp),
//                        colors = CardDefaults.cardColors(
//                            containerColor = LocalAppColors.current.fabContainerColor,
//                            contentColor = Color.White
//                        ),
//                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
//                    ) {
//                        Icon(
//                            imageVector = Icons.Rounded.Add,
//                            contentDescription = stringResource(R.string.start_a_chat),
//                            modifier = Modifier
//                                .size(48.dp)
//                                .padding(8.dp)
//                        )
//                    }
                }

                AppBottomBar(
                    currentBottomBar = BottomBars.AllChats,
                    navController = navController
                )
            }
        }
    }
}


@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview
@Composable
private fun PreviewAllChatsScreen() = AppTheme(darkTheme = true) {
    val snackbarHostState = SnackbarHostState()

    Scaffold(snackbarHost = { SnackbarHost(hostState = snackbarHostState) }) {
        AllChatsScreen(
            navController = rememberNavController(),
            snackbarHostState = snackbarHostState,
            coroutineScope = rememberCoroutineScope()
        ) {}
    }
}