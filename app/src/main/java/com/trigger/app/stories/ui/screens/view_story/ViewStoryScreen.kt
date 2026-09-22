package com.trigger.app.stories.ui.screens.view_story

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trigger.app.R
import com.trigger.app.core.presentation.ui.components.Glider
import com.trigger.app.core.presentation.ui.components.UserIcon
import com.trigger.app.core.presentation.ui.theme.AppTheme
import com.trigger.app.core.presentation.ui.theme.DarkBlue
import com.trigger.app.core.presentation.ui.theme.LightBlack
import com.trigger.app.core.presentation.ui.theme.LocalAppColors
import com.trigger.app.core.presentation.ui.theme.Poppins
import com.trigger.app.core.presentation.ui.theme.QuickSand
import com.trigger.app.stories.domain.StoryPreview
import com.trigger.app.stories.ui.components.StoryTopCountIndicator
import com.trigger.app.stories.ui.components.ViewStoryMessageBar
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewStoryScreen(authorID: String, onHideStory: () -> Unit) {
    val viewModel: ViewStoryViewModel = koinViewModel()
    val author by viewModel.author.collectAsState(initial = null)

    val stories by viewModel.stories.collectAsState(initial = null)
    val storyIndex by viewModel.storyIndex.collectAsState()
    val hideStory by viewModel.hideStory.collectAsState()

    val typedMessage by viewModel.typedMessage.collectAsState()

    // Menu + confirmation-dialog state for delete-story.
    var showOptionsDropdown by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Snackbar-equivalent: surface reply-send success / failure briefly.
    var replyMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(key1 = Unit) {
        viewModel.loadUser(authorID)
        viewModel.loadStories(authorID)
    }

    // Hide-story listener — fires when the user reaches past the last story
    // (either by tapping right on the last one or by deleting all stories).
    LaunchedEffect(key1 = hideStory) {
        if (hideStory) {
            onHideStory()
            viewModel.resetHideStory()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBlue)
    ) {

        if (stories != null) {
            val pagerState = rememberPagerState { stories!!.size }
            val localConfiguration = LocalConfiguration.current

            // Sync pagerState ← storyIndex (programmatic next/prev).
            LaunchedEffect(key1 = storyIndex) {
                if (storyIndex != pagerState.currentPage)
                    pagerState.animateScrollToPage(storyIndex)
            }

            // Auto-advance: every STORY_AUTOADVANCE_MS, advance to the next
            // story. When on the last story, the auto-advance fires hideStory
            // (which closes the viewer).
            LaunchedEffect(key1 = storyIndex, stories?.size) {
                while (true) {
                    delay(STORY_AUTOADVANCE_MS)
                    viewModel.moveToNextStory(storyIndex)
                    break  // Re-launch the loop with the new storyIndex
                }
            }

            // Sync storyIndex ← pagerState (user swipe).
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.collect { page ->
                    if (page != storyIndex) {
                        // User swiped — re-sync VM. This also triggers markStoryViewed.
                        if (page > storyIndex) viewModel.moveToNextStory(page - 1)
                        else viewModel.moveToPreviousStory(page + 1)
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp)
            ) { pageStoryIndex ->
                Column(
                    Modifier
                        .fillMaxHeight()
                        .combinedClickable(
                            onDoubleClick = { /* TODO: Like story */ },
                            onClick = { /* swallow taps so the pointerInput below handles them */ }
                        )
                        .pointerInput(stories) {
                            detectTapGestures { offset ->
                                val centerX = (localConfiguration.screenWidthDp.dp / 2)
                                val tapPosition = offset.x.toDp()

                                Timber.d("Tap on ${if (tapPosition > centerX) "right" else "left"}")
                                if (tapPosition > centerX)
                                    viewModel.moveToNextStory(pageStoryIndex)
                                else
                                    viewModel.moveToPreviousStory(pageStoryIndex)
                            }
                        }
                ) {
                    Glider(
                        imageUrl = stories?.getOrNull(pageStoryIndex)?.imageUrl,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(LightBlack),
                        loading = {
                            CircularProgressIndicator(
                                Modifier.size(48.dp),
                                strokeWidth = 4.dp,
                                trackColor = LocalAppColors.current.appThemeTextColor
                            )
                        },
                        error = {
                            Image(
                                painter = painterResource(id = R.drawable.no_users_on_trigger_app),
                                contentDescription = null,
                                modifier = Modifier.size(150.dp)
                            )
                            Text(
                                text = stringResource(id = R.string.error_occurred),
                                Modifier.padding(top = 8.dp),
                                fontFamily = Poppins,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    )

                    Column(
                        Modifier
                            .height(90.dp)
                            .background(DarkBlue)
                    ) {
                        ViewStoryMessageBar(
                            text = typedMessage,
                            onTextChange = { viewModel.updateTypedMessage(it) },
                            onSendClick = {
                                viewModel.sendReply { success ->
                                    replyMessage = if (success)
                                        stringResource(R.string.story_reply_sent)
                                    else
                                        stringResource(R.string.story_reply_failed)
                                }
                            },
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            Column {
                StoryTopCountIndicator(
                    currentStoryIndex = pagerState.currentPage,
                    totalStoryCount = pagerState.pageCount,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .padding(horizontal = 4.dp)
                )

                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UserIcon(
                        profilePic = author?.profilePic,
                        iconSize = 52.dp,
                        modifier = Modifier.padding(4.dp),
                        onClick = { }
                    )

                    Text(
                        text = author?.name ?: "",
                        fontFamily = QuickSand,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        modifier = Modifier.padding(start = 4.dp),
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // 3-dot menu — only the author gets the Delete option.
                    val isAuthor = authorID == com.google.firebase.auth.ktx.auth.let {
                        com.google.firebase.ktx.Firebase.auth.uid
                    }
                    if (isAuthor) {
                        Box {
                            IconButton(onClick = { showOptionsDropdown = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = stringResource(R.string.open_story_options),
                                    modifier = Modifier.size(28.dp),
                                    tint = Color.White
                                )
                            }
                            DropdownMenu(
                                expanded = showOptionsDropdown,
                                onDismissRequest = { showOptionsDropdown = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_story)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = null,
                                            tint = Color.Red
                                        )
                                    },
                                    onClick = {
                                        showOptionsDropdown = false
                                        showDeleteConfirmation = true
                                    }
                                )
                            }
                        }
                    }
                }
            }

        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(LightBlack),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp), strokeWidth = 4.dp)
            }
        }

        // Delete-story confirmation dialog (only shown when the user taps
        // Delete story from the dropdown).
        if (showDeleteConfirmation) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmation = false },
                title = { Text(stringResource(R.string.confirm_delete_story)) },
                text = { Text(stringResource(R.string.confirm_delete_story_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteCurrentStory { success ->
                            replyMessage = if (success) null  // no toast on success
                            else stringResource(R.string.error_occurred)
                        }
                    }) { Text(stringResource(R.string.yes)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmation = false }) {
                        Text(stringResource(R.string.no))
                    }
                }
            )
        }

        // Reply send result toast (very lightweight — auto-dismiss after 1.5s).
        replyMessage?.let { msg ->
            LaunchedEffect(msg) {
                delay(1500)
                replyMessage = null
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = msg,
                    color = Color.White,
                    fontSize = 13.sp
                )
            }
        }
    }
}


private const val STORY_AUTOADVANCE_MS = 5000L  // 5 seconds per story


@Preview
@Composable
private fun PreviewViewStoryScreen() = AppTheme {
    ViewStoryScreen(authorID = StoryPreview.TEST.authorID) {}
}
