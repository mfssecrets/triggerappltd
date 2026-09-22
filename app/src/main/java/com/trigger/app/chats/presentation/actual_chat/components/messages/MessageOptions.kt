package com.trigger.app.chats.presentation.actual_chat.components.messages

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trigger.app.R
import com.trigger.app.chats.domain.Message
import com.trigger.app.chats.domain.toMessageType
import com.trigger.app.chats.presentation.utils.toDayMonthAndTime
import com.trigger.app.core.presentation.ui.theme.ErrorRed
import com.trigger.app.core.presentation.ui.theme.LocalAppColors
import com.trigger.app.core.presentation.ui.theme.QuickSand
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase


@Composable
fun MessageOptions(
    message: Message,
    modifier: Modifier = Modifier,
    copyToClipboard: (String) -> Unit,
    editMessage: (() -> Unit)?,
    unSendMessage: ((String) -> Unit)?
) {
    // Confirmation dialog for the unsend action — prevents accidental
    // message deletion (the previous implementation deleted on first tap,
    // no undo, no warning).
    var showUnsendConfirmation by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth(0.5f)
            .clickable { }
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = LocalAppColors.current.appThemeTextColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp)) {
            Text(
                text = message.timeSent.toDayMonthAndTime(),
                fontFamily = QuickSand,
                modifier = Modifier.padding(vertical = 2.dp),
                fontSize = 13.sp
            )
            MessageOption(
                icon = R.drawable.reply,
                name = stringResource(R.string.reply),
                onOptionSelected = { })
            MessageOption(
                icon = R.drawable.forward,
                name = stringResource(R.string.forward),
                onOptionSelected = { })

            if (editMessage != null && message.senderID == Firebase.auth.uid) {
                MessageOption(
                    icon = R.drawable.edit,
                    name = stringResource(R.string.edit),
                    onOptionSelected = { editMessage() })
            }

            MessageOption(
                icon = R.drawable.copy,
                name = stringResource(R.string.copy),
                onOptionSelected = { copyToClipboard(message.messageType.toMessageType().message) })

            if (unSendMessage != null && message.senderID == Firebase.auth.uid) {
                MessageOption(
                    icon = R.drawable.unsend,
                    name = stringResource(R.string.unsend),
                    tint = ErrorRed,
                    onOptionSelected = { showUnsendConfirmation = true }
                )
            }
        }
    }

    if (showUnsendConfirmation) {
        AlertDialog(
            onDismissRequest = { showUnsendConfirmation = false },
            title = { Text(stringResource(R.string.confirm_unsend)) },
            text = { Text(stringResource(R.string.confirm_unsend_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showUnsendConfirmation = false
                    unSendMessage?.invoke(message.messageID)
                }) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showUnsendConfirmation = false }) {
                    Text(stringResource(R.string.no))
                }
            }
        )
    }
}

@Composable
fun MessageOption(
    @DrawableRes icon: Int,
    name: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalAppColors.current.appThemeTextColor,
    onOptionSelected: () -> Unit
) {
    Row(
        modifier = modifier
            .padding(vertical = 8.dp)
            .fillMaxWidth()
            .clickable(onClick = { onOptionSelected() }),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = name, fontSize = 14.sp, color = tint, fontFamily = QuickSand)

        Icon(
            painter = painterResource(id = icon),
            contentDescription = null,
            Modifier.size(20.dp),
            tint = tint
        )
    }
}
