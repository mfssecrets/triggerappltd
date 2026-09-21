package com.trigger.app.settings.presentation.screens.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.trigger.app.R
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.domain.User
import com.trigger.app.core.repo.moderation.ModerationRepo
import com.trigger.app.core.repo.moderation.ModerationRepoImpl
import com.trigger.app.core.presentation.ui.components.DefaultScreen
import com.trigger.app.core.presentation.ui.theme.QuickSand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BlockedUsersViewModel(
    private val moderationRepo: ModerationRepo = ModerationRepoImpl()
) : ViewModel() {
    val blockedUsers = moderationRepo.getBlockedUsers()
    private val _taskState = MutableStateFlow<TaskState>(TaskState.NONE)
    val taskState: StateFlow<TaskState> = _taskState

    fun unblock(userID: String) = viewModelScope.launch {
        _taskState.value = TaskState.LOADING()
        runCatching { moderationRepo.unblockUser(userID) }
            .onSuccess { _taskState.value = TaskState.DONE.SUCCESS }
            .onFailure { _taskState.value = TaskState.DONE.ERROR(R.string.error_occurred) }
    }
}

@Composable
fun PrivacySettingsScreen(navController: NavController) {
    DefaultScreen(navController = navController, appBarText = stringResource(R.string.privacy)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.privacy_options), fontFamily = QuickSand, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            PrivacyOption(stringResource(R.string.last_seen_privacy))
            PrivacyOption(stringResource(R.string.profile_photo_privacy))
            PrivacyOption(stringResource(R.string.read_receipts_privacy))
            PrivacyOption(stringResource(R.string.blocked_users)) {
                navController.navigate(com.trigger.app.core.presentation.ui.BlockedUsers)
            }
        }
    }
}

@Composable
private fun PrivacyOption(name: String, onClick: (() -> Unit)? = null) {
    Button(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface
        )
    ) { Text(name) }
}

@Composable
fun BlockedUsersScreen(navController: NavController, viewModel: BlockedUsersViewModel = viewModel()) {
    val users by viewModel.blockedUsers.collectAsState(initial = emptyList())
    val taskState by viewModel.taskState.collectAsState()
    DefaultScreen(navController = navController, appBarText = stringResource(R.string.blocked_users)) {
        if (taskState is TaskState.LOADING) {
            CircularProgressIndicator(Modifier.padding(24.dp))
        }
        if (users.isEmpty()) {
            Text(stringResource(R.string.no_blocked_users), Modifier.padding(16.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(users, key = { it.uid }) { user ->
                    BlockedUserRow(user = user, onUnblock = { viewModel.unblock(user.uid) })
                }
            }
        }
    }
}

@Composable
private fun BlockedUserRow(user: User, onUnblock: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(user.name, fontWeight = FontWeight.SemiBold)
        if (user.username.isNotBlank()) Text("@${user.username}", color = Color.Gray)
        Button(onClick = onUnblock, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.unblock)) }
    }
}

@Composable
fun VerifiedBadgeScreen(navController: NavController) {
    DefaultScreen(navController = navController, appBarText = stringResource(R.string.verified_badge)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.verified_badge_title), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.verified_badge_description), Modifier.padding(top = 8.dp))
            Button(onClick = {}, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Text(stringResource(R.string.apply_for_verification))
            }
        }
    }
}

@Composable
fun CreatePageScreen(navController: NavController) {
    DefaultScreen(navController = navController, appBarText = stringResource(R.string.create_my_page)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.create_page_title), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.create_page_description), Modifier.padding(top = 8.dp))
            Button(onClick = {}, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Text(stringResource(R.string.create_my_page))
            }
        }
    }
}

@Composable
fun HelpScreen(navController: NavController) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    DefaultScreen(navController = navController, appBarText = stringResource(R.string.help)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.help_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.help_description),
                modifier = Modifier.padding(top = 8.dp)
            )
            Button(
                onClick = { uriHandler.openUri("mailto:support@triggerappltd.cyou") },
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
            ) {
                Text(stringResource(R.string.contact_support))
            }
            Text(
                text = stringResource(R.string.faq),
                modifier = Modifier.padding(top = 28.dp),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun ReportUserScreen(
    navController: NavController,
    userID: String,
    moderationRepo: ModerationRepo = ModerationRepoImpl()
) {
    var reason by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<TaskState>(TaskState.NONE) }
    var submitted by remember { mutableStateOf(false) }

    DefaultScreen(navController = navController, appBarText = stringResource(R.string.report_user)) {
        Column(Modifier.padding(16.dp)) {
            if (submitted) {
                Text(stringResource(R.string.report_submitted), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Button(onClick = { navController.popBackStack() }, modifier = Modifier.padding(top = 20.dp)) {
                    Text(stringResource(R.string.close))
                }
            } else {
                Text(stringResource(R.string.report_reason_prompt), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    minLines = 5,
                    label = { Text(stringResource(R.string.report_reason)) }
                )
                Button(
                    onClick = {
                        state = TaskState.LOADING()
                        kotlinx.coroutines.MainScope().launch {
                            runCatching { moderationRepo.reportUser(userID, reason) }
                                .onSuccess { submitted = true; state = TaskState.DONE.SUCCESS }
                                .onFailure { state = TaskState.DONE.ERROR(R.string.error_occurred) }
                        }
                    },
                    enabled = reason.trim().length >= 10 && state !is TaskState.LOADING,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) {
                    if (state is TaskState.LOADING) CircularProgressIndicator(strokeWidth = 2.dp)
                    else Text(stringResource(R.string.report_user))
                }
                Button(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}
