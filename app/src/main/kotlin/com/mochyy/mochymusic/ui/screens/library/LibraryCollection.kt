// LibraryCollection.kt
//
// The page behind All Songs, Albums, Artists and Playlists: the title with how much is there and how
// long it runs, Play / shuffle / search, a list or grid switch, and the A-Z rail down the side. Everything the list needs (sorting thousands of
// titles into letters, Japanese and all) is worked out off the main thread.

package com.mochyy.mochymusic.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.ui.component.BoneColor
import com.mochyy.mochymusic.ui.component.HomeCardHighlight
import com.mochyy.mochymusic.ui.component.containerTransformSource
import com.mochyy.mochymusic.ui.component.detail.PlaylistDetailScaffold
import com.mochyy.mochymusic.ui.component.detail.PlaylistHeader
import com.mochyy.mochymusic.ui.component.detail.PlaylistTrackBones
import com.mochyy.mochymusic.ui.component.detail.PlaylistTrackRow
import com.mochyy.mochymusic.ui.component.detail.TrackShape
import com.mochyy.mochymusic.ui.component.detail.playlistCompactHeaderHeight
import com.mochyy.mochymusic.ui.component.rememberRestoredListState
import com.mochyy.mochymusic.ui.screens.playlist.GeneratedGroupHeader
import com.mochyy.mochymusic.ui.screens.playlist.GeneratedNoResults
import com.mochyy.mochymusic.ui.screens.playlist.GeneratedSearchCapsule
import com.mochyy.mochymusic.ui.utils.backToMain
import com.mochyy.mochymusic.ui.utils.resize
import com.mochyy.mochymusic.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/** one entry on a library page, as both the list and the grid draw it */
@Immutable
data class CollectionEntry(
    val id: String,
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
    // songs only: marks the row while it plays and when it's on the phone
    val songId: String? = null,
    // albums and playlists open growing out of their cover
    val sharedElementKey: String? = null,
    // playlists only: more than one person can edit it, marked on the card and the row
    val collaborative: Boolean = false,
)

/** A folder on a collection page: its own header, expanding in place to show what Apple put in it. */
@Immutable
class CollectionFolder<T>(
    val id: String,
    val name: String,
    val items: List<T>,
)

/**
 * How a collection page is ordered. [comparator] sorts the list ascending; [descending] turns it
 * round; [sectioned] is true only when A-Z would be telling the truth, so a date or a count sorts
 * without letter headings over it. [control] is the "date added ⌄" line under the buttons, the same
 * one Liked songs wears.
 */
class CollectionSorting<T>(
    val descending: Boolean,
    val sectioned: Boolean,
    val comparator: Comparator<T>?,
    val control: @Composable () -> Unit,
)

/** Names compare the way people read them: "Ä" files with "A", not after "Z". */
internal fun libraryCollator(): Collator =
    Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }

/** A comparator on a text key, collated. Built once per sort and used off the main thread. */
internal fun <T> byCollated(selector: (T) -> String): Comparator<T> {
    val collator = libraryCollator()
    return Comparator { a, b -> collator.compare(selector(a), selector(b)) }
}

private const val GridColumns = 3
private val TileShape = RoundedCornerShape(14.dp)
private val RailLandingGap = 64.dp

private sealed interface CollectionRow<out T> {
    val key: String

    class Letter(val section: IndexSection) : CollectionRow<Nothing> {
        override val key = "h_${section.key}"
    }

    class Single<T>(val item: T, val entry: CollectionEntry) : CollectionRow<T> {
        override val key = "i_${entry.id}"
    }

    class Tiles<T>(override val key: String, val tiles: List<Pair<T, CollectionEntry>>) : CollectionRow<T>

    class Folder(
        override val key: String,
        val id: String,
        val name: String,
        val count: Int,
        val expanded: Boolean,
    ) : CollectionRow<Nothing>
}

// what the page shows, worked out off the main thread: rows in index order, the letters on the rail and where each starts
private class CollectionLayout<T>(
    val rows: List<CollectionRow<T>>,
    val ordered: List<T>,
    val sections: List<IndexSection>,
    val firstRowOf: Map<String, Int>,
    val count: Int,
    val totalSeconds: Int,
    // tints the page and sits in the collapsed pill, like the other pages without a cover of their own
    val coverUrl: String?,
)

private fun <T> layoutOf(
    items: List<T>,
    toEntry: (T) -> CollectionEntry,
    query: String,
    grid: Boolean,
    secondsOf: ((T) -> Int)?,
    sort: CollectionSorting<T>?,
    folders: List<CollectionFolder<T>> = emptyList(),
    expandedFolders: Set<String> = emptySet(),
): CollectionLayout<T> {
    val pairs = items.map { it to toEntry(it) }
    val q = query.trim()
    val visible = if (q.isEmpty()) {
        pairs
    } else {
        pairs.filter { (_, e) -> e.title.contains(q, ignoreCase = true) || e.subtitle?.contains(q, ignoreCase = true) == true }
    }
    // folders are the page's own structure: a search has to search everything, so it goes flat
    if (folders.isNotEmpty() && q.isEmpty()) {
        return foldLayout(items, visible, toEntry, grid, sort, folders, expandedFolders)
    }
    // a null comparator leaves the list in the order it arrived in (newest first, from the
    // database); descending then has nothing to turn round, which is the order Apple gives too
    val itemComparator = sort?.comparator
    val orderedPairs = when {
        sort == null || sort.sectioned || itemComparator == null -> visible
        else -> {
            val byItem = Comparator<Pair<T, CollectionEntry>> { a, b -> itemComparator.compare(a.first, b.first) }
            val sorted = visible.sortedWith(byItem)
            if (sort.descending) sorted.asReversed() else sorted
        }
    }
    val sections = if (sort?.sectioned == true) {
        indexSections(orderedPairs) { it.second.title }
    } else {
        emptyList()
    }
    val rows = ArrayList<CollectionRow<T>>(orderedPairs.size + sections.size)
    val firstRowOf = HashMap<String, Int>()
    if (sections.isEmpty()) {
        // no letters: one row an item, or tiles three to a row
        if (grid) {
            orderedPairs.chunked(GridColumns).forEachIndexed { i, chunk -> rows += CollectionRow.Tiles("t_$i", chunk) }
        } else {
            orderedPairs.forEach { (item, entry) -> rows += CollectionRow.Single(item, entry) }
        }
    } else {
        sections.forEach { (section, entries) ->
            firstRowOf[section.key] = rows.size
            rows += CollectionRow.Letter(section)
            if (grid) {
                entries.chunked(GridColumns).forEachIndexed { i, chunk -> rows += CollectionRow.Tiles("t_${section.key}_$i", chunk) }
            } else {
                entries.forEach { (item, entry) -> rows += CollectionRow.Single(item, entry) }
            }
        }
    }
    return CollectionLayout(
        rows = rows,
        ordered = if (sections.isEmpty()) orderedPairs.map { it.first } else sections.flatMap { (_, entries) -> entries.map { it.first } },
        sections = sections.map { it.first },
        firstRowOf = firstRowOf,
        count = items.size,
        totalSeconds = secondsOf?.let { seconds -> items.sumOf(seconds) } ?: 0,
        coverUrl = pairs.firstNotNullOfOrNull { it.second.thumbnailUrl?.takeIf(String::isNotEmpty) },
    )
}

/**
 * The page with Apple's folders: a header per folder, and its playlists only while it is open.
 * Sorting still applies, inside each folder and to the playlists that sit outside one. No letter
 * rail — the folders are the structure the page is drawn by.
 */
private fun <T> foldLayout(
    items: List<T>,
    visible: List<Pair<T, CollectionEntry>>,
    toEntry: (T) -> CollectionEntry,
    grid: Boolean,
    sort: CollectionSorting<T>?,
    folders: List<CollectionFolder<T>>,
    expanded: Set<String>,
): CollectionLayout<T> {
    val rows = ArrayList<CollectionRow<T>>(visible.size + folders.size * 2)
    val ordered = ArrayList<T>(visible.size)
    val folderOf = HashMap<String, Int>()
    folders.forEachIndexed { index, folder ->
        folder.items.forEach { folderOf[toEntry(it).id] = index }
    }

    fun sorted(pairs: List<Pair<T, CollectionEntry>>): List<Pair<T, CollectionEntry>> {
        val comparator = sort?.comparator ?: return pairs
        if (sort.sectioned) return pairs
        val byItem = Comparator<Pair<T, CollectionEntry>> { a, b -> comparator.compare(a.first, b.first) }
        val ascending = pairs.sortedWith(byItem)
        return if (sort.descending) ascending.asReversed() else ascending
    }

    fun addRows(pairs: List<Pair<T, CollectionEntry>>) {
        if (grid) {
            pairs.chunked(GridColumns).forEachIndexed { i, chunk ->
                rows += CollectionRow.Tiles("t_f${rows.size}_$i", chunk)
            }
        } else {
            pairs.forEach { (item, entry) -> rows += CollectionRow.Single(item, entry) }
        }
        ordered += pairs.map { it.first }
    }

    folders.forEachIndexed { index, folder ->
        val pairs = sorted(visible.filter { folderOf[it.second.id] == index })
        rows += CollectionRow.Folder(
            key = "f_${folder.id}",
            id = folder.id,
            name = folder.name,
            count = pairs.size,
            expanded = folder.id in expanded,
        )
        if (folder.id in expanded) addRows(pairs)
    }
    addRows(sorted(visible.filter { folderOf[it.second.id] == null }))

    return CollectionLayout(
        rows = rows,
        ordered = ordered,
        sections = emptyList(),
        firstRowOf = emptyMap(),
        count = items.size,
        totalSeconds = 0,
        coverUrl = items.firstOrNull { !toEntry(it).thumbnailUrl.isNullOrEmpty() }?.let { toEntry(it).thumbnailUrl },
    )
}

/**
 * the page itself. [onOpen] and [onPlay] get the items in the order shown, so a tap or Play queues
 * what's on screen. [pageKey] remembers this page's list or grid choice on its own.
 */
@Composable
fun <T> LibraryCollectionPage(
    title: String,
    pageKey: String,
    items: List<T>,
    toEntry: (T) -> CollectionEntry,
    countText: @Composable (Int) -> String,
    searchPlaceholder: String,
    onOpen: (item: T, ordered: List<T>) -> Unit,
    onMenu: (T) -> Unit,
    onPlay: (ordered: List<T>, shuffle: Boolean) -> Unit,
    navController: NavController,
    sort: CollectionSorting<T>,
    /** Apple's folders: when present, the page draws each as a header that opens in place. */
    folders: List<CollectionFolder<T>> = emptyList(),
    roundCovers: Boolean = false,
    secondsOf: ((T) -> Int)? = null,
    rowWrapper: @Composable (item: T, row: @Composable () -> Unit) -> Unit = { _, row -> row() },
) {
    val playerConnection = LocalPlayerConnection.current
    val idle = remember { MutableStateFlow<MediaMetadata?>(null) }
    // states, read inside the rows and the header only, so a play or pause never recomposes the page
    val nowPlaying = (playerConnection?.mediaMetadata ?: idle).collectAsState()
    val isPlaying = (playerConnection?.isEffectivelyPlaying ?: remember { MutableStateFlow(false) }).collectAsState()
    val queueTitle = (playerConnection?.queueTitle ?: remember { MutableStateFlow<String?>(null) }).collectAsState()

    var grid by rememberPreference(booleanPreferencesKey("libraryGrid_$pageKey"), defaultValue = false)
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by remember { mutableStateOf(TextFieldValue()) }
    val searchFocus = remember { FocusRequester() }
    var expandedFolders by remember { mutableStateOf(emptySet<String>()) }
    val layout by produceState<CollectionLayout<T>?>(null, items, query.text, grid, sort, folders, expandedFolders) {
        value = withContext(Dispatchers.Default) {
            layoutOf(items, toEntry, query.text, grid, secondsOf, sort, folders, expandedFolders)
        }
    }
    // the bones stand in until the sort is here, and a list measured with only those in it loses
    // the position it was left at
    val listState = rememberRestoredListState(hasContent = layout != null)
    val scope = rememberCoroutineScope()
    // a letter picked on the rail lands just under the collapsed bar instead of hidden behind it
    val barPx = with(LocalDensity.current) {
        (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + RailLandingGap).toPx()
    }

    if (searching) {
        BackHandler {
            searching = false
            query = TextFieldValue()
        }
    }

    val shown = layout
    val stats = shown?.let {
        val count = countText(it.count)
        if (it.totalSeconds > 0) {
            stringResource(R.string.lib_stats_in_library_time, count, listeningTime(it.totalSeconds))
        } else {
            stringResource(R.string.lib_stats_in_library, count)
        }
    }.orEmpty()

    // a pull here is the library's refresh: this page *is* a slice of the library
    val (libraryRefreshing, libraryRefresh) = rememberLibraryRefresh()

    PlaylistDetailScaffold(
        sharedElementKey = null,
        coverUrl = shown?.coverUrl,
        isRefreshing = libraryRefreshing,
        onRefresh = libraryRefresh,
        lazyListState = listState,
        onBackClick = { navController.navigateUp() },
        onBackLongClick = { navController.backToMain() },
        contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)
            .union(WindowInsets.ime).asPaddingValues(),
        showCover = false,
        roundCapsule = true,
        headerHeight = playlistCompactHeaderHeight(),
        overlay = {
            if (shown != null && shown.sections.size > 1 && query.text.isBlank()) {
                LibraryIndexRail(
                    sections = shown.sections,
                    onSectionSelected = { key ->
                        // +1 for the header above the rows
                        shown.firstRowOf[key]?.let { row ->
                            scope.launch {
                                listState.scrollToItem(row + 1)
                                listState.scrollBy(-barPx)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
    ) {
        item(key = "collection_header", contentType = "header") {
            val thisQueue = queueTitle.value == title
            PlaylistHeader(
                title = title,
                creators = emptyList(),
                stats = stats,
                isPlaying = thisQueue && isPlaying.value,
                onPlayClick = {
                    if (thisQueue) playerConnection?.togglePlayPause() else shown?.let { onPlay(it.ordered, false) }
                },
                onShuffleClick = { shown?.let { onPlay(it.ordered, true) } },
                onSearchClick = {
                    searching = true
                    scope.launch {
                        listState.animateScrollToItem(0)
                        runCatching { searchFocus.requestFocus() }
                    }
                },
                actionsEnd = { ViewSwitch(grid = grid, onChange = { grid = it }) },
                sortControl = sort.control,
                below = if (searching) {
                    {
                        GeneratedSearchCapsule(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = searchPlaceholder,
                            focusRequester = searchFocus,
                        )
                    }
                } else {
                    null
                },
            )
        }

        if (shown == null) {
            item(key = "loading", contentType = "loading") { PlaylistTrackBones() }
            return@PlaylistDetailScaffold
        }
        if (shown.rows.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                if (query.text.isNotBlank()) GeneratedNoResults(query.text) else LibraryEmptyState()
            }
        }

        items(
            items = shown.rows,
            key = { it.key },
            contentType = { row ->
                when (row) {
                    is CollectionRow.Letter -> "letter"
                    is CollectionRow.Single -> "row"
                    is CollectionRow.Tiles -> "tiles"
                    is CollectionRow.Folder -> "folder"
                }
            },
        ) { row ->
            when (row) {
                is CollectionRow.Letter -> GeneratedGroupHeader(row.section.label)
                is CollectionRow.Folder -> CollectionFolderRow(
                    name = row.name,
                    count = row.count,
                    expanded = row.expanded,
                    onClick = {
                        expandedFolders = if (row.id in expandedFolders) expandedFolders - row.id else expandedFolders + row.id
                    },
                )
                is CollectionRow.Single -> rowWrapper(row.item) {
                    val entry = row.entry
                    val active = entry.songId != null && entry.songId == nowPlaying.value?.id
                    PlaylistTrackRow(
                        thumbnailUrl = entry.thumbnailUrl,
                        title = entry.title,
                        subtitle = entry.subtitle.orEmpty(),
                        isActive = active,
                        isPlaying = active && isPlaying.value,
                        onClick = { onOpen(row.item, shown.ordered) },
                        onLongClick = { onMenu(row.item) },
                        onMenuClick = { onMenu(row.item) },
                        coverShape = if (roundCovers) CircleShape else TrackShape,
                        coverModifier = entry.sharedElementKey?.let { Modifier.containerTransformSource(it, cornerRadius = 9.dp) } ?: Modifier,
                        songId = entry.songId,
                        trailing = if (entry.collaborative) {
                            { CollaborativeMark() }
                        } else {
                            null
                        },
                    )
                }
                is CollectionRow.Tiles -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, bottom = 16.dp),
                ) {
                    row.tiles.forEach { (item, entry) ->
                        CollectionTile(
                            entry = entry,
                            round = roundCovers,
                            onClick = { onOpen(item, shown.ordered) },
                            onLongClick = { onMenu(item) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // the last row's tiles stay the size of a full row's
                    repeat(GridColumns - row.tiles.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        item(key = "bottom_spacer", contentType = "spacer") { Spacer(Modifier.height(60.dp)) }
    }
}

/** "152h 42m" or "42m" */
@Composable
internal fun listeningTime(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) {
        stringResource(R.string.lib_time_hours_minutes, hours, minutes)
    } else {
        stringResource(R.string.lib_time_minutes, minutes)
    }
}

// the grid and list switch at the end of the buttons, the one showing a step brighter
@Composable
private fun ViewSwitch(grid: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).padding(3.dp),
    ) {
        ViewSwitchButton(R.drawable.grid_view, stringResource(R.string.lib_view_grid), selected = grid) { onChange(true) }
        ViewSwitchButton(R.drawable.list, stringResource(R.string.lib_view_list), selected = !grid) { onChange(false) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ViewSwitchButton(icon: Int, description: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (selected) 0.22f else 0f))
            .combinedClickable(onClick = onClick),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = Color.White.copy(alpha = if (selected) 1f else 0.55f),
            modifier = Modifier.size(15.dp),
        )
    }
}

// a cover with its name under it, three to a row in the grid
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CollectionTile(
    entry: CollectionEntry,
    round: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = if (round) CircleShape else TileShape
    Column(
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
        modifier = modifier.combinedClickable(
            interactionSource = null,
            indication = HomeCardHighlight,
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(entry.sharedElementKey?.let { Modifier.containerTransformSource(it, cornerRadius = 14.dp) } ?: Modifier)
                .clip(shape)
                .background(BoneColor),
        ) {
            AsyncImage(
                model = entry.thumbnailUrl?.resize(300, 300),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (entry.collaborative) {
                CollaborativeMark(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.title,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = Color.White.copy(alpha = 0.94f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        entry.subtitle?.let {
            Text(
                text = it,
                style = TextStyle(fontSize = 11.sp),
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The small mark a collaborative playlist wears: two heads in a dark chip, in the corner of its
 * cover and before the menu on its row. Copper-free so it reads on any artwork.
 */
@Composable
internal fun CollaborativeMark(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
    ) {
        Icon(
            painter = painterResource(R.drawable.group_filled),
            contentDescription = stringResource(R.string.playlist_collaborative),
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** One Apple folder on the page: a header that opens and closes in place, no separate screen. */
@Composable
private fun CollectionFolderRow(
    name: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_folder_small),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.78f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = name,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = TextStyle(fontSize = 12.sp),
            color = Color.White.copy(alpha = 0.5f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            painter = painterResource(R.drawable.expand_more),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
        )
    }
}
