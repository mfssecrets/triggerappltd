package com.trigger.app.auth.ui.screens.enter_code

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.trigger.app.R
import com.trigger.app.auth.PhoneAuthState
import com.trigger.app.auth.ui.components.OTPTextField
import com.trigger.app.core.presentation.ui.AllChats
import com.trigger.app.core.presentation.ui.CreateProfile
import com.trigger.app.core.presentation.ui.Welcome
import com.trigger.app.core.presentation.ui.navigateSafely
import com.trigger.app.core.presentation.ui.navigateSafelyAndPopTo
import com.trigger.app.core.presentation.ui.theme.Montserrat
import com.trigger.app.core.presentation.ui.theme.QuickSand
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientBottom
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientMid
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientTop
import com.trigger.app.core.utils.getActivity
import kotlinx.coroutines.launch

@Composable
fun EnterCodeScreen(
    navController: NavController,
    phoneNumberWithCountryCode: String,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val viewModel = viewModel<EnterCodeViewModel>()

    val code by viewModel.code.collectAsState()
    val authState by viewModel.authState.collectAsState()
    val resendCountdown by viewModel.resendCountdown.collectAsState()
    val formattedCountdown by viewModel.formattedCountdown.collectAsState()

    val activity = LocalContext.current.getActivity()
    val coroutineScope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main) }

    val gradientBrush = Brush.verticalGradient(
        colors = listOf(WelcomeGradientTop, WelcomeGradientMid, WelcomeGradientMid, WelcomeGradientBottom)
    )

    // Send SMS ONCE on first composition.
    LaunchedEffect(key1 = Unit) {
        viewModel.authenticateWithNumber(phoneNumberWithCountryCode, activity)
    }

    // FIX #7: Single state machine for both auto-verification + manual OTP.
    LaunchedEffect(key1 = authState) {
        when (authState) {
            is PhoneAuthState.Success -> {
                val isExistingAccount = viewModel.checkIfUserHasExistingAccount()
                if (isExistingAccount)
                    navController.navigateSafelyAndPopTo(AllChats, Welcome, true)
                else
                    navController.navigateSafely(CreateProfile(phoneNumberWithCountryCode))
                viewModel.resetAuthState()
            }
            is PhoneAuthState.Error -> {
                // FIX: Don't pop back — stay on screen so user can retry.
                val message = (authState as PhoneAuthState.Error).message
                snackbarHostState.showSnackbar(message)
                viewModel.resetAuthState()
            }
            else -> {}
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(gradientBrush).imePadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 60.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (authState) {
                is PhoneAuthState.SendingCode, is PhoneAuthState.VerifyingCode -> {
                    val msg = if (authState is PhoneAuthState.SendingCode) "Sending code…" else "Verifying code…"
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 4.dp, modifier = Modifier.height(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(msg, fontFamily = Montserrat, fontWeight = FontWeight.Medium, color = Color.White, fontSize = 14.sp)
                    }
                }
                is PhoneAuthState.Success -> {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 4.dp, modifier = Modifier.height(48.dp))
                    }
                }
                else -> {
                    // CodeSent or Idle — show OTP input.
                    Text(
                        text = stringResource(R.string.enter_code),
                        fontFamily = QuickSand,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 24.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.otp_sent_to, phoneNumberWithCountryCode),
                        fontFamily = Montserrat,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(32.dp))
                    OTPTextField(
                        value = code,
                        length = EnterCodeViewModel.CODE_LENGTH,
                        modifier = Modifier.fillMaxWidth(),
                        spacedBy = 10.dp
                    ) { newCode -> viewModel.updateCode(newCode) }
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (resendCountdown > EnterCodeViewModel.SECOND_IN_MILLIS) {
                            Text(
                                text = stringResource(R.string.otp_resend_disabled, formattedCountdown),
                                fontFamily = Montserrat, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f)
                            )
                        } else {
                            Button(
                                onClick = { viewModel.authenticateWithNumber(phoneNumberWithCountryCode, activity) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White)
                            ) {
                                Text(stringResource(R.string.resend_otp), fontFamily = QuickSand, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { viewModel.submitCode() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = WelcomeGradientTop),
                        enabled = code.length == EnterCodeViewModel.CODE_LENGTH && authState !is PhoneAuthState.VerifyingCode
                    ) {
                        Text(stringResource(R.string.submit_code), fontFamily = QuickSand, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
