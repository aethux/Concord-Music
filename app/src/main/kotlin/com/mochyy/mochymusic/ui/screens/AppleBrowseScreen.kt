// Concord: new — Apple's Browse page, as the "New" tab, and one section's room.
//
// The content is the editorial tree Cider's Browse page reads (`AppleBrowseReader`); the drawing is
// Home's own — a section title, a card row, Home's cover card, the search page's song row — so the
// page looks like this app and not like another client. A section's title opens its room, which is
// what Apple's own "View all" does; the room draws the same two shapes over its whole list.
package com.mochyy.mochymusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.AppleBrowseSection
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.AppleRadioItem
import com.mochyy.mochymusic.applesync.AppleSearchItem
import com.mochyy.mochymusic.importer.account.AccountService
import com.mochyy.mochymusic.playback.PlayerConnection
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.ui.component.AppleRadioCard
import com.mochyy.mochymusic.ui.component.AppleSignInPrompt
import com.mochyy.mochymusic.ui.component.BonesHost
import com.mochyy.mochymusic.ui.component.HomeCardSpacing
import com.mochyy.mochymusic.ui.component.HomeContentInset
import com.mochyy.mochymusic.ui.component.HomeSectionBones
import com.mochyy.mochymusic.ui.component.HomeSectionTitle
import com.mochyy.mochymusic.ui.component.HomeViewAllCard
import com.mochyy.mochymusic.ui.component.IconButton as MochyIconButton
import com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState
import com.mochyy.mochymusic.ui.component.RevealSeenState
import com.mochyy.mochymusic.ui.component.homeCardSize
import com.mochyy.mochymusic.ui.component.rememberRevealSeenState
import com.mochyy.mochymusic.ui.menu.songedit.VideoPickerSheet
import com.mochyy.mochymusic.ui.player.asMediaItem
import com.mochyy.mochymusic.ui.screens.library.showLibraryImportSheet
import com.mochyy.mochymusic.ui.screens.search.SearchResultRow
import com.mochyy.mochymusic.ui.utils.backToMain
import com.mochyy.mochymusic.viewmodels.AppleBrowseViewModel
import com.mochyy.mochymusic.viewmodels.AppleChartsViewModel
import kotlinx.coroutines.launch

/** A song shelf shows its first few rows and its title is the way to the rest, as Apple's page does. */
private const val SongPreviewRows = 5

@Composable
fun AppleBrowseScreen(
    navController: NavController,
    viewModel: AppleBrowseViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val sections by viewModel.sections.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val signedOut by viewModel.signedOut.collectAsState()

    // signing in is Mochy's own import sheet, opened on Apple's sign-in
    val sheets = LocalZoomOutOverlayState.current
    if (signedOut) {
        AppleSignInPrompt(onSignIn = { sheets.showLibraryImportSheet(AccountService.APPLE_MUSIC) })
        return
    }

    val scope = rememberCoroutineScope()
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
        AppleBrowseTopBar(title = stringResource(R.string.browse_new), navController = null)
        when {
            failed || sections?.isEmpty() == true -> Box(Modifier.weight(1f)) {
                ShelfNotice(
                    text = stringResource(R.string.apple_shelf_empty),
                    action = stringResource(R.string.home_apple_retry),
                    onAction = { viewModel.retry() },
                )
            }
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 10.dp, bottom = bottomInset + 24.dp),
            ) {
                val loaded = sections
                if (loaded == null) {
                    // Home's own bones, in the same footprint, while Apple's tree arrives
                    item(key = "browse_bones", contentType = "bones") {
                        BonesHost {
                            repeat(2) {
                                HomeSectionBones()
                                Spacer(Modifier.height(34.dp))
                            }
                        }
                    }
                } else {
                    loaded.forEach { section ->
                        browseSectionRows(
                            section = section,
                            cardSize = cardSize,
                            revealSeen = revealSeen,
                            open = open,
                            playSongs = { songs, start -> playBrowseSongs(playerConnection, songs, start) },
                            openRoom = { clicked ->
                                // the two chart rows Apple puts on New open the Charts page; every
                                // other section's "View all" is its room
                                if (clicked is AppleBrowseSection.Cards && clicked.charts) {
                                    navController.navigate("apple_charts/${AppleChartsViewModel.Root}")
                                } else {
                                    navController.navigate(
                                        "apple_browse_room/${clicked.roomId}?title=" +
                                            android.net.Uri.encode(clicked.title)
                                    )
                                }
                            },
                        )
                    }
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

@Composable
fun AppleBrowseRoomScreen(
    navController: NavController,
    viewModel: AppleBrowseViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val room by viewModel.room.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val signedOut by viewModel.signedOut.collectAsState()

    val sheets = LocalZoomOutOverlayState.current
    if (signedOut) {
        AppleSignInPrompt(onSignIn = { sheets.showLibraryImportSheet(AccountService.APPLE_MUSIC) })
        return
    }

    val context = LocalContext.current
    val bottomInset = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val cardSize = (screenWidth - HomeContentInset * 2 - HomeCardSpacing) / 2
    var pickVideoFor by remember { mutableStateOf<AppleRadioItem?>(null) }
    val open = browseOpener(navController, context, playerConnection, pickVideoFor = { pickVideoFor = it }) { id ->
        viewModel.stationQueue(id)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // the title the row had first, so the header is right before the read lands
        AppleBrowseTopBar(
            title = viewModel.title.ifBlank { room?.title.orEmpty() },
            navController = navController,
        )
        val shown = room
        when {
            failed -> Box(Modifier.weight(1f)) {
                ShelfNotice(
                    text = stringResource(R.string.apple_shelf_empty),
                    action = stringResource(R.string.home_apple_retry),
                    onAction = { viewModel.retry() },
                )
            }
            shown == null -> Box(Modifier.weight(1f)) {
                ShelfNotice(text = stringResource(R.string.apple_shelf_loading))
            }
            shown.songs.isNotEmpty() -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 6.dp, bottom = bottomInset + 24.dp),
            ) {
                songRows(
                    sectionId = "room",
                    songs = shown.songs,
                    playSongs = { songs, start -> playBrowseSongs(playerConnection, songs, start) },
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = HomeContentInset,
                    end = HomeContentInset,
                    top = 10.dp,
                    bottom = bottomInset + 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(HomeCardSpacing),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(
                    count = shown.cards.size,
                    key = { index -> "${shown.cards[index].kind}_${shown.cards[index].id}_$index" },
                ) { index ->
                    val item = shown.cards[index]
                    AppleRadioCard(item = item, size = cardSize, onClick = { open(item) })
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

/** One section on the New page: a title, then its cards or its first few songs. */
private fun LazyListScope.browseSectionRows(
    section: AppleBrowseSection,
    cardSize: Dp,
    revealSeen: RevealSeenState,
    open: (AppleRadioItem) -> Unit,
    playSongs: (List<AppleSearchItem>, AppleSearchItem) -> Unit,
    openRoom: (AppleBrowseSection) -> Unit,
) {
    val titled = section.title.isNotBlank()
    if (titled) {
        item(key = "title_${section.id}", contentType = "title") {
            HomeSectionTitle(title = section.title, onClick = { openRoom(section) })
        }
    }
    when (section) {
        is AppleBrowseSection.Cards -> item(key = "row_${section.id}", contentType = "cardRow") {
            HomeCardRow(
                rowKey = "browse_${section.id}",
                items = section.items.mapIndexed { index, item -> index to item },
                revealSeen = revealSeen,
                key = { (index, item) -> "${item.kind}_${item.id}_$index" },
                trailing = if (titled) {
                    { HomeViewAllCard(size = cardSize, onClick = { openRoom(section) }) }
                } else {
                    null
                },
            ) { (_, item) ->
                AppleRadioCard(item = item, size = cardSize, onClick = { open(item) })
            }
        }
        is AppleBrowseSection.Songs -> songRows(
            sectionId = section.id,
            songs = section.items.take(SongPreviewRows),
            playSongs = playSongs,
        )
    }
    item(key = "gap_${section.id}", contentType = "gap") {
        Spacer(Modifier.height(26.dp))
    }
}

internal fun LazyListScope.songRows(
    sectionId: String,
    songs: List<AppleSearchItem>,
    playSongs: (List<AppleSearchItem>, AppleSearchItem) -> Unit,
) {
    songs.forEach { song ->
        item(key = "song_${sectionId}_${song.id}", contentType = "song") {
            SearchResultRow(
                title = song.title,
                subtitle = listOfNotNull(
                    song.subtitle,
                    song.durationMs?.let { com.mochyy.mochymusic.utils.makeTimeString(it) }
                        ?.takeIf { text -> text.isNotEmpty() },
                ).joinToString("  •  "),
                artworkUrl = AppleCovers.sized(song.artworkUrl),
                onClick = { playSongs(songs, song) },
                // the page's own inset, so a song row lines up with the titles and cards above it
                horizontalPadding = HomeContentInset,
            )
        }
    }
}

/** The songs a shelf shows, as one queue starting where it was tapped — Apple's own behaviour. */
internal fun playBrowseSongs(
    playerConnection: PlayerConnection,
    songs: List<AppleSearchItem>,
    start: AppleSearchItem,
) {
    val index = songs.indexOfFirst { it.id == start.id }.coerceAtLeast(0)
    playerConnection.playQueue(
        ListQueue(items = songs.map { it.asMediaItem() }, startIndex = index),
    )
}

/** What every card on either page opens, with Mochy's picker offered for a video YouTube can't find. */
@Composable
internal fun browseOpener(
    navController: NavController,
    context: android.content.Context,
    playerConnection: PlayerConnection,
    pickVideoFor: (AppleRadioItem) -> Unit,
    stationQueue: suspend (String) -> com.mochyy.mochymusic.playback.queues.AppleStationQueue?,
): (AppleRadioItem) -> Unit {
    val scope = rememberCoroutineScope()
    return { item ->
        scope.launch {
            openAppleRadioItem(
                item = item,
                navController = navController,
                context = context,
                playerConnection = playerConnection,
                stationQueue = stationQueue,
                onVideoPick = pickVideoFor,
            )
        }
    }
}

/**
 * The page's own bar: the tab's title, and on a room the back arrow every pushed page wears.
 * A tab root has nowhere to go back to, so it draws no arrow.
 */
@Composable
internal fun AppleBrowseTopBar(title: String, navController: NavController?) {
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                // the tab's page lines its title up with its own cards and section titles (Home's
                // 36 dp inset); a pushed room keeps Material's bar inset, as every pushed page does
                modifier = if (navController == null) {
                    Modifier.padding(start = HomeContentInset - 16.dp)
                } else {
                    Modifier
                },
            )
        },
        navigationIcon = {
            if (navController != null) {
                MochyIconButton(
                    onClick = { if (!navController.navigateUp()) navController.backToMain() },
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back_ios),
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            }
        },
    )
}
