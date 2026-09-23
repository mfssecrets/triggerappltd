package com.trigger.app.calls.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trigger.app.core.presentation.ui.components.UserIcon

/**
 * Shared scaffold for all three call screens (Outgoing / Incoming / InCall).
 *
 * Layout (top-to-bottom):
 *   - Spacer (top padding)
 *   - Avatar (large, centered)
 *   - Name (large bold text)
 *   - Status text ("Calling...", "Connecting...", "00:05")
 *   - Optional spinner
 *   - Spacer (flex)
 *   - Primary action button row at the bottom (cancel / accept-decline / hangup-mute-speaker)
 *   - Spacer (bottom safe area)
 *
 * Background is the theme's `background` color (DarkBlack in dark mode,
 * White in light mode) so the screen is high-contrast.
 */
@Composable
fun CallStatusScaffold(
    callType: String,
    name: String,
    profilePic: String?,
    statusText: String,
    showSpinner: Boolean,
    primaryButton: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(80.dp))

        UserIcon(
            profilePic = profilePic,
            iconSize = 120.dp,
            progressBarSize = 40.dp,
            progressBarThickness = 3.dp,
            borderIfUsingDefaultPic = 2.dp,
            onClick = { /* no-op — call screens don't open profile pic */ }
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = name,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = statusText,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            fontSize = 16.sp
        )

        if (showSpinner) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onBackground,
                strokeWidth = 2.dp,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            primaryButton()
        }
    }
}


/**
 * Circular icon button used in call screens.
 *
 * @param backgroundColor circle background
 * @param iconTint icon color (defaults to white for visibility on red/green backgrounds)
 * @param size circle diameter (defaults to 56.dp — comfortable tap target)
 */
@Composable
fun CallIconButton(
    icon: ImageVector,
    contentDescription: String,
    backgroundColor: Color,
    onClick: () -> Unit,
    iconTint: Color = Color.White,
    size: Dp = 56.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}
