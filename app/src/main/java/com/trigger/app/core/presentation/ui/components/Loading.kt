package com.trigger.app.core.presentation.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trigger.app.R
import com.trigger.app.core.presentation.ui.theme.AppTheme
import com.trigger.app.core.presentation.ui.theme.QuickSand

@Composable
fun LoadingSpinner(modifier: Modifier = Modifier) {
    // FIX (aba6585 follow-up): the previous colors were:
    //   containerColor = background  → card was the SAME color as the page
    //                                 background → invisible card
    //   color        = surface      → DarkBlue arc on DarkBlack card →
    //                                  invisible spinner
    //   trackColor   = background   → track was the SAME color as the card →
    //                                  invisible track
    // The user saw "black card on black background with dark spinner".
    //
    // Fix: card uses `surface` (DarkBlue) so it visually stands out from the
    // page background (DarkBlack in dark mode, White in light mode).
    // Spinner arc + track + text use `onSurface` (White in both modes) so
    // they're high-contrast against the DarkBlue card.
    Card(
        modifier.fillMaxWidth(0.38f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp)
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onSurface,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f),
                strokeWidth = 4.dp,
                modifier = Modifier.size(42.dp)
            )

            Text(
                text = stringResource(id = R.string.loading),
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = QuickSand,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}


@Composable
fun LoadingSpinnerWithProgress(progress: Int, modifier: Modifier = Modifier) {
    // FIX: same color fix as LoadingSpinner above. Card on surface (DarkBlue),
    // arc + track + text on onSurface (White). Was: card=background,
    // arc=surface (invisible on card), track=background (invisible on card).
    Card(
        modifier.fillMaxWidth(0.38f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp)
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onSurface,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f),
                strokeWidth = 4.dp,
                modifier = Modifier.size(42.dp)
            )

            Text(
                text = stringResource(
                    R.string.loading_with_progress,
                    progress
                ),
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = QuickSand,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}


@Preview
@Composable
private fun PreviewLoadingSpinner() = AppTheme {
    Column(Modifier.fillMaxSize()) {
        LoadingSpinner()
    }
}
