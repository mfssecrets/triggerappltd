package com.trigger.app.auth.ui.screens.create_username

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.trigger.app.R
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.presentation.ui.AllChats
import com.trigger.app.core.presentation.ui.CreateUsername
import com.trigger.app.core.presentation.ui.Welcome
import com.trigger.app.core.presentation.ui.components.DefaultScreen
import com.trigger.app.core.presentation.ui.navigateSafelyAndPopTo
import com.trigger.app.core.presentation.ui.theme.Poppins
import com.trigger.app.core.presentation.ui.theme.QuickSand
import kotlinx.coroutines.flow.collectLatest

@Composable
fun CreateUsernameScreen(
    navController: NavController,
    route: CreateUsername,
    snackbarHostState: androidx.compose.material3.SnackbarHostState
) {
    val viewModel = viewModel<CreateUsernameViewModel>()
    val username by viewModel.username.collectAsState()
    val availability by viewModel.availability.collectAsState()
    val taskState by viewModel.taskState.collectAsState()

    LaunchedEffect(taskState) {
        when (taskState) {
            TaskState.DONE.SUCCESS -> navController.navigateSafelyAndPopTo(AllChats, Welcome, true)
            is TaskState.DONE.ERROR -> snackbarHostState.showSnackbar(
                stringResource((taskState as TaskState.DONE.ERROR).errorMessageRes)
            )
            else -> Unit
        }
    }

    DefaultScreen(
        navController = navController,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.choose_username),
                fontFamily = QuickSand,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                modifier = Modifier.padding(top = 24.dp)
            )
            Text(
                text = stringResource(R.string.username_help),
                fontFamily = Poppins,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            OutlinedTextField(
                value = username,
                onValueChange = viewModel::updateUsername,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth(),
                singleLine = true,
                prefix = { Text("@") },
                label = { Text(stringResource(R.string.username)) },
                supportingText = {
                    Text(
                        when (availability) {
                            UsernameAvailability.CHECKING -> stringResource(R.string.checking_username)
                            UsernameAvailability.AVAILABLE -> stringResource(R.string.username_available)
                            UsernameAvailability.TAKEN -> stringResource(R.string.username_taken)
                            UsernameAvailability.INVALID -> stringResource(R.string.username_invalid)
                            UsernameAvailability.UNKNOWN -> stringResource(R.string.username_rules)
                        },
                        color = when (availability) {
                            UsernameAvailability.AVAILABLE -> Color(0xFF16803C)
                            UsernameAvailability.TAKEN, UsernameAvailability.INVALID -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    viewModel.createUserProfile(route.name, route.bio, route.phoneNumber, route.profilePic)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                enabled = availability == UsernameAvailability.AVAILABLE && taskState !is TaskState.LOADING,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                if (taskState is TaskState.LOADING)
                    CircularProgressIndicator(modifier = Modifier.padding(vertical = 4.dp), strokeWidth = 2.dp)
                else
                    Text(stringResource(R.string.finish_profile), fontFamily = Poppins)
            }
        }
    }
}