package com.trigger.app.welcome

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.trigger.app.R
import com.trigger.app.core.presentation.ui.EnterNumber
import com.trigger.app.core.presentation.ui.navigateSafely
import com.trigger.app.core.presentation.ui.theme.AppTheme
import com.trigger.app.core.presentation.ui.theme.Montserrat
import com.trigger.app.core.presentation.ui.theme.QuickSand
import com.trigger.app.core.presentation.ui.theme.StatusBars
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientBottom
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientMid
import com.trigger.app.core.presentation.ui.theme.WelcomeGradientTop

@Composable
fun WelcomeScreen(
    navController: NavController,
    updateStatusBar: (StatusBars) -> Unit
) {
    val welcomeBrush = Brush.verticalGradient(
        colors = listOf(
            WelcomeGradientTop,
            WelcomeGradientMid,
            WelcomeGradientMid,
            WelcomeGradientBottom,
        )
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(welcomeBrush)
    ) {
        LocalView.current
        val isDarkIcons = false

        LaunchedEffect(key1 = Unit) {
            updateStatusBar(StatusBars(WelcomeGradientTop, isDarkIcons))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo only — no white box, no glow circle.
            Image(
                painter = painterResource(id = R.drawable.trigger_app_logo),
                contentDescription = null,
                modifier = Modifier.size(140.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Title — forced to a single line.
            Text(
                text = stringResource(id = R.string.welcome_message),
                fontFamily = QuickSand,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Subtext — different font family (Montserrat) + italic + smaller size,
            // clearly differentiated from the QuickSand title.
            Text(
                text = stringResource(id = R.string.welcome_tagline),
                fontFamily = Montserrat,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(0.85f)
            )
        }

        Button(
            onClick = {
                navController.navigateSafely(EnterNumber)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = WelcomeGradientTop
            )
        ) {
            Text(
                text = stringResource(id = R.string.register),
                fontFamily = QuickSand,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
    }
}


@Preview
@Composable
private fun PreviewWelcomeScreen() = AppTheme(darkTheme = true) {
    WelcomeScreen(rememberNavController()) {}
}
