// Concord: new — one "Browse Categories" curator, opened from its genre tile: the playlists Apple
// publishes for that genre, as a grid of the same cards Home draws.
//
// The tile carries the curator's name, so the header is right before the read lands and no second
// request is spent on it. Every card is a playlist, and opens that playlist's page.
package com.mochyy.mochymusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.importer.account.AccountService
import com.mochyy.mochymusic.ui.component.AppleSignInPrompt
import com.mochyy.mochymusic.ui.component.HomeCardHighlight
import com.mochyy.mochymusic.ui.component.HomeCardSpacing
import com.mochyy.mochymusic.ui.component.HomeContentInset
import com.mochyy.mochymusic.ui.component.HomeCoverCard
import com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState
import com.mochyy.mochymusic.ui.screens.library.showLibraryImportSheet
import com.mochyy.mochymusic.ui.utils.backToMain
import com.mochyy.mochymusic.viewmodels.AppleCuratorViewModel

@Composable
fun AppleCuratorScreen(
    navController: NavController,
    viewModel: AppleCuratorViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val signedOut by viewModel.signedOut.collectAsState()

    val sheets = LocalZoomOutOverlayState.current
    if (signedOut) {
        AppleSignInPrompt(onSignIn = { sheets.showLibraryImportSheet(AccountService.APPLE_MUSIC) })
        return
    }

    val bottomInset = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val cardSize = (screenWidth - HomeContentInset * 2 - HomeCardSpacing) / 2

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            failed -> CuratorNotice(
                text = stringResource(R.string.apple_curator_empty),
                action = stringResource(R.string.home_apple_retry),
                onAction = { viewModel.retry() },
            )

            playlists == null -> CuratorNotice(text = stringResource(R.string.apple_shelf_loading))

            playlists.orEmpty().isEmpty() -> CuratorNotice(text = stringResource(R.string.apple_curator_empty))

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = HomeContentInset,
                    end = HomeContentInset,
                    top = 128.dp,
                    bottom = bottomInset + 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(HomeCardSpacing),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        // the tile's own name first, so the header is right before the read lands
                        text = viewModel.title.ifBlank { stringResource(R.string.apple_music_style) },
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                items(playlists.orEmpty(), key = { it.id }) { playlist ->
                    HomeCoverCard(
                        title = playlist.title,
                        subtitle = playlist.curator,
                        thumbnailUrl = AppleCovers.sized(playlist.artworkUrl, (cardSize.value * 2).toInt()),
                        size = cardSize,
                        modifier = Modifier.clickable(
                            interactionSource = null,
                            indication = HomeCardHighlight,
                            onClick = { navController.navigate("apple_playlist/${playlist.id}") },
                        ),
                    )
                }
            }
        }

        IconButton(
            onClick = { if (!navController.navigateUp()) navController.backToMain() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 48.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.arrow_back),
                contentDescription = stringResource(R.string.action_back),
                tint = Color.White,
            )
        }
    }
}

/** one line in the middle of the page: a read under way, a genre with nothing in it, or a retry */
@Composable
private fun CuratorNotice(
    text: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (action != null && onAction != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = action,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}
