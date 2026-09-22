package com.trigger.app.core.presentation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.trigger.app.R
import com.trigger.app.core.presentation.ui.AllChats
import com.trigger.app.core.presentation.ui.Calls
import com.trigger.app.core.presentation.ui.Groups
import com.trigger.app.core.presentation.ui.MyProfile
import com.trigger.app.core.presentation.ui.Stories
import com.trigger.app.core.presentation.ui.theme.AppTheme
import com.trigger.app.core.presentation.ui.theme.DarkBlue
import com.trigger.app.core.presentation.ui.theme.LightGrey
import com.trigger.app.core.presentation.ui.theme.QuickSand

enum class BottomBars {
    AllChats, Groups, Stories, Calls, Profile
}

// 5 tabs: Chats | Groups | Stories | Calls | Profile
// The "Start a chat" (+) action used to live here as a center-docked FAB; it has
// moved to the top-right of the AllChats screen's app bar.
@Composable
fun AppBottomBar(
    currentBottomBar: BottomBars,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(vertical = 12.dp, horizontal = 8.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .clip(RoundedCornerShape(20))
        ) {
            drawRect(DarkBlue)
            // NOTE: a `drawCircle` call used to live here, drawing a lighter-blue
            // circle ABOVE the canvas to create a half-moon backdrop for the
            // center-docked FAB. With the FAB removed (per the 5-tab layout),
            // that half-circle is now an orphan — a visible "half-round crop"
            // floating in the bar. Removed entirely; the bar is now a clean
            // solid DarkBlue rounded rectangle.
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(60.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            BottomAppBarItem(
                isActive = BottomBars.AllChats.isCurrentScreen(currentBottomBar),
                icon = painterResource(id = R.drawable.medium_messages),
                content = stringResource(R.string.chats),
                modifier = Modifier.weight(1f),
                onClick = {
                    if (!BottomBars.AllChats.isCurrentScreen(currentBottomBar))
                        navController.navigate(AllChats)
                }
            )

            BottomAppBarItem(
                isActive = BottomBars.Groups.isCurrentScreen(currentBottomBar),
                icon = painterResource(id = R.drawable.groups),
                content = stringResource(R.string.groups),
                modifier = Modifier.weight(1f),
                onClick = {
                    if (!BottomBars.Groups.isCurrentScreen(currentBottomBar))
                        navController.navigate(Groups)
                }
            )

            BottomAppBarItem(
                isActive = BottomBars.Stories.isCurrentScreen(currentBottomBar),
                icon = painterResource(id = R.drawable.stories),
                content = stringResource(R.string.stories),
                modifier = Modifier.weight(1f),
                onClick = {
                    if (!BottomBars.Stories.isCurrentScreen(currentBottomBar))
                        navController.navigate(Stories)
                }
            )

            BottomAppBarItem(
                isActive = BottomBars.Calls.isCurrentScreen(currentBottomBar),
                icon = painterResource(id = R.drawable.med_calls),
                content = stringResource(R.string.calls),
                modifier = Modifier.weight(1f),
                onClick = {
                    if (!BottomBars.Calls.isCurrentScreen(currentBottomBar))
                        navController.navigate(Calls)
                }
            )

            BottomAppBarItem(
                isActive = BottomBars.Profile.isCurrentScreen(currentBottomBar),
                imageVector = Icons.Rounded.Person,
                content = stringResource(R.string.profile),
                modifier = Modifier.weight(1f),
                onClick = {
                    if (!BottomBars.Profile.isCurrentScreen(currentBottomBar))
                        navController.navigate(MyProfile)
                }
            )
        }
    }
}


@Composable
fun BottomAppBarItem(
    isActive: Boolean,
    icon: Painter,
    content: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) = BottomAppBarItemImpl(
    isActive = isActive,
    iconPainter = icon,
    imageVector = null,
    content = content,
    onClick = onClick,
    modifier = modifier
)

@Composable
fun BottomAppBarItem(
    isActive: Boolean,
    imageVector: ImageVector,
    content: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) = BottomAppBarItemImpl(
    isActive = isActive,
    iconPainter = rememberVectorPainter(imageVector),
    imageVector = null,
    content = content,
    onClick = onClick,
    modifier = modifier
)

@Composable
private fun BottomAppBarItemImpl(
    isActive: Boolean,
    iconPainter: Painter,
    imageVector: ImageVector?,
    content: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable { onClick() }
    ) {
        Icon(
            painter = iconPainter,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (isActive) Color.White else LightGrey
        )

        Text(
            text = content,
            modifier = Modifier.padding(top = 2.dp),
            fontFamily = QuickSand,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = if (isActive) Color.White else LightGrey
        )
    }
}

fun BottomBars.isCurrentScreen(currentBottomBar: BottomBars) = this == currentBottomBar

@Preview
@Composable
private fun PreviewAppBottomBar() = AppTheme {
    AppBottomBar(currentBottomBar = BottomBars.AllChats, navController = rememberNavController())
}
