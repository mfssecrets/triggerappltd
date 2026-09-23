package com.trigger.app.notifications.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.trigger.app.R
import com.trigger.app.core.presentation.ui.ActualChat
import com.trigger.app.core.presentation.ui.components.AppBottomBar
import com.trigger.app.core.presentation.ui.components.BottomBars
import com.trigger.app.core.presentation.ui.components.DefaultScreen
import com.trigger.app.core.presentation.ui.components.LoadingSpinner
import com.trigger.app.core.presentation.ui.components.UserIcon
import com.trigger.app.core.presentation.ui.navigateSafely
import com.trigger.app.core.presentation.ui.theme.AppTheme
import com.trigger.app.core.presentation.ui.theme.Poppins
import com.trigger.app.core.presentation.ui.theme.QuickSand
import org.koin.androidx.compose.koinViewModel

/**
 * Notifications screen — live feed of:
 *   1. Unread chats (new messages from your conversations)
 *   2. Story replies (people who replied to your stories)
 *   3. Missed calls (incoming calls you didn't pick up)
 *
 * All three sources use Firestore snapshot listeners — auto-updates in real
 * time. Items are merged + sorted newest-first across all types.
 *
 * Tap behavior:
 *   - UnreadChat → opens ActualChat(chatId)
 *   - StoryReply → marks as read + (TODO: opens ViewStoryScreen)
 *   - MissedCall → marks as read + (TODO: opens user profile or callback)
 */
@Composable
fun NotificationsScreen(navController: NavController) {
    val viewModel: NotificationsViewModel = koinViewModel()
    val feed by viewModel.feed.collectAsState()

    DefaultScreen(
        navController = navController,
        appBarText = stringResource(R.string.notifications)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            when {
                feed.isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LoadingSpinner()
                    }
                }

                feed.items.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.no_users_on_trigger_app),
                            contentDescription = null,
                            modifier = Modifier.size(150.dp)
                        )

                        Text(
                            text = stringResource(R.string.no_notifications_yet),
                            fontFamily = Poppins,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .padding(top = 16.dp)
                                .fillMaxWidth(0.75f)
                        )

                        Text(
                            text = stringResource(R.string.no_notifications_subtitle),
                            fontFamily = Poppins,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .fillMaxWidth(0.85f)
                        )
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(feed.items) { item ->
                            NotificationItemRow(
                                item = item,
                                onClick = { handleItemTap(item, viewModel, navController) }
                            )
                        }
                    }
                }
            }

            AppBottomBar(
                currentBottomBar = BottomBars.Notifications,
                navController = navController,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}


private fun handleItemTap(
    item: NotificationsViewModel.NotificationItem,
    viewModel: NotificationsViewModel,
    navController: NavController
) {
    when (item.type) {
        NotificationsViewModel.NotificationType.UnreadChat -> {
            item.chatId?.let { chatId ->
                navController.navigateSafely(ActualChat(chatId = chatId, newContact = null))
            }
        }
        NotificationsViewModel.NotificationType.StoryReply -> {
            viewModel.markStoryReplyAsRead(item)
            // TODO: route to ViewStoryScreen(storyID = item.storyID)
        }
        NotificationsViewModel.NotificationType.MissedCall -> {
            viewModel.markCallAsRead(item)
            // TODO: route to user profile or callback screen
        }
    }
}


@Composable
private fun NotificationItemRow(
    item: NotificationsViewModel.NotificationItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 8.dp, horizontal = 12.dp)
    ) {
        UserIcon(
            profilePic = item.senderProfilePic,
            iconSize = 48.dp,
            progressBarSize = 20.dp,
            progressBarThickness = (1.5).dp,
            borderIfUsingDefaultPic = 1.dp,
            modifier = Modifier.align(Alignment.CenterVertically),
            onClick = onClick,
        )

        Column(
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f)
                .align(Alignment.CenterVertically)
        ) {
            // Subtle type prefix for non-chat notifications so the user
            // can distinguish them at a glance.
            val prefix = when (item.type) {
                NotificationsViewModel.NotificationType.StoryReply -> "Story reply · "
                NotificationsViewModel.NotificationType.MissedCall -> "Missed call · "
                else -> ""
            }

            Text(
                text = prefix + item.senderName,
                fontFamily = QuickSand,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.previewText,
                fontFamily = Poppins,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp)
            )
        }

        if (item.unreadCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}


@Preview
@Composable
private fun PreviewNotificationsScreen() = AppTheme {
    NotificationsScreen(navController = rememberNavController())
}
