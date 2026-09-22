package com.trigger.app.stories.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trigger.app.R
import com.trigger.app.core.presentation.ui.theme.AppTheme
import com.trigger.app.core.presentation.ui.theme.DarkBlue
import com.trigger.app.core.presentation.ui.theme.LightGrey
import com.trigger.app.core.presentation.ui.theme.QuickSand

/** Maximum characters allowed in a story reply. Mirrors the
 *  `message.size() <= 500` check in `firestore.rules` (story/replies path). */
private const val MAX_REPLY_LENGTH = 500

/**
 * Story reply bar — renders a TextField + Send button.
 *
 * Caller contract: this composable assumes it's rendered over a dark
 * background (story viewer uses DarkBlue). The text color is locked to white
 * via [textColor] — pass a different color if you reuse this on a light
 * surface.
 */
@Composable
fun ViewStoryMessageBar(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSendClick: () -> Unit = {},
    textColor: Color = Color.White,  // themable so this isn't dark-only
) {
    // Whitespace-trimmed non-empty check — a single space, a newline, or
    // pure whitespace is NOT a valid reply. Backend rule mirrors this.
    val canSend = text.trim().isNotEmpty()

    // Single source of truth for "should we send" — used by both the
    // IME Send key AND the Send TextButton.
    val sendIfValid: () -> Unit = {
        if (canSend) onSendClick()
    }

    Row {
        Surface(
            modifier = modifier
                .weight(1f)
                .padding(start = 8.dp),
            shape = RoundedCornerShape(60),
            // Bumped from 0.5.dp → 1.5.dp: 0.5dp rounds to 1px on most
            // density buckets, nearly invisible on hi-DPI phones.
            border = BorderStroke(1.5.dp, textColor)
        ) {
            TextField(
                value = text,
                onValueChange = { newValue ->
                    // Cap input length client-side. Backend rules enforce the
                    // same 500-char limit server-side (defense in depth).
                    if (newValue.length <= MAX_REPLY_LENGTH) onTextChange(newValue)
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle.Default.copy(
                    fontSize = 14.sp,
                    fontFamily = QuickSand,
                    fontWeight = FontWeight.Medium
                ),
                // Send key on the soft keyboard fires the same onSendClick
                // as the Send TextButton. Both respect the whitespace-trim
                // guard.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendIfValid() }),
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    focusedTextColor = textColor,
                    unfocusedTextColor = textColor,
                    unfocusedPlaceholderColor = LightGrey,
                    focusedPlaceholderColor = LightGrey,
                    cursorColor = textColor
                ),
                placeholder = {
                    Text(
                        text = stringResource(id = R.string.send_message),
                        style = TextStyle.Default.copy(
                            fontSize = 14.sp,
                            fontFamily = QuickSand,
                            fontWeight = FontWeight.Medium
                        ),
                    )
                }
            )
        }

        TextButton(
            onClick = sendIfValid,
            modifier = Modifier
                .padding(end = 8.dp, start = 4.dp)
                .align(Alignment.CenterVertically),
            enabled = canSend
        ) {
            Text(
                text = stringResource(R.string.send),
                color = if (canSend) DarkBlue else LightGrey,
                fontSize = 15.sp,
                fontFamily = QuickSand,
                fontWeight = FontWeight.Medium
            )
        }
    }
}


@Preview
@Composable
private fun PreviewViewStoryMessageBar() = AppTheme {
    var message by remember { mutableStateOf("") }

    Column(
        Modifier
            .height(100.dp)
            .background(DarkBlue)
    ) {
        ViewStoryMessageBar(
            text = message,
            onTextChange = { message = it },
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
