package com.trigger.app.chats.presentation.select_contact.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trigger.app.core.domain.User
import com.trigger.app.core.presentation.ui.components.UserIcon
import com.trigger.app.core.presentation.ui.theme.AppTheme
import com.trigger.app.core.presentation.ui.theme.Poppins
import com.trigger.app.core.presentation.ui.theme.QuickSand

/**
 * A single contact row in the SelectContactScreen search results / contacts list.
 *
 * UX (after fix): tap the row to open the user's profile (chat_details screen
 * in view-only mode). Two icon buttons on the right:
 *   - person icon → open profile (same as row tap)
 *   - message icon → start / resume the conversation
 *
 * Before this fix, the row tap immediately started a conversation with no
 * way to view the user's profile first. User reported:
 * "SEARCHING SHOWING USERS, BUT THERE IS NO OPTION TO SEE THE USER PROFILE
 *  AND SEND MESSAGE" — the message button wasn't visible and there was no
 *  profile-view affordance.
 */
@Composable
fun ContactPreview(
    contact: User,
    modifier: Modifier = Modifier,
    openProfilePic: () -> Unit,
    startConversation: () -> Unit,
    viewProfile: () -> Unit = {}
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable {
                // FIX: row tap → view profile (was: start conversation).
                // User wants to inspect the profile before deciding to chat.
                viewProfile()
            }
            .padding(vertical = 8.dp)
            .padding(horizontal = 8.dp)
    ) {

        UserIcon(
            profilePic = contact.profilePic,
            iconSize = 48.dp,
            progressBarSize = 20.dp,
            progressBarThickness = (1.5).dp,
            modifier = Modifier.align(Alignment.CenterVertically),
            borderIfUsingDefaultPic = 1.dp,
            onClick = {
                // Tapping the profile pic opens the full-screen pic preview
                // (existing behavior — unchanged).
                openProfilePic()
            }
        )

        Column(modifier = Modifier
            .padding(start = 8.dp, top = 3.dp)
            .weight(1f)
            .align(Alignment.Top)) {
            Text(
                text = contact.name,
                fontFamily = QuickSand,
                fontSize = 15.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = contact.bio,
                fontFamily = Poppins,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                modifier = Modifier
                    .padding(top = 1.dp)
            )
        }

        // FIX: two icon buttons on the right side so the user can clearly
        // see both affordances (profile view + send message). Before this
        // fix, the row tap implicitly started a conversation, and there
        // was no visible "send message" or "view profile" button.
        Row(
            modifier = Modifier.align(Alignment.CenterVertically),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // View-profile icon button.
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = "View profile",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { viewProfile() }
                    .padding(8.dp)
            )

            // Send-message icon button — high-contrast accent so the user
            // can see "I can send a message to this person".
            Icon(
                imageVector = Icons.Rounded.Email,
                contentDescription = "Send message",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { startConversation() }
                    .padding(8.dp)
            )
        }
    }
}


@Preview
@Composable
private fun PreviewContactPreview() = AppTheme {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
    ) {
        ContactPreview(
            contact = User.TEST_User_LONG_BIO,
            openProfilePic = {},
            startConversation = {},
            viewProfile = {}
        )

        Spacer(Modifier.height(2.dp))

        ContactPreview(
            contact = User.TEST_User_SHORT_BIO,
            openProfilePic = {},
            startConversation = {},
            viewProfile = {}
        )
    }
}
