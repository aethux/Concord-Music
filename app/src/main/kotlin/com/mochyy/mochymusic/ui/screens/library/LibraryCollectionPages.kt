// LibraryCollectionPages.kt
//
// The library's albums, artists and playlists on the shared collection page. Play and shuffle there
// reach every song across all of them, gathered off the main thread so a big library never stalls.

package com.mochyy.mochymusic.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalDatabase
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.AlbumSortType
import com.mochyy.mochymusic.constants.ArtistSortType
import com.mochyy.mochymusic.constants.LibraryAlbumsSortDescendingKey
import com.mochyy.mochymusic.constants.LibraryAlbumsSortTypeKey
import com.mochyy.mochymusic.constants.LibraryArtistsSortDescendingKey
import com.mochyy.mochymusic.constants.LibraryArtistsSortTypeKey
import com.mochyy.mochymusic.constants.LibraryPlaylistsSortDescendingKey
import com.mochyy.mochymusic.constants.LibraryPlaylistsSortTypeKey
import com.mochyy.mochymusic.constants.PlaylistSortType
import com.mochyy.mochymusic.db.entities.Album
import com.mochyy.mochymusic.db.entities.Artist
import com.mochyy.mochymusic.db.entities.Playlist
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.playback.PlayerConnection
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.ui.component.DefaultDialog
import com.mochyy.mochymusic.ui.component.ListDialog
import com.mochyy.mochymusic.ui.component.TextFieldDialog
import com.mochyy.mochymusic.ui.player.menu.MenuRow
import com.mochyy.mochymusic.applesync.ApplePlaylistWrites
import com.mochyy.mochymusic.applesync.PlaylistFolders
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.TextFieldValue
import com.mochyy.mochymusic.ui.component.LocalMenuState
import com.mochyy.mochymusic.ui.component.detail.PlaylistSortControl
import com.mochyy.mochymusic.ui.menu.AlbumMenu
import com.mochyy.mochymusic.ui.menu.ArtistMenu
import com.mochyy.mochymusic.ui.menu.PlaylistMenu
import com.mochyy.mochymusic.utils.rememberEnumPreference
import com.mochyy.mochymusic.utils.rememberPreference
import com.mochyy.mochymusic.viewmodels.LibraryHomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// gathers the songs off the main thread and plays them as one queue; shuffled, it's every song mixed, not the lists in a new order
private fun CoroutineScope.playGathered(player: PlayerConnection?, title: String, shuffle: Boolean, gather: suspend () -> List<Song>) {
    if (player == null) return
    launch {
        val items = withContext(Dispatchers.IO) {
            val songs = gather().distinctBy { it.id }
            (if (shuffle) songs.shuffled() else songs).map { it.toMediaItem() }
        }
        if (items.isNotEmpty()) player.playQueue(ListQueue(title = title, items = items))
    }
}

@Composable
internal fun LibraryAlbumsPage(navController: NavController) {
    val viewModel: LibraryHomeViewModel = hiltViewModel()
    val albums by viewModel.albums.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val database = LocalDatabase.current
    val player = LocalPlayerConnection.current
    val menuState = LocalMenuState.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.albums)
    val (sortType, onSortTypeChange) = rememberEnumPreference(LibraryAlbumsSortTypeKey, AlbumSortType.CREATE_DATE)
    val (sortDescending, onSortDescendingChange) = rememberPreference(LibraryAlbumsSortDescendingKey, true)
    val sort = remember(sortType, sortDescending) {
        CollectionSorting(
            descending = sortDescending,
            sectioned = sortType == AlbumSortType.NAME && !sortDescending,
            comparator = albumComparator(sortType),
            control = {
                PlaylistSortControl(
                    current = sortType,
                    descending = sortDescending,
                    options = AlbumSortOrder,
                    label = { albumSortLabel(it) },
                    onSelect = { type ->
                        onSortTypeChange(type)
                        onSortDescendingChange(type.readsNewestFirst())
                    },
                    onToggleDirection = { onSortDescendingChange(!sortDescending) },
                )
            },
        )
    }

    LibraryCollectionPage(
        title = title,
        pageKey = "albums",
        items = albums,
        toEntry = { album ->
            CollectionEntry(
                id = album.id,
                title = album.title,
                subtitle = album.artists.joinToString { it.name }.ifBlank { null },
                thumbnailUrl = album.album.thumbnailUrl,
                sharedElementKey = "album-${album.id}",
            )
        },
        countText = { pluralStringResource(R.plurals.n_album, it, it) },
        searchPlaceholder = stringResource(R.string.library_albums_search),
        onOpen = { album, _ -> navController.navigate("album/${album.id}") },
        onMenu = { album ->
            menuState.show { AlbumMenu(originalAlbum = album, navController = navController, onDismiss = menuState::dismiss) }
        },
        onPlay = { ordered, shuffle ->
            // an album only knows its tracks once its page has been opened; before that, the library's songs from it stand in
            val library = songs
            scope.playGathered(player, title, shuffle) {
                val byAlbum = library.groupBy { it.song.albumId }
                ordered.flatMap { album -> database.albumSongs(album.id).first().ifEmpty { byAlbum[album.id].orEmpty() } }
            }
        },
        navController = navController,
        sort = sort,
    )
}

@Composable
internal fun LibraryArtistsPage(navController: NavController) {
    val viewModel: LibraryHomeViewModel = hiltViewModel()
    val artists by viewModel.artists.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val resources = LocalContext.current.resources
    val player = LocalPlayerConnection.current
    val menuState = LocalMenuState.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.artists)
    val (sortType, onSortTypeChange) = rememberEnumPreference(LibraryArtistsSortTypeKey, ArtistSortType.NAME)
    // Cider's list is `sort=name`; A→Z is the direction that matches it, not Z→A
    val (sortDescending, onSortDescendingChange) = rememberPreference(LibraryArtistsSortDescendingKey, false)
    val sort = remember(sortType, sortDescending) {
        CollectionSorting(
            descending = sortDescending,
            sectioned = sortType == ArtistSortType.NAME && !sortDescending,
            comparator = artistComparator(sortType),
            control = {
                PlaylistSortControl(
                    current = sortType,
                    descending = sortDescending,
                    options = ArtistSortOrder,
                    label = { artistSortLabel(it) },
                    onSelect = { type ->
                        onSortTypeChange(type)
                        onSortDescendingChange(type.readsNewestFirst())
                    },
                    onToggleDirection = { onSortDescendingChange(!sortDescending) },
                )
            },
        )
    }

    LibraryCollectionPage(
        title = title,
        pageKey = "artists",
        items = artists,
        toEntry = { artist ->
            CollectionEntry(
                id = artist.id,
                title = artist.artist.name,
                subtitle = resources.getQuantityString(R.plurals.lib_song_count, artist.songCount, artist.songCount),
                thumbnailUrl = artist.artist.thumbnailUrl,
            )
        },
        countText = { pluralStringResource(R.plurals.n_artist, it, it) },
        searchPlaceholder = stringResource(R.string.lib_artists_search),
        onOpen = { artist, _ -> navController.navigate("artist/${artist.id}") },
        onMenu = { artist ->
            menuState.show { ArtistMenu(originalArtist = artist, coroutineScope = scope, onDismiss = menuState::dismiss) }
        },
        onPlay = { ordered, shuffle ->
            // the library's songs by each artist, in the order the artists are shown
            val library = songs
            scope.playGathered(player, title, shuffle) {
                val byArtist = library.flatMap { song -> song.artists.map { it.id to song } }.groupBy({ it.first }, { it.second })
                ordered.flatMap { byArtist[it.id].orEmpty() }
            }
        },
        navController = navController,
        roundCovers = true,
        sort = sort,
    )
}

@Composable
internal fun LibraryPlaylistsPage(navController: NavController) {
    val viewModel: LibraryHomeViewModel = hiltViewModel()
    val playlists by viewModel.playlists.collectAsState()
    val context = LocalContext.current
    // which playlists more than one person can edit: the sidecar knows, the music database does not
    val collaborative by produceState<Set<String>>(emptySet(), playlists) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                dagger.hilt.android.EntryPointAccessors
                    .fromApplication(context.applicationContext, com.mochyy.mochymusic.applesync.di.ApplePlaylistEntryPoint::class.java)
                    .dao()
                    .playlistMetas()
                    .filter { it.collaborative }
                    .mapTo(HashSet()) { it.playlistId }
            }.getOrDefault(emptySet())
        }
    }
    // Apple's folders, from the tree the playlist import keeps: a header per folder, opened in place
    // observed, so a folder made, renamed, emptied or deleted from the folder's own menu is on the
    // page before the person lifts their finger off it
    val folderTree by produceState<com.mochyy.mochymusic.applesync.PlaylistFolders.Tree?>(null, playlists) {
        val dao = runCatching {
            dagger.hilt.android.EntryPointAccessors
                .fromApplication(context.applicationContext, com.mochyy.mochymusic.applesync.di.ApplePlaylistEntryPoint::class.java)
                .dao()
        }.getOrNull() ?: return@produceState
        dao.stateFlow().collect { rows ->
            rows.firstOrNull { it.key == com.mochyy.mochymusic.applesync.PlaylistFolders.KEY }?.value?.let { raw ->
                value = com.mochyy.mochymusic.applesync.PlaylistFolders.decode(raw)
            }
        }
    }
    val folderSections = remember(playlists, folderTree) {
        val tree = folderTree ?: return@remember emptyList()
        tree.folders.map { folder ->
            CollectionFolder(
                id = folder.id,
                name = folder.name,
                items = playlists.filter { tree.parentOf[it.id] == folder.id },
            )
        }
    }
    val resources = LocalContext.current.resources
    val database = LocalDatabase.current
    val player = LocalPlayerConnection.current
    val menuState = LocalMenuState.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.playlists)
    val (sortType, onSortTypeChange) = rememberEnumPreference(LibraryPlaylistsSortTypeKey, PlaylistSortType.CREATE_DATE)
    val (sortDescending, onSortDescendingChange) = rememberPreference(LibraryPlaylistsSortDescendingKey, true)
    val sort = remember(sortType, sortDescending) {
        CollectionSorting(
            descending = sortDescending,
            sectioned = sortType == PlaylistSortType.NAME && !sortDescending,
            comparator = playlistComparator(sortType),
            control = {
                PlaylistSortControl(
                    current = sortType,
                    descending = sortDescending,
                    options = PlaylistSortOrder,
                    label = { playlistSortLabel(it) },
                    onSelect = { type ->
                        onSortTypeChange(type)
                        onSortDescendingChange(type.readsNewestFirst())
                    },
                    onToggleDirection = { onSortDescendingChange(!sortDescending) },
                )
            },
        )
    }

    var folderMenu by remember { mutableStateOf<PlaylistFolders.Folder?>(null) }
    var folderRename by remember { mutableStateOf<PlaylistFolders.Folder?>(null) }
    var folderNewPlaylist by remember { mutableStateOf<PlaylistFolders.Folder?>(null) }
    var folderDelete by remember { mutableStateOf<PlaylistFolders.Folder?>(null) }

    LibraryCollectionPage(
        title = title,
        pageKey = "playlists",
        items = playlists,
        toEntry = { playlist ->
            CollectionEntry(
                id = playlist.id,
                title = playlist.playlist.name,
                subtitle = resources.getQuantityString(R.plurals.lib_song_count, playlist.songCount, playlist.songCount),
                thumbnailUrl = playlist.thumbnails.firstOrNull(),
                // the same key Home's cards use, so the playlist opens growing out of its cover
                sharedElementKey = "playlist-${playlist.id}",
                collaborative = playlist.id in collaborative,
            )
        },
        countText = { pluralStringResource(R.plurals.n_playlist, it, it) },
        searchPlaceholder = stringResource(R.string.lib_playlists_search),
        onOpen = { playlist, _ -> navController.navigate("local_playlist/${playlist.id}") },
        onMenu = { playlist ->
            menuState.show {
                PlaylistMenu(playlist = playlist, coroutineScope = scope, onDismiss = menuState::dismiss, navController = navController)
            }
        },
        onPlay = { ordered, shuffle ->
            scope.playGathered(player, title, shuffle) { ordered.flatMap { list -> database.playlistSongs(list.id).first().map { it.song } } }
        },
        navController = navController,
        sort = sort,
        folders = folderSections,
        onFolderMenu = { id, name -> folderMenu = PlaylistFolders.Folder(id, name) },
    )

    // ---- a folder's own menu: rename it, put a new playlist in it, or delete it -----------------
    folderMenu?.let { folder ->
        ListDialog(onDismiss = { folderMenu = null }) {
            item {
                MenuRow(
                    title = stringResource(R.string.folder_rename),
                    icon = R.drawable.edit,
                    onClick = { folderMenu = null; folderRename = folder },
                )
            }
            item {
                MenuRow(
                    title = stringResource(R.string.folder_add_playlist),
                    icon = R.drawable.playlist_add,
                    onClick = { folderMenu = null; folderNewPlaylist = folder },
                )
            }
            item {
                MenuRow(
                    title = stringResource(R.string.folder_delete),
                    icon = R.drawable.delete,
                    onClick = { folderMenu = null; folderDelete = folder },
                )
            }
        }
    }

    folderRename?.let { folder ->
        TextFieldDialog(
            title = { Text(stringResource(R.string.folder_rename)) },
            initialTextFieldValue = TextFieldValue(folder.name),
            onDismiss = { folderRename = null },
            onDone = { name ->
                folderRename = null
                scope.launch(Dispatchers.IO) { ApplePlaylistWrites.renameFolder(context, folder.id, name.trim()) }
            },
        )
    }

    folderNewPlaylist?.let { folder ->
        TextFieldDialog(
            title = { Text(stringResource(R.string.folder_add_playlist)) },
            placeholder = { Text(stringResource(R.string.playlist_name_hint)) },
            onDismiss = { folderNewPlaylist = null },
            onDone = { name ->
                folderNewPlaylist = null
                scope.launch(Dispatchers.IO) {
                    ApplePlaylistWrites.createPlaylist(context, name.trim(), folder.id)
                }
            },
        )
    }

    folderDelete?.let { folder ->
        DefaultDialog(
            onDismiss = { folderDelete = null },
            title = { Text(stringResource(R.string.folder_delete)) },
            buttons = {
                TextButton(onClick = { folderDelete = null }) { Text(stringResource(R.string.cancel)) }
                TextButton(
                    onClick = {
                        folderDelete = null
                        scope.launch(Dispatchers.IO) { ApplePlaylistWrites.deleteFolder(context, folder.id) }
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
        ) {
            Text(stringResource(R.string.folder_delete_confirm, folder.name))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// the sorts the four library pages offer
// ---------------------------------------------------------------------------------------------

// Only the orders each page can actually answer for: an album has a year and a length, an artist
// does not, and a playlist counts its songs. Date added is "when it reached this library".
private val AlbumSortOrder = listOf(
    AlbumSortType.CREATE_DATE,
    AlbumSortType.NAME,
    AlbumSortType.ARTIST,
    AlbumSortType.YEAR,
    AlbumSortType.SONG_COUNT,
    AlbumSortType.LENGTH,
)

private val ArtistSortOrder = listOf(
    ArtistSortType.CREATE_DATE,
    ArtistSortType.NAME,
    ArtistSortType.SONG_COUNT,
)

private val PlaylistSortOrder = listOf(
    PlaylistSortType.CREATE_DATE,
    PlaylistSortType.NAME,
    PlaylistSortType.SONG_COUNT,
    PlaylistSortType.LAST_UPDATED,
)

// what a date or a count reads as: newest and biggest first. A name or an artist reads A to Z.
private fun AlbumSortType.readsNewestFirst(): Boolean =
    this == AlbumSortType.CREATE_DATE || this == AlbumSortType.YEAR || this == AlbumSortType.SONG_COUNT || this == AlbumSortType.LENGTH

private fun ArtistSortType.readsNewestFirst(): Boolean =
    this == ArtistSortType.CREATE_DATE || this == ArtistSortType.SONG_COUNT

private fun PlaylistSortType.readsNewestFirst(): Boolean =
    this == PlaylistSortType.CREATE_DATE || this == PlaylistSortType.SONG_COUNT || this == PlaylistSortType.LAST_UPDATED

private fun albumComparator(type: AlbumSortType): Comparator<Album> = when (type) {
    AlbumSortType.CREATE_DATE -> compareBy<Album> { it.album.inLibrary ?: it.album.bookmarkedAt ?: it.album.lastUpdateTime }
    AlbumSortType.NAME -> byCollated { it.album.title }
    AlbumSortType.ARTIST -> byCollated { it.artists.joinToString("") { artist -> artist.name } }
    AlbumSortType.YEAR -> compareBy { it.album.year ?: 0 }
    AlbumSortType.SONG_COUNT -> compareBy { it.album.songCount }
    AlbumSortType.LENGTH -> compareBy { it.album.duration }
    AlbumSortType.PLAY_TIME -> compareBy { it.timeListened ?: 0 }
}

private fun artistComparator(type: ArtistSortType): Comparator<Artist> = when (type) {
    ArtistSortType.CREATE_DATE -> compareBy<Artist> { it.artist.bookmarkedAt ?: it.artist.lastUpdateTime }
    ArtistSortType.NAME -> byCollated { it.artist.name }
    ArtistSortType.SONG_COUNT -> compareBy { it.songCount }
    ArtistSortType.PLAY_TIME -> compareBy { it.timeListened ?: 0 }
}

private fun playlistComparator(type: PlaylistSortType): Comparator<Playlist> = when (type) {
    PlaylistSortType.CREATE_DATE -> compareBy<Playlist> { it.playlist.createdAt ?: it.playlist.lastUpdateTime }
    PlaylistSortType.NAME -> byCollated { it.playlist.name }
    PlaylistSortType.SONG_COUNT -> compareBy { it.songCount }
    PlaylistSortType.LAST_UPDATED -> compareBy<Playlist> { it.playlist.lastUpdateTime }
}

@Composable
private fun albumSortLabel(type: AlbumSortType): String = stringResource(
    when (type) {
        AlbumSortType.CREATE_DATE -> R.string.sort_by_create_date
        AlbumSortType.NAME -> R.string.sort_by_name
        AlbumSortType.ARTIST -> R.string.sort_by_artist
        AlbumSortType.YEAR -> R.string.sort_by_year
        AlbumSortType.SONG_COUNT -> R.string.sort_by_song_count
        AlbumSortType.LENGTH -> R.string.sort_by_length
        AlbumSortType.PLAY_TIME -> R.string.sort_by_play_time
    },
)

@Composable
private fun artistSortLabel(type: ArtistSortType): String = stringResource(
    when (type) {
        ArtistSortType.CREATE_DATE -> R.string.sort_by_create_date
        ArtistSortType.NAME -> R.string.sort_by_name
        ArtistSortType.SONG_COUNT -> R.string.sort_by_song_count
        ArtistSortType.PLAY_TIME -> R.string.sort_by_play_time
    },
)

@Composable
private fun playlistSortLabel(type: PlaylistSortType): String = stringResource(
    when (type) {
        PlaylistSortType.CREATE_DATE -> R.string.sort_by_create_date
        PlaylistSortType.NAME -> R.string.sort_by_name
        PlaylistSortType.SONG_COUNT -> R.string.sort_by_song_count
        PlaylistSortType.LAST_UPDATED -> R.string.sort_by_last_updated
    },
)
