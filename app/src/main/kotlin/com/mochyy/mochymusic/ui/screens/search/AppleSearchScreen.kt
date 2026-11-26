// Concord: new — the Search tab: Apple's catalogue, drawn in Mochy's search chrome.
//
// One page and one field. An empty field is Apple's "Browse Categories" landing and, once the field
// is in use, the search history. Typing shows Apple's suggested terms and the hits already in the
// library. A submitted term draws Apple's own shelves in Apple's own order (`meta.results.order`),
// with the library's hits above them and the chip row filtering that one answer in place — no
// second request per chip, and no second page per search.
package com.mochyy.mochymusic.ui.screens.search

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalDatabase
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.AppleSearchCategory
import com.mochyy.mochymusic.applesync.AppleSearchItem
import com.mochyy.mochymusic.applesync.AppleSearchSection
import com.mochyy.mochymusic.constants.PauseSearchHistoryKey
import com.mochyy.mochymusic.constants.ProfilePicUriKey
import com.mochyy.mochymusic.db.entities.Album
import com.mochyy.mochymusic.db.entities.Artist
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.LocalItem
import com.mochyy.mochymusic.db.entities.Playlist
import com.mochyy.mochymusic.db.entities.SearchHistory
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.db.entities.SongSource
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.importer.account.AccountService
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.ui.component.AppleSignInPrompt
import com.mochyy.mochymusic.ui.component.GlassState
import com.mochyy.mochymusic.ui.component.HomeSectionTitle
import com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState
import com.mochyy.mochymusic.ui.component.RemoteFocus
import com.mochyy.mochymusic.ui.component.TopToaster
import com.mochyy.mochymusic.ui.component.glassRoot
import com.mochyy.mochymusic.ui.component.navigateToTab
import com.mochyy.mochymusic.ui.player.findOfficialMusicVideoId
import com.mochyy.mochymusic.ui.player.playYouTubeVideo
import com.mochyy.mochymusic.ui.screens.library.showLibraryImportSheet
import com.mochyy.mochymusic.utils.makeTimeString
import com.mochyy.mochymusic.utils.rememberPreference
import com.mochyy.mochymusic.viewmodels.AppleSearchViewModel
import com.mochyy.mochymusic.viewmodels.LocalFilter
import com.mochyy.mochymusic.viewmodels.LocalSearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The width of a video card in the All view's single horizontal row. */
private val VideoCardWidth = 230.dp

/** Home separates its sections with air, not rules; this is the same distance it uses. */
private val SectionSpacing = 34.dp

/** The result kinds the chip row offers, in Apple's own order. `Kind` null is "everything". */
private enum class SearchChip(val labelRes: Int, val kind: AppleSearchItem.Kind?) {
    All(R.string.search_category_all, null),
    Songs(R.string.apple_search_songs, AppleSearchItem.Kind.Song),
    Albums(R.string.apple_search_albums, AppleSearchItem.Kind.Album),
    Artists(R.string.apple_search_artists, AppleSearchItem.Kind.Artist),
    Playlists(R.string.apple_search_playlists, AppleSearchItem.Kind.Playlist),
    Videos(R.string.apple_search_videos, AppleSearchItem.Kind.Video),
}

/** The same chip as a filter over the library's own rows; a video has no local counterpart. */
private fun SearchChip.localFilter(): LocalFilter? = when (this) {
    SearchChip.Songs -> LocalFilter.SONG
    SearchChip.Albums -> LocalFilter.ALBUM
    SearchChip.Artists -> LocalFilter.ARTIST
    SearchChip.Playlists -> LocalFilter.PLAYLIST
    SearchChip.All, SearchChip.Videos -> null
}

@Composable
fun AppleSearchScreen(
    navController: NavController,
    pureBlack: Boolean = false,
    viewModel: AppleSearchViewModel = hiltViewModel(),
    localSearch: LocalSearchViewModel = hiltViewModel(),
) {
    val database = LocalDatabase.current
    // the results only tint their playing row, so a play or pause leaves the list alone
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val pauseSearchHistory by rememberPreference(PauseSearchHistoryKey, defaultValue = false)
    val profilePicStr by rememberPreference(ProfilePicUriKey, defaultValue = "")
    val sheets = LocalZoomOutOverlayState.current
    val bottomInset = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()

    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var active by rememberSaveable { mutableStateOf(false) }

    /** the submitted term whose answer is on screen; null while the field is being used */
    var shown by rememberSaveable { mutableStateOf<String?>(null) }
    // a video card with no YouTube match: the card, and the media id the hand pick is stored under
    var pickVideoFor by remember { mutableStateOf<Pair<AppleSearchItem, String>?>(null) }
    var chipIndex by rememberSaveable { mutableIntStateOf(0) }

    val categories by viewModel.categories.collectAsState()
    val signedOut by viewModel.signedOut.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val results by viewModel.results.collectAsState()
    val localResult by localSearch.result.collectAsState()
    val history by remember(database) { database.searchHistory() }.collectAsState(initial = emptyList())

    // the field is the only input: the library's own rows follow it, and a new term drops the
    // answer that belonged to the old one
    LaunchedEffect(query.text) {
        viewModel.onTyped(query.text)
        localSearch.query.value = query.text
    }
    LaunchedEffect(active) {
        if (active) {
            runCatching { focusRequester.requestFocus() }
        } else {
            keyboardController?.hide()
        }
    }
    // two taps on the Search tab ask for the field (requestSearchFocus)
    val backStackEntry = LocalViewModelStoreOwner.current as? androidx.navigation.NavBackStackEntry
    val focusAsked = remember(backStackEntry) {
        backStackEntry?.savedStateHandle?.getStateFlow(SearchFocusKey, false)
    }?.collectAsState()
    LaunchedEffect(focusAsked?.value) {
        if (focusAsked?.value != true) return@LaunchedEffect
        backStackEntry?.savedStateHandle?.set(SearchFocusKey, false)
        if (active) {
            runCatching { focusRequester.requestFocus() }
            keyboardController?.show()
        } else {
            active = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { focusManager.clearFocus() }
    }

    val commit: (String) -> Unit = { raw ->
        val text = raw.trim()
        if (text.isNotEmpty()) {
            focusManager.clearFocus()
            keyboardController?.hide()
            query = TextFieldValue(text, TextRange(text.length))
            shown = text
            chipIndex = 0
            viewModel.search(text)
            if (!pauseSearchHistory) {
                coroutineScope.launch(Dispatchers.IO) {
                    database.query { insert(SearchHistory(query = text)) }
                }
            }
        }
    }

    BackHandler(enabled = shown != null || active) {
        when {
            shown != null -> shown = null
            else -> {
                active = false
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        }
    }

    val activeChip = SearchChip.entries.getOrNull(chipIndex) ?: SearchChip.All
    val localFilters = remember(activeChip) {
        when (activeChip) {
            SearchChip.All -> LocalFilter.entries.toSet()
            // a video has no library row: the chip is an empty library section, not every row
            SearchChip.Videos -> emptySet()
            else -> setOfNotNull(activeChip.localFilter())
        }
    }
    val localItems = remember(localResult, localFilters) {
        localResult.map.filterKeys { it in localFilters }.values.flatten().distinctBy { it.id }
    }
    val appleSections = remember(results.sections, activeChip) {
        results.sections.filter { activeChip.kind == null || it.kind == activeChip.kind }
    }
    // two to a row on a wide screen, as Mochy's results do
    val columns = if (LocalConfiguration.current.screenWidthDp >= 600) 2 else 1
    val topHit = remember(results.sections, results.term, activeChip) {
        if (activeChip == SearchChip.All) pickTopHit(results.sections, results.term) else null
    }
    val appleSongs = remember(results.sections) {
        results.sections.firstOrNull { it.kind == AppleSearchItem.Kind.Song }?.items.orEmpty()
    }

    val playLocal: (Song) -> Unit = { song ->
        val songs = localItems.filterIsInstance<Song>()
        val start = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playerConnection.playQueue(
            ListQueue(items = songs.map { it.toMediaItem() }, startIndex = start),
        )
    }
    val openLocal: (LocalItem) -> Unit = { item ->
        when (item) {
            is Song -> playLocal(item)
            is Album -> navController.navigate("album/${item.id}")
            is Artist -> navController.navigate("artist/${item.id}")
            is Playlist -> navController.navigate("local_playlist/${item.id}")
        }
    }
    val openApple: (AppleSearchItem) -> Unit = { item ->
        when (item.kind) {
            AppleSearchItem.Kind.Song -> {
                // the songs on screen are the queue, starting where it was tapped: Apple's app
                // plays on through the list instead of stopping after one row
                val songs = appleSongs.ifEmpty { listOf(item) }
                val start = songs.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                playerConnection.playQueue(
                    ListQueue(
                        title = results.term.ifBlank { null },
                        items = songs.map { it.toMediaItem() },
                        startIndex = start,
                    ),
                )
            }
            AppleSearchItem.Kind.Album -> navController.navigate("album/${item.id}")
            AppleSearchItem.Kind.Artist -> navController.navigate("apple_artist/${item.id}")
            AppleSearchItem.Kind.Playlist -> navController.navigate("apple_playlist/${item.id}")
            // a music video plays the way See video does: its YouTube counterpart in Mochy's own
            // player. Apple's own video stream can't be decrypted here (concord/STATE.md)
            AppleSearchItem.Kind.Video -> coroutineScope.launch {
                val mediaId = "AM_${item.id}"
                val edit = com.mochyy.mochymusic.songedit.SongEdits.of(mediaId)
                if (edit?.noVideo == true) {
                    TopToaster.show(context.getString(R.string.apple_video_none))
                    return@launch
                }
                // a video picked by hand wins, so a card YouTube can't find stays fixed
                val youtubeId = edit?.videoId ?: findOfficialMusicVideoId(item.title, item.subtitle.orEmpty())
                if (youtubeId == null) {
                    TopToaster.show(context.getString(R.string.apple_video_none))
                    // Mochy's own picker, writing the hand pick read above next time
                    pickVideoFor = item to mediaId
                    return@launch
                }
                playYouTubeVideo(playerConnection, youtubeId).onFailure {
                    TopToaster.show(context.getString(R.string.apple_video_unreachable))
                }
            }
        }
    }

    val listState = rememberLazyListState()
    val glassState = remember { GlassState() }
    val collapse = rememberCollapseProgress(listState)

    // the page has four faces — the landing, the history, the words under the typed text and an
    // answer — and each one starts at its own first row. One list state serves all of them, and the
    // head of a new face is inserted above the rows already on screen, so without this the list
    // stays anchored past it (the answer opened scrolled past Top Results, and the suggestions
    // panel under the collapsed bar).
    val face = when {
        shown != null -> "answer"
        query.text.isEmpty() && active && history.isNotEmpty() -> "history"
        query.text.isEmpty() -> "landing"
        else -> "typing"
    }
    LaunchedEffect(face, chipIndex, results.loading, suggestions.isNotEmpty()) {
        if (face == "answer" && results.loading) return@LaunchedEffect
        // the suggestion panel also lands above the library rows already on screen, so its arrival
        // is a reset of its own
        listState.scrollToItem(0)
    }

    // the chip row sits under the field only while an answer is on screen; the landing and the
    // typing states have nothing to filter
    val chipsRow: (@Composable () -> Unit)? = if (shown != null) {
        {
            SearchCategoryRow(
                categories = SearchChip.entries.map { stringResource(it.labelRes) },
                selectedIndex = chipIndex,
                onSelect = { chipIndex = it },
            )
        }
    } else {
        null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SearchColors.page(pureBlack)),
    ) {
        if (signedOut && shown == null && query.text.isEmpty()) {
            AppleSignInPrompt(
                onSignIn = { sheets.showLibraryImportSheet(AccountService.APPLE_MUSIC) },
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .glassRoot(glassState, isActive = { collapse.value > 0.01f }),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = searchTopBarHeight(
                            withTitle = true,
                            extra = if (shown != null) 46.dp else 0.dp,
                        ),
                        bottom = bottomInset + 24.dp,
                    ),
                ) {
                    val term = shown
                    if (term == null) {
                        if (query.text.isEmpty()) {
                            if (active && history.isNotEmpty()) {
                                historyRows(
                                    history = history,
                                    onOpen = commit,
                                    onFill = { text -> query = TextFieldValue(text, TextRange(text.length)) },
                                    onDelete = { entry ->
                                        coroutineScope.launch(Dispatchers.IO) {
                                            database.query { delete(entry) }
                                        }
                                    },
                                )
                            } else {
                                categoryRows(
                                    categories = categories,
                                    onOpen = { category ->
                                        // a genre tile opens that genre's own page
                                        navController.navigate(
                                            "apple_curator/${category.id}" +
                                                "?title=${Uri.encode(category.title)}"
                                        )
                                    },
                                    onRetry = { viewModel.retryCategories() },
                                )
                            }
                        } else {
                            if (suggestions.isNotEmpty()) {
                                item(key = "suggestion_header") {
                                    HomeSectionTitle(
                                        title = stringResource(R.string.suggestions),
                                        contentInset = SearchHorizontalPadding,
                                    )
                                }
                                items(
                                    items = suggestions,
                                    key = { "suggestion_$it" },
                                    contentType = { "suggestion" },
                                ) { suggestion ->
                                    SearchSimpleRow(
                                        text = suggestion,
                                        pill = true,
                                        onClick = { commit(suggestion) },
                                        trailing = {
                                            RowGlyph(R.drawable.arrow_top_left) {
                                                query = TextFieldValue(suggestion, TextRange(suggestion.length))
                                            }
                                        },
                                    )
                                }
                                item(key = "suggestion_gap") {
                                    Spacer(modifier = Modifier.height(SectionSpacing))
                                }
                            }
                            localRows(
                                rows = localItems,
                                headerRes = R.string.apple_search_in_library,
                                onOpen = openLocal,
                            )
                        }
                    } else {
                        if (topHit != null) {
                            item(key = "top_header") {
                                HomeSectionTitle(
                                    title = stringResource(R.string.search_top_results),
                                    contentInset = SearchHorizontalPadding,
                                )
                            }
                            item(key = "top_card") {
                                val active = mediaMetadata?.id == "AM_${topHit.id}"
                                SearchTopResultCard(
                                    title = topHit.title,
                                    detail = topDetail(topHit),
                                    artworkUrl = topHit.artworkUrl,
                                    circle = topHit.kind == AppleSearchItem.Kind.Artist,
                                    isActive = active,
                                    isPlaying = active && isPlaying,
                                    // only a song's own tap plays; an album, artist or playlist opens
                                    // its page, so a play button there would be a lie
                                    playable = topHit.kind == AppleSearchItem.Kind.Song,
                                    onClick = { openApple(topHit) },
                                )
                                Spacer(modifier = Modifier.height(SectionSpacing))
                            }
                        }

                        localRows(
                            rows = localItems,
                            headerRes = R.string.apple_search_in_library,
                            onOpen = openLocal,
                        )

                        appleSections.forEachIndexed { index, section ->
                            item(key = "section_${section.kind.name}") {
                                HomeSectionTitle(
                                    title = kindTitle(section.kind),
                                    contentInset = SearchHorizontalPadding,
                                )
                            }
                            if (section.kind == AppleSearchItem.Kind.Video) {
                                if (activeChip == SearchChip.All) {
                                    // in All the videos are one horizontal row: a shelf, not a list
                                    item(key = "videos_row_${section.items.first().id}") {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = SearchHorizontalPadding),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                        ) {
                                            items(
                                                items = section.items,
                                                key = { "video_${it.id}" },
                                                contentType = { "videoCard" },
                                            ) { item ->
                                                SearchVideoCard(
                                                    title = item.title,
                                                    subtitle = item.subtitle,
                                                    artworkUrl = item.artworkUrl,
                                                    onClick = { openApple(item) },
                                                    modifier = Modifier.width(VideoCardWidth),
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    // the Videos chip is a list you came to browse: two to a line
                                    items(
                                        items = section.items.chunked(2),
                                        key = { chunk -> "video_${chunk.first().id}" },
                                        contentType = { "videoCard" },
                                    ) { chunk ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = SearchHorizontalPadding, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            chunk.forEach { item ->
                                                SearchVideoCard(
                                                    title = item.title,
                                                    subtitle = item.subtitle,
                                                    artworkUrl = item.artworkUrl,
                                                    onClick = { openApple(item) },
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                            repeat(2 - chunk.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            } else if (columns == 1) {
                                items(
                                    items = section.items,
                                    key = { "apple_${section.kind.name}_${it.id}" },
                                    contentType = { "appleRow" },
                                ) { item ->
                                    AppleResultRow(
                                        item = item,
                                        isActive = mediaMetadata?.id == "AM_${item.id}",
                                        owned = item.kind == AppleSearchItem.Kind.Song && item.id in results.owned,
                                        onClick = { openApple(item) },
                                    )
                                }
                            } else {
                                // two to a row on a wide screen, as Mochy's results do
                                items(
                                    items = section.items.chunked(columns),
                                    key = { chunk -> "apple_${section.kind.name}_${chunk.first().id}" },
                                    contentType = { "appleRowPair" },
                                ) { chunk ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = SearchHorizontalPadding - 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        chunk.forEach { item ->
                                            Box(modifier = Modifier.weight(1f)) {
                                                AppleResultRow(
                                                    item = item,
                                                    isActive = mediaMetadata?.id == "AM_${item.id}",
                                                    owned = item.kind == AppleSearchItem.Kind.Song && item.id in results.owned,
                                                    onClick = { openApple(item) },
                                                    horizontalPadding = 8.dp,
                                                )
                                            }
                                        }
                                        repeat(columns - chunk.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                            // Home's own separation: air, no rule
                            item(key = "gap_${section.kind.name}") {
                                Spacer(modifier = Modifier.height(SectionSpacing))
                            }
                        }

                        when {
                            results.loading -> item(key = "loading") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(160.dp),
                                    contentAlignment = Alignment.Center,
                                ) { SearchLoadingDots() }
                            }

                            results.failed -> item(key = "failed") {
                                SearchNotice(
                                    text = stringResource(R.string.apple_search_failed),
                                    action = stringResource(R.string.retry),
                                    onAction = { viewModel.retry() },
                                )
                            }

                            results.signedOut -> item(key = "signed_out") {
                                SearchNotice(text = stringResource(R.string.apple_search_signed_out))
                            }

                            appleSections.isEmpty() && localItems.isEmpty() -> item(key = "empty") {
                                SearchNotice(text = stringResource(R.string.search_no_results))
                            }
                        }
                    }
                }
            }
        }

        SearchGlassTopBar(
            glassState = glassState,
            progressProvider = { collapse.value },
            pureBlack = pureBlack,
            title = stringResource(R.string.search),
            trailing = {
                SearchAvatar(
                    imageUrl = profilePicStr.ifBlank { null },
                    onClick = { navController.navigateToTab("settings") },
                )
            },
            below = chipsRow,
        ) {
            SearchField(
                value = query,
                onValueChange = { value ->
                    query = value
                    active = true
                    // editing the term retires the answer it belonged to, and the chip with it
                    if (shown != null) {
                        shown = null
                        chipIndex = 0
                    }
                },
                onSearch = commit,
                placeholder = stringResource(R.string.search_placeholder_tracks_albums),
                focused = active,
                leading = if (active) {
                    {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                            tint = SearchColors.Secondary,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = RemoteFocus,
                                ) {
                                    active = false
                                    shown = null
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                },
                        )
                    }
                } else {
                    null
                },
                trailing = if (query.text.isNotEmpty()) {
                    {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = null,
                            tint = SearchColors.Secondary,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = RemoteFocus,
                                ) {
                                    query = TextFieldValue("")
                                    shown = null
                                },
                        )
                    }
                } else {
                    null
                },
                focusRequester = focusRequester,
                onFocusChanged = { focused -> if (focused) active = true },
                readOnlyClick = if (!active) {
                    { active = true }
                } else {
                    null
                },
            )
        }

        pickVideoFor?.let { (item, mediaId) ->
            com.mochyy.mochymusic.ui.menu.songedit.VideoPickerSheet(
                songId = mediaId,
                title = item.title,
                artist = item.subtitle.orEmpty(),
                onDismiss = { pickVideoFor = null },
            )
        }
    }
}

/** The term's own answer: an artist (or album) called exactly that, else Apple's first song. */
private fun pickTopHit(sections: List<AppleSearchSection>, term: String): AppleSearchItem? {
    val typed = term.trim().lowercase()
    val all = sections.flatMap { it.items }
    if (all.isEmpty()) return null
    all.firstOrNull {
        it.kind == AppleSearchItem.Kind.Artist && it.title.lowercase() == typed
    }?.let { return it }
    all.firstOrNull {
        it.kind == AppleSearchItem.Kind.Album && it.title.lowercase() == typed
    }?.let { return it }
    return sections.firstOrNull { it.kind == AppleSearchItem.Kind.Song }?.items?.firstOrNull()
        ?: all.first()
}

/** A row's second line, Mochy's wording: the credit, then the length for a song. */
@Composable
private fun rowSubtitle(item: AppleSearchItem): String = when (item.kind) {
    AppleSearchItem.Kind.Song -> listOfNotNull(
        item.subtitle,
        item.durationMs?.let { makeTimeString(it).takeIf { text -> text.isNotEmpty() } },
    ).joinToString("  •  ")

    AppleSearchItem.Kind.Album -> listOfNotNull(
        stringResource(R.string.item_kind_album),
        item.subtitle,
    ).joinToString("  •  ")

    AppleSearchItem.Kind.Artist -> stringResource(R.string.item_kind_artist)

    AppleSearchItem.Kind.Playlist -> listOfNotNull(
        stringResource(R.string.item_kind_playlist),
        item.subtitle,
    ).joinToString("  •  ")

    AppleSearchItem.Kind.Video -> listOfNotNull(
        stringResource(R.string.item_kind_video),
        item.subtitle,
        item.durationMs?.let { makeTimeString(it).takeIf { text -> text.isNotEmpty() } },
    ).joinToString("  •  ")
}

/** The top card's second line: the kind, then the credit, then the length — Mochy's wording. */
@Composable
private fun topDetail(item: AppleSearchItem): String {
    val kind = when (item.kind) {
        AppleSearchItem.Kind.Song -> stringResource(R.string.item_kind_song)
        AppleSearchItem.Kind.Album -> stringResource(R.string.item_kind_album)
        AppleSearchItem.Kind.Artist -> stringResource(R.string.item_kind_artist)
        AppleSearchItem.Kind.Playlist -> stringResource(R.string.item_kind_playlist)
        AppleSearchItem.Kind.Video -> stringResource(R.string.item_kind_video)
    }
    return listOfNotNull(
        kind,
        item.subtitle?.let { stringResource(R.string.search_by_artist, it) },
        item.durationMs
            ?.takeIf { item.kind == AppleSearchItem.Kind.Song || item.kind == AppleSearchItem.Kind.Video }
            ?.let { makeTimeString(it).takeIf { text -> text.isNotEmpty() } },
    ).joinToString("  •  ")
}

/** One catalogue row and its rule: the block Mochy's results draw, with Apple's data. */
@Composable
private fun AppleResultRow(
    item: AppleSearchItem,
    isActive: Boolean,
    owned: Boolean,
    onClick: () -> Unit,
    horizontalPadding: Dp = SearchHorizontalPadding,
) {
    SearchResultRow(
        title = item.title,
        subtitle = rowSubtitle(item),
        artworkUrl = item.artworkUrl,
        onClick = onClick,
        isActive = isActive,
        circle = item.kind == AppleSearchItem.Kind.Artist,
        inLibrary = owned,
        horizontalPadding = horizontalPadding,
    )
}

@Composable
private fun kindTitle(kind: AppleSearchItem.Kind): String = stringResource(
    when (kind) {
        AppleSearchItem.Kind.Song -> R.string.apple_search_songs
        AppleSearchItem.Kind.Album -> R.string.apple_search_albums
        AppleSearchItem.Kind.Artist -> R.string.apple_search_artists
        AppleSearchItem.Kind.Playlist -> R.string.apple_search_playlists
        AppleSearchItem.Kind.Video -> R.string.apple_search_videos
    },
)

/** A catalogue row, as a playable item: the `AM_` id is how the Apple module finds the recording. */
private fun AppleSearchItem.toMediaItem(): androidx.media3.common.MediaItem {
    val song = Song(
        song = SongEntity(
            id = "AM_$id",
            title = title,
            duration = ((durationMs ?: 0L) / 1000L).toInt(),
            thumbnailUrl = AppleCovers.sized(artworkUrl),
            source = SongSource.Apple,
        ),
        artists = listOf(
            ArtistEntity(
                id = ArtistEntity.generateArtistId(),
                name = subtitle?.takeIf { it.isNotBlank() } ?: "Unknown artist",
            ),
        ),
    )
    return song.toMediaItem()
}

@Composable
private fun SearchNotice(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SearchHorizontalPadding, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            color = SearchColors.Secondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        if (action != null && onAction != null) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** The history, exactly as the field's own page draws it: a clock, the term, delete and fill-in. */
private fun LazyListScope.historyRows(
    history: List<SearchHistory>,
    onOpen: (String) -> Unit,
    onFill: (String) -> Unit,
    onDelete: (SearchHistory) -> Unit,
) {
    item(key = "history_header") {
        HomeSectionTitle(
            title = stringResource(R.string.search_history),
            contentInset = SearchHorizontalPadding,
        )
    }
    items(
        items = history,
        key = { "history_${it.query}" },
        contentType = { "history" },
    ) { entry ->
        SearchSimpleRow(
            text = entry.query,
            leadingIcon = R.drawable.history,
            onClick = { onOpen(entry.query) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowGlyph(R.drawable.close) { onDelete(entry) }
                    Spacer(modifier = Modifier.width(4.dp))
                    RowGlyph(R.drawable.arrow_top_left) { onFill(entry.query) }
                }
            },
        )
    }
}

/** The library's own hits, drawn with the same row as Apple's so one list reads as one list. */
private fun LazyListScope.localRows(
    rows: List<LocalItem>,
    /** a string resource: it is read inside the item, where a composable may run */
    headerRes: Int,
    onOpen: (LocalItem) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "local_header") {
        HomeSectionTitle(
            title = stringResource(headerRes),
            contentInset = SearchHorizontalPadding,
        )
    }
    items(
        items = rows,
        key = { "local_${it.id}" },
        contentType = { "localRow" },
    ) { item ->
        SearchResultRow(
            title = item.title,
            subtitle = localSubtitle(item),
            artworkUrl = item.thumbnailUrl,
            onClick = { onOpen(item) },
            circle = item is Artist,
        )
    }
    item(key = "local_gap") { Spacer(modifier = Modifier.height(SectionSpacing)) }
}

@Composable
private fun localSubtitle(item: LocalItem): String = when (item) {
    is Song -> item.artists.joinToString { it.name }
        .ifBlank { stringResource(R.string.item_kind_song) }

    is Album -> listOfNotNull(
        stringResource(R.string.item_kind_album),
        item.artists.joinToString { it.name }.takeIf { it.isNotBlank() },
    ).joinToString("  •  ")

    is Artist -> stringResource(R.string.item_kind_artist)
    is Playlist -> stringResource(R.string.item_kind_playlist)
}

/** "Browse Categories": Apple's own genre tiles, two to a row like the tab's category grid. */
private fun LazyListScope.categoryRows(
    categories: List<AppleSearchCategory>?,
    onOpen: (AppleSearchCategory) -> Unit,
    onRetry: () -> Unit,
) {
    if (categories == null) {
        item(key = "categories_loading") {
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center,
            ) { SearchLoadingDots() }
        }
        return
    }
    if (categories.isEmpty()) {
        item(key = "categories_empty") {
            SearchNotice(
                text = stringResource(R.string.apple_shelf_empty),
                action = stringResource(R.string.retry),
                onAction = onRetry,
            )
        }
        return
    }
    item(key = "categories_header") {
        HomeSectionTitle(
            title = stringResource(R.string.apple_search_browse_categories),
            contentInset = SearchHorizontalPadding,
        )
    }
    items(
        items = categories.chunked(2),
        key = { row -> "category_${row.first().id}" },
        contentType = { "categoryRow" },
    ) { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SearchHorizontalPadding, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            row.forEach { category ->
                MoodTile(
                    title = category.title,
                    stripeColor = category.tint,
                    covers = listOfNotNull(category.artworkUrl?.let { AppleCovers.sized(it, 256) }),
                    onClick = { onOpen(category) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(2 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
}
