// Concord: new — one Home shelf, opened from its title.
//
// Apple answers this page with the shelf's whole card list in one read (see `AppleClient.homeShelf`),
// so it is a grid of Home's own cards and nothing else: no second card, no second tap target.
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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.AppleHomeItem
import com.mochyy.mochymusic.importer.account.AccountService
import com.mochyy.mochymusic.ui.screens.library.showLibraryImportSheet
import com.mochyy.mochymusic.ui.component.AppleSignInPrompt
import com.mochyy.mochymusic.ui.component.HomeCardHighlight
import com.mochyy.mochymusic.ui.component.HomeCardSpacing
import com.mochyy.mochymusic.ui.component.HomeContentInset
import com.mochyy.mochymusic.ui.component.HomeCoverCard
import com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState
import com.mochyy.mochymusic.ui.utils.backToMain
import com.mochyy.mochymusic.viewmodels.AppleShelfViewModel
import kotlinx.coroutines.launch

@Composable
fun AppleShelfScreen(
    navController: NavController,
    viewModel: AppleShelfViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val shelf by viewModel.shelf.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val signedOut by viewModel.signedOut.collectAsState()

    // signing in is Mochy's own import sheet, opened on Apple's sign-in
    val sheets = LocalZoomOutOverlayState.current
    if (signedOut) {
        AppleSignInPrompt(onSignIn = { sheets.showLibraryImportSheet(AccountService.APPLE_MUSIC) })
        return
    }

    val scope = rememberCoroutineScope()
    val bottomInset = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val cardSize = (screenWidth - HomeContentInset * 2 - HomeCardSpacing) / 2
    val open: (AppleHomeItem) -> Unit = { item ->
        scope.launch {
            openAppleHomeItem(
                item = item,
                navController = navController,
                database = viewModel.database,
                stationQueue = { id -> viewModel.stationQueue(id) },
                playStation = { queue -> playerConnection.playQueue(queue) },
                openReplay = { openAppleReplay(navController, viewModel) },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val group = shelf
        when {
            failed -> ShelfNotice(
                text = stringResource(R.string.apple_shelf_empty),
                action = stringResource(R.string.home_apple_retry),
                onAction = { viewModel.retry() },
            )
            group == null -> ShelfNotice(text = stringResource(R.string.apple_shelf_loading))
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
                        // Home's title first, so the header is right before the read lands
                        text = group.title.ifBlank { viewModel.title },
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                items(group.items, key = { it.id }) { item ->
                    HomeCoverCard(
                        title = item.title,
                        subtitle = item.subtitle,
                        thumbnailUrl = AppleCovers.sized(item.artworkUrl, (cardSize.value * 2).toInt()),
                        motionUrl = item.motionUrl,
                        size = cardSize,
                        sharedElementKey = if (item.kind == AppleHomeItem.Kind.Album) "album-${item.id}" else null,
                        modifier = Modifier.clickable(
                            interactionSource = null,
                            indication = HomeCardHighlight,
                            onClick = { open(item) },
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

/** one line in the middle of the page: a read under way, or a shelf Apple no longer lists */
@Composable
internal fun ShelfNotice(
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
