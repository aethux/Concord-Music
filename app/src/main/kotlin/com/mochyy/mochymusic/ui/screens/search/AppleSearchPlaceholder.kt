// Concord: new — the Search tab until Apple's catalogue search exists.
//
// YouTube's browse, genres and suggestions were the whole page, and they are hidden now, so this
// states that plainly instead of asking innertube for shelves it would only fail to draw.
package com.mochyy.mochymusic.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mochyy.mochymusic.R

/**
 * The Search tab with YouTube hidden: a note, and nothing else until Apple's catalogue search is
 * built. [navController] keeps the same signature the real search page will have.
 */
@Composable
fun AppleSearchPlaceholder(@Suppress("UNUSED_PARAMETER") navController: NavController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.search),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )
        Text(
            text = stringResource(R.string.search_apple_coming),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
