package com.trigger.app.auth.ui.screens.create_username

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.trigger.app.R
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.presentation.ui.AllChats
import com.trigger.app.core.presentation.ui.Welcome
import com.trigger.app.core.presentation.ui.components.DefaultScreen
import com.trigger.app.core.presentation.ui.navigateSafelyAndPopTo
import com.trigger.app.core.presentation.ui.theme.Montserrat
import com.trigger.app.core.presentation.ui.theme.QuickSand
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientBottom
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientMid
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientTop

@Composable
fun CreateUsernameScreen(
    navController: NavController,
    route: com.trigger.app.core.presentation.ui.CreateUsername,
    snackbarHostState: androidx.compose.material3.SnackbarHostState
) {
    val viewModel = viewModel<CreateUsernameViewModel>()
    val username by viewModel.username.collectAsState()
    val availability by viewModel.availability.collectAsState()
    val taskState by viewModel.taskState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(taskState) {
        when (taskState) {
            TaskState.DONE.SUCCESS -> navController.navigateSafelyAndPopTo(AllChats, Welcome, true)
            is TaskState.DONE.ERROR -> snackbarHostState.showSnackbar(
                context.getString((taskState as TaskState.DONE.ERROR).errorMessageRes)
            )
            else -> Unit
        }
    }

    val gradientBrush = Brush.verticalGradient(
        colors = listOf(
            WelcomeGradientTop,
            WelcomeGradientMid,
            WelcomeGradientMid,
            WelcomeGradientBottom,
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradientBrush)
            .imePadding()
    ) {
        DefaultScreen(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 60.dp),
            surfaceColor = Color.Transparent,
            backgroundColor = Color.Transparent,
            appBar = { Spacer(modifier = Modifier.height(0.dp)) }
        ) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    text = stringResource(R.string.choose_username),
                    fontFamily = QuickSand,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = Color.White
                )
                Text(
                    text = stringResource(R.string.username_help),
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.padding(top = 8.dp)
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = viewModel::updateUsername,
                    modifier = Modifier
                        .padding(top = 28.dp)
                        .fillMaxWidth(),
                    singleLine = true,
                    prefix = {
                        Text(
                            "@",
                            color = Color.White,
                            fontFamily = Montserrat,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    label = {
                        Text(
                            stringResource(R.string.username),
                            color = Color.White.copy(alpha = 0.7f),
                            fontFamily = Montserrat
                        )
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontFamily = Montserrat,
                        fontWeight = FontWeight.Medium,
                        fontSize = 17.sp
                    ),
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
                                UsernameAvailability.TAKEN, UsernameAvailability.INVALID -> Color(0xFFFF6B6B)
                                else -> Color.White.copy(alpha = 0.7f)
                            },
                            fontFamily = Montserrat,
                            fontSize = 12.sp
                        )
                    }
                )

                Spacer(Modifier.height(32.dp))

                Button(
                    onClick = {
                        viewModel.createUserProfile(
                            route.name,
                            route.bio,
                            route.phoneNumber,
                            route.profilePic
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = availability == UsernameAvailability.AVAILABLE
                            && taskState !is TaskState.LOADING,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = WelcomeGradientTop
                    )
                ) {
                    if (taskState is TaskState.LOADING)
                        CircularProgressIndicator(
                            color = WelcomeGradientTop,
                            strokeWidth = 2.dp
                        )
                    else
                        Text(
                            stringResource(R.string.finish_profile),
                            fontFamily = QuickSand,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                }
            }
        }
    }
}
