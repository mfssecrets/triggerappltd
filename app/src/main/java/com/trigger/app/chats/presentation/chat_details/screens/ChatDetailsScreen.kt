package com.trigger.app.chats.presentation.chat_details.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.trigger.app.chats.presentation.chat_details.components.ChatDetailActions
import com.trigger.app.chats.presentation.chat_details.components.ComingSoonPopup
import com.trigger.app.chats.presentation.chat_details.components.RedBorderButton
import com.trigger.app.chats.presentation.view_profile_pic.ControlBlurOnScreen
import com.trigger.app.core.presentation.ui.components.DefaultScreen
import com.trigger.app.core.presentation.ui.ReportUser
import com.trigger.app.core.presentation.ui.components.LoadingSpinner
import com.trigger.app.core.presentation.ui.components.UserIcon
import com.trigger.app.core.presentation.ui.theme.AppTheme
import com.trigger.app.core.presentation.ui.theme.LocalAppColors
import com.trigger.app.core.presentation.ui.theme.QuickSand
import com.trigger.app.core.presentation.ui.theme.StatusBars

@Composable
fun ChatDetailsScreen(
    chatID: String, otherUserID: String, navController: NavController,
    updateStatusBar: (StatusBars) -> Unit
) {
    val viewModel = viewModel<ChatDetailsViewModel>()

    val otherUser by viewModel.otherUser.collectAsState(initial = null)
    val blockState by viewModel.blockState.collectAsState()
    var showBlockConfirmation by remember { mutableStateOf(false) }
    var showComingSoonPopup by remember {
        mutableStateOf(false)
    }
    var isProfilePicFullScreen by remember {
        mutableStateOf(false)
    }


    val appColors = LocalAppColors.current

    // Reset the status bar color to the background color
    LaunchedEffect(key1 = Unit) {
        updateStatusBar(StatusBars(appColors.blueCardColor, false))
    }



    LaunchedEffect(key1 = Unit) {
        viewModel.loadOtherUser(otherUserID)
    }

    ControlBlurOnScreen(
        isPictureOnFullScreen = isProfilePicFullScreen,
        profilePic = otherUser?.profilePic,
        dismissPicture = { isProfilePicFullScreen = false }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            DefaultScreen(navController = navController) {
                otherUser?.let { user ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {

                        Column(
                            modifier = Modifier.padding(top = 24.dp, bottom = 16.dp)
                        ) {
                            UserIcon(
                                profilePic = otherUser?.profilePic,
                                iconSize = 130.dp,
                                progressBarSize = 32.dp,
                                progressBarThickness = 3.dp,
                                borderIfUsingDefaultPic = 2.dp,
                                onClick = {
                                    isProfilePicFullScreen = true
                                }
                            )
                        }

                        Text(
                            text = user.name,
                            fontFamily = QuickSand,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Text(
                            text = user.number,
                            fontFamily = QuickSand,
                            fontSize = 16.sp,
                            lineHeight = 18.sp,
                            letterSpacing = (1.2).sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                        )

                        Text(
                            text = buildString {
                                append(" ~ ")
                                append(user.bio)
                                append(" ~ ")
                            },
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .padding(horizontal = 12.dp)
                                .fillMaxWidth(0.7f),
                            textAlign = TextAlign.Center,
                            lineHeight = 15.sp,
                            fontFamily = QuickSand,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                            fontSize = 16.sp
                        )
                    }

                    ChatDetailActions(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        onMessage = {
                            navController.popBackStack()
                        },
                        onAudioCall = { showComingSoonPopup = true },
                        onVideoCall = { showComingSoonPopup = true }
                    )


                    Spacer(modifier = Modifier.weight(1f))

                    RedBorderButton(
                        name = stringResource(R.string.block_user),
                        modifier = Modifier.padding(bottom = 4.dp),
                        onOptionSelected = { showBlockConfirmation = true }
                    )
                    RedBorderButton(
                        name = stringResource(R.string.report_user),
                        modifier = Modifier.padding(bottom = 8.dp),
                        onOptionSelected = {
                            navController.navigate(ReportUser(user.uid))
                        }
                    )
                }
            }

            if (showComingSoonPopup)
                ComingSoonPopup(
                    hidePopup = {
                        showComingSoonPopup = false
                    }
                )

            if (blockState is com.trigger.app.core.domain.TaskState.LOADING)
                LoadingSpinner(modifier = Modifier.align(Alignment.Center))

            if (showBlockConfirmation)
                AlertDialog(
                    onDismissRequest = { showBlockConfirmation = false },
                    title = { Text(stringResource(R.string.block_user)) },
                    text = { Text(stringResource(R.string.block_confirmation)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showBlockConfirmation = false
                            viewModel.blockUser()
                        }) { Text(stringResource(R.string.block_user)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBlockConfirmation = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )

            if (blockState is com.trigger.app.core.domain.TaskState.DONE.SUCCESS)
                AlertDialog(
                    onDismissRequest = { navController.popBackStack() },
                    title = { Text(stringResource(R.string.user_blocked)) },
                    text = { Text(stringResource(R.string.user_blocked_message)) },
                    confirmButton = {
                        TextButton(onClick = { navController.popBackStack() }) {
                            Text(stringResource(R.string.close))
                        }
                    }
                )
        }
    }
}


@Preview
@Composable
private fun PreviewChatDetailsScreen() = AppTheme {
    ChatDetailsScreen("", "lCfSU1UMWOh2LFsDA1bpEE8nGSx2", navController = rememberNavController()) {}
}