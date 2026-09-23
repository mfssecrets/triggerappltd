package com.trigger.app.notifications.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.trigger.app.R
import com.trigger.app.core.presentation.ui.components.AppBottomBar
import com.trigger.app.core.presentation.ui.components.BottomBars
import com.trigger.app.core.presentation.ui.components.DefaultScreen
import com.trigger.app.core.presentation.ui.theme.AppTheme
import com.trigger.app.core.presentation.ui.theme.Poppins

/**
 * Notifications screen — shows recent notifications (new messages, missed calls,
 * story replies, message requests).
 *
 * For now this is a placeholder with an empty state. The actual notification
 * feed will be wired up later — the immediate fix was routing the "Notifications"
 * bottom-nav tab to THIS screen instead of MessageRequestsScreen (which is a
 * distinct feature for incoming chat requests from non-contacts).
 */
@Composable
fun NotificationsScreen(navController: NavController) {
    DefaultScreen(
        navController = navController,
        appBarText = stringResource(R.string.notifications)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
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
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth(0.75f)
                )

                Text(
                    text = stringResource(R.string.no_notifications_subtitle),
                    fontFamily = Poppins,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(0.85f)
                )
            }
        }

        AppBottomBar(
            currentBottomBar = BottomBars.Notifications,
            navController = navController,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun PreviewNotificationsScreen() = AppTheme {
    NotificationsScreen(navController = rememberNavController())
}
