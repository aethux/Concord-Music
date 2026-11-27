// Concord: new — Apple's Charts page: one genre's charts, with Apple's own genre chips above them.
//
// Opened from the two chart rows Apple puts on the New tab (Daily Top 100, City Charts). Everything
// drawn here is a piece this app already has: `AppleBrowseTopBar` for a pushed page, the search
// page's chip row and song row, Home's section title, card row and cover card. Apple's own order
// comes from the response (`AppleChartsReader`), so the page never invents a running order.
package com.mochyy.mochymusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.AppleChartGroup
import com.mochyy.mochymusic.applesync.AppleRadioItem
import com.mochyy.mochymusic.applesync.AppleSearchItem
import com.mochyy.mochymusic.importer.account.AccountService
import com.mochyy.mochymusic.playback.PlayerConnection
import com.mochyy.mochymusic.ui.component.AppleRadioCard
import com.mochyy.mochymusic.ui.component.AppleSignInPrompt
import com.mochyy.mochymusic.ui.component.HomeContentInset
import com.mochyy.mochymusic.ui.component.HomeSectionTitle
import com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState
import com.mochyy.mochymusic.ui.component.RevealSeenState
import com.mochyy.mochymusic.ui.component.homeCardSize
import com.mochyy.mochymusic.ui.component.rememberRevealSeenState
import com.mochyy.mochymusic.ui.menu.songedit.VideoPickerSheet
import com.mochyy.mochymusic.ui.screens.library.showLibraryImportSheet
import com.mochyy.mochymusic.ui.screens.search.SearchCategoryRow
import com.mochyy.mochymusic.ui.screens.search.SearchHorizontalPadding
import com.mochyy.mochymusic.viewmodels.AppleChartsViewModel

@Composable
fun AppleChartsScreen(
    navController: NavController,
    viewModel: AppleChartsViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val genres by viewModel.genres.collectAsState()
    val page by viewModel.page.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val signedOut by viewModel.signedOut.collectAsState()

    val sheets = LocalZoomOutOverlayState.current
    if (signedOut) {
        AppleSignInPrompt(onSignIn = { sheets.showLibraryImportSheet(AccountService.APPLE_MUSIC) })
        return
    }

    val context = LocalContext.current
    val bottomInset = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val cardSize = homeCardSize()
    val revealSeen = rememberRevealSeenState()
    var pickVideoFor by remember { mutableStateOf<AppleRadioItem?>(null) }
    val open = browseOpener(navController, context, playerConnection, pickVideoFor = { pickVideoFor = it }) { id ->
        viewModel.stationQueue(id)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AppleBrowseTopBar(title = stringResource(R.string.charts_title), navController = navController)

        val chips = genres
        if (!chips.isNullOrEmpty()) {
            SearchCategoryRow(
                categories = chips.map { it.name },
                selectedIndex = chips.indexOfFirst { it.id == selected }.coerceAtLeast(0),
                onSelect = { index -> chips.getOrNull(index)?.let { viewModel.select(it.id) } },
                // the page's own inset (Home's 36 dp), so the chips line up with the titles and the
                // rows under them instead of sitting 12 dp further in
                modifier = Modifier.padding(horizontal = HomeContentInset - SearchHorizontalPadding),
            )
        }

        val shown = page
        when {
            failed -> Box(Modifier.weight(1f)) {
                ShelfNotice(
                    text = stringResource(R.string.apple_charts_empty),
                    action = stringResource(R.string.home_apple_retry),
                    onAction = { viewModel.retry() },
                )
            }
            shown == null -> Box(Modifier.weight(1f)) {
                ShelfNotice(text = stringResource(R.string.apple_shelf_loading))
            }
            shown.groups.isEmpty() -> Box(Modifier.weight(1f)) {
                ShelfNotice(text = stringResource(R.string.apple_charts_empty))
            }
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 10.dp, bottom = bottomInset + 24.dp),
            ) {
                shown.groups.forEach { group ->
                    chartGroupRows(
                        group = group,
                        cardSize = cardSize,
                        revealSeen = revealSeen,
                        open = open,
                        playSongs = { songs, start -> playBrowseSongs(playerConnection, songs, start) },
                    )
                }
            }
        }
    }

    pickVideoFor?.let { item ->
        VideoPickerSheet(
            songId = "AM_${item.id}",
            title = item.title,
            artist = item.subtitle.orEmpty(),
            onDismiss = { pickVideoFor = null },
        )
    }
}

/** One chart: Apple's name for it, then its ranked rows — songs as rows, everything else as cards. */
private fun LazyListScope.chartGroupRows(
    group: AppleChartGroup,
    cardSize: Dp,
    revealSeen: RevealSeenState,
    open: (AppleRadioItem) -> Unit,
    playSongs: (List<AppleSearchItem>, AppleSearchItem) -> Unit,
) {
    item(key = "title_${group.id}", contentType = "title") {
        HomeSectionTitle(title = group.title)
    }
    if (group.songs.isNotEmpty()) {
        songRows(sectionId = "chart_${group.id}", songs = group.songs, playSongs = playSongs)
    }
    if (group.cards.isNotEmpty()) {
        item(key = "row_${group.id}", contentType = "cardRow") {
            HomeCardRow(
                rowKey = "charts_${group.id}",
                items = group.cards.mapIndexed { index, item -> index to item },
                revealSeen = revealSeen,
                key = { (index, item) -> "${item.kind}_${item.id}_$index" },
            ) { (_, item) ->
                AppleRadioCard(item = item, size = cardSize, onClick = { open(item) })
            }
        }
    }
    item(key = "gap_${group.id}", contentType = "gap") {
        Spacer(Modifier.height(26.dp))
    }
}
