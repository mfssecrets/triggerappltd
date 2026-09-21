package com.trigger.app.auth.ui.screens.enter_code

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.trigger.app.auth.ui.components.OTPTextField
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.presentation.ui.AllChats
import com.trigger.app.core.presentation.ui.CreateProfile
import com.trigger.app.core.presentation.ui.Welcome
import com.trigger.app.core.presentation.ui.components.LoadingSpinner
import com.trigger.app.core.presentation.ui.navigateSafely
import com.trigger.app.core.presentation.ui.navigateSafelyAndPopTo
import com.trigger.app.core.presentation.ui.theme.AppTheme
import com.trigger.app.core.presentation.ui.theme.Montserrat
import com.trigger.app.core.presentation.ui.theme.QuickSand
import com.trigger.app.core.presentation.ui.theme.StatusBars
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientBottom
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientMid
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientTop
import com.trigger.app.core.utils.getActivity

@Composable
fun EnterCodeScreen(
    navController: NavController,
    phoneNumberWithCountryCode: String,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val viewModel = viewModel<EnterCodeViewModel>()

    val code by viewModel.code.collectAsState()
    val taskState by viewModel.taskState.collectAsState()
    val isCodeSent by viewModel.isCodeSent.collectAsState()

    val timeLeftInMillis by viewModel.timeLeftInMillis.collectAsState()
    val formattedTimeLeft by viewModel.formattedTimeLeft.collectAsState()

    val activity = LocalContext.current.getActivity()

    // Gradient background — same as the Welcome screen, applied to every auth page.
    val gradientBrush = Brush.verticalGradient(
        colors = listOf(
            WelcomeGradientTop,
            WelcomeGradientMid,
            WelcomeGradientMid,
            WelcomeGradientBottom,
        )
    )

    // Send the SMS exactly ONCE when the screen first composes. Do NOT re-send on
    // every NONE transition — that was the bug causing "wrong OTP sends you back".
    LaunchedEffect(key1 = Unit) {
        viewModel.authenticateWithNumber(phoneNumberWithCountryCode, activity)
    }

    LaunchedEffect(key1 = taskState) {
        when (taskState) {
            is TaskState.DONE.SUCCESS -> {
                val isExistingAccount = viewModel.checkIfUserHasExistingAccount()

                if (isExistingAccount)
                    navController.navigateSafelyAndPopTo(AllChats, Welcome, true)
                else
                    navController.navigateSafely(CreateProfile(phoneNumberWithCountryCode))

                viewModel.resetTaskState()
            }

            is TaskState.DONE.ERROR -> {
                // Show the snackbar and let the user retry — do NOT pop back to EnterNumber.
                snackbarHostState.showSnackbar(
                    context.getString((taskState as TaskState.DONE.ERROR).errorMessageRes)
                )
                viewModel.resetTaskState()
            }

            else -> {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradientBrush)
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 60.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (taskState is TaskState.LOADING) {
                // Verifying OTP state — show a clear loading message instead of just a spinner
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 4.dp,
                        modifier = Modifier.height(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.verifying_otp),
                        fontFamily = Montserrat,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            } else if (taskState is TaskState.DONE.SUCCESS) {
                // Brief "Verified!" state before navigation kicks in.
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LoadingSpinner()
                }
            } else {
                // Default "enter the code" view.
                Text(
                    text = stringResource(R.string.enter_code),
                    fontFamily = QuickSand,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 24.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // "Code sent to +91xxxxxxxxxx" message — only shows once SMS has actually
                // been delivered to the user's phone.
                Text(
                    text = if (isCodeSent)
                        stringResource(R.string.otp_sent_to, phoneNumberWithCountryCode)
                    else
                        stringResource(R.string.enter_otp_below),
                    fontFamily = Montserrat,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                OTPTextField(
                    value = code,
                    length = EnterCodeViewModel.CODE_LENGTH,
                    modifier = Modifier.fillMaxWidth(),
                    spacedBy = 10.dp
                ) { newCode ->
                    viewModel.updateCode(newCode)
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Resend row: countdown + (when 0) resend button.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (timeLeftInMillis > EnterCodeViewModel.SECOND_IN_MILLIS) {
                        Text(
                            text = stringResource(
                                R.string.otp_resend_disabled,
                                formattedTimeLeft
                            ),
                            fontFamily = Montserrat,
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    } else {
                        Button(
                            onClick = {
                                viewModel.authenticateWithNumber(
                                    phoneNumberWithCountryCode,
                                    activity
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.resend_otp),
                                fontFamily = QuickSand,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = { viewModel.submitCode() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = WelcomeGradientTop
                    ),
                    enabled = code.length == EnterCodeViewModel.CODE_LENGTH
                            && taskState !is TaskState.LOADING
                ) {
                    Text(
                        text = stringResource(R.string.submit_code),
                        fontFamily = QuickSand,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}


@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview
@Composable
private fun PreviewEnterCodeScreen() = AppTheme {
    val snackbarHostState = remember {
        SnackbarHostState()
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) {
        EnterCodeScreen(
            navController = rememberNavController(),
            phoneNumberWithCountryCode = "",
            snackbarHostState = snackbarHostState
        )
    }
}
