package com.trigger.app.auth.ui.screens.enter_number

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.da_chelimo.compose_ccp.components.CCPTextField
import com.da_chelimo.compose_ccp.model.PickerUtils
import com.da_chelimo.compose_ccp.theme.DarkBlue
import com.trigger.app.R
import com.trigger.app.core.domain.TaskState
import com.trigger.app.core.presentation.ui.EnterCode
import com.trigger.app.core.presentation.ui.components.DefaultScreen
import com.trigger.app.core.presentation.ui.navigateSafely
import com.trigger.app.core.presentation.ui.theme.AppTheme
import com.trigger.app.core.presentation.ui.theme.Montserrat
import com.trigger.app.core.presentation.ui.theme.QuickSand
import com.trigger.app.core.presentation.ui.theme.StatusBars
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientBottom
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientMid
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientTop

@Composable
fun EnterNumberScreen(
    navController: NavController,
    updateStatusBar: (StatusBars) -> Unit
) {
    val context = LocalContext.current
    val viewModel = viewModel<EnterNumberViewModel>()

    val number by viewModel.number.collectAsState()
    val country by viewModel.country.collectAsState()
    val taskState by viewModel.taskState.collectAsState()
    val shouldNavigateToEnterCode by viewModel.shouldNavigateToEnterCode.collectAsState()

    // Gradient background — same palette as the Welcome screen, applied to all auth pages.
    val gradientBrush = Brush.verticalGradient(
        colors = listOf(
            WelcomeGradientTop,
            WelcomeGradientMid,
            WelcomeGradientMid,
            WelcomeGradientBottom,
        )
    )

    // Reset the status bar to the top of the gradient.
    LaunchedEffect(key1 = Unit) {
        updateStatusBar(StatusBars(WelcomeGradientTop, false))
    }

    // Auto-select the user's country on screen entry.
    LaunchedEffect(key1 = Unit) {
        val defaultCountry = PickerUtils.getDefaultCountry(context)
        viewModel.updateCountry(defaultCountry)
    }

    // Navigate to EnterCode when validation succeeds and user taps "Request code".
    LaunchedEffect(shouldNavigateToEnterCode) {
        if (shouldNavigateToEnterCode) {
            navController.navigateSafely(EnterCode(viewModel.getE164Number() ?: ""))
            viewModel.resetShouldNavigate()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradientBrush)
            .imePadding()
    ) {
        // DefaultScreen paints its own surface + background; pass transparent colors so
        // the gradient we just painted on the Box shows through.
        DefaultScreen(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(top = 60.dp),
            surfaceColor = Color.Transparent,
            backgroundColor = Color.Transparent,
            appBar = {
                // Empty app bar — the gradient is the visual hero here.
                Spacer(modifier = Modifier.height(0.dp))
            }
        ) {
            Text(
                text = stringResource(R.string.enter_your_phone_number),
                fontFamily = QuickSand,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                textAlign = TextAlign.Center
            )

            Text(
                text = stringResource(R.string.verify_number_statement),
                fontFamily = Montserrat,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.72f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, start = 16.dp, end = 16.dp),
                textAlign = TextAlign.Center
            )

            CCPTextField(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth(),
                country = country,
                number = number,
                containerColor = Color.White.copy(alpha = 0.12f),
                textColor = Color.White,
                defaultIndicatorColor = Color.White,
                isValid = taskState is TaskState.DONE.SUCCESS,
                errorMessage = (taskState as? TaskState.DONE.ERROR)?.errorMessageRes?.let {
                    stringResource(it)
                },
                onCountryChange = { viewModel.updateCountry(it) },
                onNumberChange = { viewModel.updateNumber(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    viewModel.navigateToEnterCode()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = WelcomeGradientTop
                ),
                enabled = taskState is TaskState.DONE.SUCCESS
            ) {
                Text(
                    text = stringResource(R.string.request_code),
                    fontFamily = QuickSand,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
        }
    }
}


@Preview
@Composable
private fun PreviewEnterNumberScreen() = AppTheme(darkTheme = true) {
    EnterNumberScreen(rememberNavController()) {}
}
