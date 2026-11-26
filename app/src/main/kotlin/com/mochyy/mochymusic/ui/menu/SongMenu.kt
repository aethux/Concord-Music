// SongMenu.kt

package com.mochyy.mochymusic.ui.menu

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.music.innertube.YouTube
import com.mochyy.mochymusic.LocalDatabase
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleSongWrites
import com.mochyy.mochymusic.applesync.AppleTokens
import com.mochyy.mochymusic.applesync.AppleVersionSwitch
import com.mochyy.mochymusic.importer.ImportedVersions
import com.mochyy.mochymusic.importer.TrackMatch
import com.mochyy.mochymusic.ui.component.LocalMenuState
import com.mochyy.mochymusic.ui.component.TopToaster
import com.mochyy.mochymusic.LocalDownloadUtil
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.LocalSyncUtils
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.LastAddedPlaylistKey
import com.mochyy.mochymusic.constants.ListItemHeight
import com.mochyy.mochymusic.constants.ListThumbnailSize
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.Event
import com.mochyy.mochymusic.db.entities.SpeedDialItem
import com.mochyy.mochymusic.db.entities.Playlist
import com.mochyy.mochymusic.db.entities.PlaylistSong
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.models.toMediaMetadata
import com.mochyy.mochymusic.playback.ExoDownloadService
import com.mochyy.mochymusic.playback.queues.AppleStationQueue
import com.mochyy.mochymusic.playback.queues.YouTubeQueue
import com.mochyy.mochymusic.ui.component.ListDialog
import com.mochyy.mochymusic.ui.component.LocalBottomSheetPageState
import com.mochyy.mochymusic.ui.component.Material3MenuGroup
import com.mochyy.mochymusic.ui.component.Material3MenuItemData
import com.mochyy.mochymusic.ui.component.NewAction
import com.mochyy.mochymusic.ui.component.NewActionGrid
import com.mochyy.mochymusic.ui.component.SheetActionCard
import com.mochyy.mochymusic.ui.component.SheetActionCardRow
import com.mochyy.mochymusic.ui.component.SheetDownloadCard
import com.mochyy.mochymusic.ui.component.SheetHeaderGap
import com.mochyy.mochymusic.ui.component.SheetHeader
import com.mochyy.mochymusic.ui.component.SheetHeaderButton
import com.mochyy.mochymusic.ui.component.SongListItem
import com.mochyy.mochymusic.ui.menu.songedit.SongEditSheet
import com.mochyy.mochymusic.utils.listItemShape
import com.mochyy.mochymusic.utils.dataStore
import com.mochyy.mochymusic.ui.utils.ShowMediaInfo
import com.mochyy.mochymusic.viewmodels.CachePlaylistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color

@Composable
fun SongMenu(
    originalSong: Song,
    event: Event? = null,
    navController: NavController,
    playlistSong: PlaylistSong? = null,
    playlistBrowseId: String? = null,
    onDismiss: () -> Unit,
    isFromCache: Boolean = false,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val songState = remember(database, originalSong.id) { database.song(originalSong.id) }.collectAsState(initial = originalSong)
    val song = songState.value ?: originalSong
    // an Apple row belongs to Apple: no local edit sheet, and its radio is an Apple station
    val isApple = song.id.startsWith("AM_")
    // the playlist the last add went into: Cider offers it as its own row. Read once per opening;
    // an id that no longer resolves (the playlist was deleted) simply draws nothing.
    val lastPlaylist by produceState<Playlist?>(initialValue = null) {
        val saved = runCatching { context.dataStore.data.first()[LastAddedPlaylistKey] }.getOrNull()
        value = saved?.let { database.playlist(it).first() }
    }
    // Apple's own rating for this row (1 loved, -1 disliked, null unrated), read when the sheet opens
    // so "Suggest less" and "Undo dislike" say what the account says and no column was added
    val appleRating by produceState<Long?>(initialValue = null) {
        if (!isApple) return@produceState
        value = withContext(Dispatchers.IO) {
            val pair = runCatching { AppleTokens(context).read() }.getOrNull()
            pair?.let { runCatching { AppleClient(it).rating(song.id.removePrefix("AM_")) }.getOrNull() }
        }
    }
    val downloadUtil = LocalDownloadUtil.current

    val download by remember(downloadUtil, originalSong.id) {
        downloadUtil.getDownload(originalSong.id)
    }.collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()
    val syncUtils = LocalSyncUtils.current
    val scope = rememberCoroutineScope()
    var refetchIconDegree by remember { mutableFloatStateOf(0f) }

    val cacheViewModel = hiltViewModel<CachePlaylistViewModel>()

    val rotationAnimation by animateFloatAsState(
        targetValue = refetchIconDegree,
        animationSpec = tween(durationMillis = 800),
        label = "",
    )

    val isPinned by remember(database, song.id) { database.speedDialDao.isPinned(song.id) }.collectAsState(initial = false)

    // the four cards at the top read live state: shuffle from the player, membership from the row
    val shuffleOn by playerConnection.shuffleModeEnabled.collectAsState()
    val inLibrary = song.song.inLibrary != null
    val toggleLibrary: () -> Unit = {
        if (song.id.startsWith("AM_")) {
            // an Apple row changes membership on Apple, never through a YouTube feedback token
            coroutineScope.launch { AppleSongWrites.setLibrary(context, song.song, add = !inLibrary) }
        } else {
            val token = if (inLibrary) song.song.libraryRemoveToken else song.song.libraryAddToken
            token?.let { feedback -> coroutineScope.launch { YouTube.feedback(listOf(feedback)) } }
            database.query { update(song.song.toggleLibrary()) }
        }
    }

    // the track an import brought this song in as: offers "Choose different version", and makes
    // Refetch search for that track again rather than only refresh this song's details
    val importMatch by produceState<TrackMatch?>(initialValue = null, song.id) {
        value = ImportedVersions.trackFor(context, database, song.id)
    }
    val menuState = LocalMenuState.current

    val orderedArtists by produceState(initialValue = emptyList<ArtistEntity>(), song) {
        withContext(Dispatchers.IO) {
            val artistMaps = database.songArtistMap(song.id).sortedBy { it.position }
            val sorted = artistMaps.mapNotNull { map ->
                song.artists.firstOrNull { it.id == map.artistId }
            }
            value = sorted
        }
    }

    var showEditDialog by rememberSaveable {
        mutableStateOf(false)
    }

    // the full song edit sheet: cover, name, lyrics, offset, speed, eq, artists, album and its video
    if (showEditDialog) {
        SongEditSheet(metadata = song.toMediaMetadata(), onDismiss = { showEditDialog = false })
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showErrorPlaylistAddDialog by rememberSaveable {
        mutableStateOf(false)
    }

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog,
        onGetSong = { playlist ->
            coroutineScope.launch(Dispatchers.IO) {
                playlist.playlist.browseId?.let { browseId ->
                    YouTube.addToPlaylist(browseId, song.id)
                }
            }
            listOf(song.id)
        },
        onDismiss = {
            showChoosePlaylistDialog = false
        },
        topSongId = song.id,
        topSongArtworkUrl = song.song.thumbnailUrl,
    )

    if (showErrorPlaylistAddDialog) {
        ListDialog(
            onDismiss = {
                showErrorPlaylistAddDialog = false
                onDismiss()
            },
        ) {
            item {
                ListItem(
                    headlineContent = { Text(text = stringResource(R.string.already_in_playlist)) },
                    leadingContent = {
                        Image(
                            painter = painterResource(R.drawable.close),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                            modifier = Modifier.size(ListThumbnailSize),
                        )
                    },
                    modifier = Modifier.clickable { showErrorPlaylistAddDialog = false },
                )
            }

            items(listOf(song)) { song ->
                SongListItem(song = song, isSwipeable = false)
            }
        }
    }

    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showSelectArtistDialog) {
        ListDialog(
            onDismiss = { showSelectArtistDialog = false },
        ) {
            items(
                items = song.artists.distinctBy { it.id },
                key = { it.id },
            ) { artist ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(ListItemHeight)
                        .clickable {
                            navController.navigate("artist/${artist.id}")
                            showSelectArtistDialog = false
                            onDismiss()
                        }
                        .padding(horizontal = 12.dp),
                ) {
                    Box(
                        modifier = Modifier.padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = artist.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(ListThumbnailSize)
                                .clip(CircleShape),
                        )
                    }
                    Text(
                        text = artist.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }

    SheetHeader(
        thumbnailUrl = song.song.thumbnailUrl,
        title = song.song.title,
        subtitle = song.artists.joinToString(", ") { it.name },
        trailing = {
            SheetHeaderButton(
                icon = if (song.song.liked) R.drawable.favorite else R.drawable.favorite_border,
                tint = if (song.song.liked) MaterialTheme.colorScheme.error else Color.White,
                onClick = {
                    if (song.id.startsWith("AM_")) {
                        // an Apple row is loved on Apple — the same write the player's heart makes.
                        // The sheet reads the song from its own flow, so the icon flips when Apple's
                        // read-back lands and the row changes; a YouTube call is never made.
                        coroutineScope.launch {
                            AppleSongWrites.pushLike(context, song.song, !song.song.liked)
                        }
                    } else {
                        val s = song.song.toggleLike()
                        database.query {
                            update(s)
                        }
                        syncUtils.likeSong(s)
                    }
                },
            )
        },
    )

    Spacer(Modifier.height(SheetHeaderGap))

    val bottomSheetPageState = LocalBottomSheetPageState.current
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    LazyColumn(
        contentPadding = PaddingValues(
            start = 0.dp,
            top = 0.dp,
            end = 0.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        item {
            // Four headline actions, 2 × 2 at the same card size: queue this song, queue it behind,
            // switch shuffle, keep it. Download moved to the rows.
            SheetActionCardRow {
                SheetActionCard(
                    icon = R.drawable.playlist_play,
                    title = stringResource(R.string.play_next),
                    onClick = {
                        onDismiss()
                        playerConnection.playNext(song.toMediaItem())
                    },
                    modifier = Modifier.weight(1f),
                )
                SheetActionCard(
                    icon = R.drawable.queue_music,
                    title = stringResource(R.string.add_to_queue),
                    onClick = {
                        onDismiss()
                        playerConnection.addToQueue(song.toMediaItem())
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            SheetActionCardRow {
                SheetActionCard(
                    icon = if (shuffleOn) R.drawable.shuffle_on else R.drawable.shuffle,
                    title = stringResource(R.string.shuffle),
                    onClick = { playerConnection.player.shuffleModeEnabled = !shuffleOn },
                    modifier = Modifier.weight(1f),
                    active = shuffleOn,
                )
                SheetActionCard(
                    icon = if (inLibrary) R.drawable.library_add_check else R.drawable.library_add,
                    title = stringResource(
                        if (inLibrary) R.string.remove_from_library else R.string.add_to_library
                    ),
                    onClick = {
                        onDismiss()
                        toggleLibrary()
                    },
                    modifier = Modifier.weight(1f),
                    active = inLibrary,
                )
            }
        }

        item { Spacer(modifier = Modifier.height(10.dp)) }

        item {
            Material3MenuGroup(
                items = listOfNotNull(
                    lastPlaylist?.let { playlist ->
                        // one tap into the playlist the person used last, without opening the sheet
                        Material3MenuItemData(
                            title = {
                                Text(
                                    text = stringResource(
                                        R.string.add_to_playlist_name,
                                        playlist.playlist.name,
                                    )
                                )
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.playlist_add),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onDismiss()
                                coroutineScope.launch(Dispatchers.IO) {
                                    addToPlaylistKeepingSync(context, database, playlist, listOf(song.id))
                                }
                            }
                        )
                    },
                    Material3MenuItemData(
                        title = { Text(text = stringResource(R.string.add_to_an_playlist)) },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.playlist_add),
                                contentDescription = null,
                            )
                        },
                        onClick = { showChoosePlaylistDialog = true }
                    ),
                    if (song.song.albumId != null) {
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.view_album)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.album),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onDismiss()
                                navController.navigate("album/${song.song.albumId}")
                            }
                        )
                    } else null,
                    Material3MenuItemData(
                        title = { Text(text = stringResource(R.string.view_artist)) },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.artist),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            if (song.artists.size == 1) {
                                navController.navigate("artist/${song.artists[0].id}")
                                onDismiss()
                            } else {
                                showSelectArtistDialog = true
                            }
                        }
                    ),
                    // Cider's "Go to Song": the row's own page on Apple, handed to whatever browser
                    // or app the phone opens music.apple.com with
                    if (isApple) {
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.view_song)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.music_note),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onDismiss()
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://music.apple.com/song/${song.id.removePrefix("AM_")}"),
                                        )
                                    )
                                }
                            }
                        )
                    } else null,
                )
            )
        }

        // Edit and Play are the YouTube row's business. An Apple row is played by tapping it, and
        // everything the edit sheet would rewrite — cover, name, artists, album — is Apple's answer,
        // so the version and video rows below are the only edit an Apple row has.
        if (!isApple) {
            item { Spacer(modifier = Modifier.height(12.dp)) }

            item {
                Material3MenuGroup(
                    items = listOf(
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.edit)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.edit),
                                    contentDescription = null,
                                )
                            },
                            onClick = { showEditDialog = true }
                        ),
                    )
                )
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items = listOf(
                    Material3MenuItemData(
                        title = { Text(text = stringResource(R.string.start_radio)) },
                        description = { Text(text = stringResource(R.string.start_radio_desc)) },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.radio),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            onDismiss()
                            if (isApple) {
                                // `ra.<catalogueId>` is Apple's own station shortcut, the id Cider's
                                // startRadio queues, so no request is spent seeding a song's radio
                                coroutineScope.launch(Dispatchers.IO) {
                                    val queue = AppleStationQueue
                                        .seeded(AppleTokens(context), "ra.${song.id.removePrefix("AM_")}")
                                    withContext(Dispatchers.Main) {
                                        // no session, no station: say so rather than doing nothing
                                        if (queue != null) playerConnection.playQueue(queue)
                                        else TopToaster.show(
                                            text = context.getString(R.string.apple_sign_in_title),
                                        )
                                    }
                                }
                            } else {
                                playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                            }
                        }
                    ),
                    Material3MenuItemData(
                        title = { Text(text = stringResource(R.string.share)) },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.share),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            onDismiss()
                            com.mochyy.mochymusic.ui.player.share.song.SongShare.open(song.toMediaMetadata())
                        }
                    ),
                )
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items = buildList {
                    add(
                        Material3MenuItemData(
                            title = {
                                Text(
                                    text = stringResource(if (isPinned) R.string.unpin_from_speed_dial else R.string.pin_to_speed_dial)
                                )
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(if (isPinned) R.drawable.remove else R.drawable.add),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    if (isPinned) {
                                        database.speedDialDao.delete(song.id)
                                    } else {
                                        database.pinToSpeedDial(
                                            SpeedDialItem(
                                                id = song.id,
                                                title = song.song.title,
                                                subtitle = song.artists.joinToString(", ") { it.name },
                                                thumbnailUrl = song.song.thumbnailUrl,
                                                type = "SONG",
                                                explicit = song.song.explicit
                                            )
                                        )
                                    }
                                }
                                onDismiss()
                            }
                        )
                    )
                    // Download moved out of the cards: it belongs with the keep-it rows, and it
                    // follows the download's own state (start, or remove once saved)
                    if (!song.song.isLocal) {
                        add(
                            Material3MenuItemData(
                                title = {
                                    Text(
                                        text = stringResource(
                                            if (download?.state == Download.STATE_COMPLETED) R.string.remove_download
                                            else R.string.action_download
                                        )
                                    )
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(
                                            if (download?.state == Download.STATE_COMPLETED) R.drawable.offline
                                            else R.drawable.download
                                        ),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    onDismiss()
                                    if (download?.state == Download.STATE_COMPLETED) {
                                        DownloadService.sendRemoveDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            song.id,
                                            false,
                                        )
                                    } else {
                                        val downloadRequest = DownloadRequest
                                            .Builder(song.id, song.id.toUri())
                                            .setCustomCacheKey(song.id)
                                            .setData(song.song.title.toByteArray())
                                            .build()
                                        DownloadService.sendAddDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            downloadRequest,
                                            false,
                                        )
                                    }
                                }
                            )
                        )
                    }
                    // Apple's dislike: read from Apple each time the sheet opens, so the row says what
                    // the account says and no column had to be added to the song table
                    if (isApple) {
                        add(
                            Material3MenuItemData(
                                title = {
                                    Text(
                                        text = stringResource(
                                            if (appleRating == -1L) R.string.undo_dislike else R.string.suggest_less
                                        )
                                    )
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.thumb_down),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    val dislike = appleRating != -1L
                                    onDismiss()
                                    coroutineScope.launch {
                                        AppleSongWrites.pushDislike(context, song.song, dislike)
                                    }
                                }
                            )
                        )
                    }
                    if (event != null) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.remove_from_history)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.delete),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    onDismiss()
                                    database.query {
                                        delete(event)
                                    }
                                }
                            )
                        )
                    }
                    if (playlistSong != null) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.remove_from_playlist)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.delete),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    database.transaction {
                                        coroutineScope.launch {
                                            playlistBrowseId?.let { playlistId ->
                                                if (playlistSong.map.setVideoId != null) {
                                                    YouTube.removeFromPlaylist(
                                                        playlistId,
                                                        playlistSong.map.songId,
                                                        playlistSong.map.setVideoId
                                                    )
                                                }
                                            }
                                        }
                                        move(
                                            playlistSong.map.playlistId,
                                            playlistSong.map.position,
                                            Int.MAX_VALUE
                                        )
                                        delete(playlistSong.map.copy(position = Int.MAX_VALUE))
                                    }
                                    onDismiss()
                                }
                            )
                        )
                    }
                    if (isFromCache) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.remove_from_cache)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.delete),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    onDismiss()
                                    cacheViewModel.removeSongFromCache(song.id)
                                }
                            )
                        )
                    }
                }
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items = buildList {
                    importMatch?.let { match ->
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.menu_choose_version)) },
                                description = { Text(text = stringResource(R.string.menu_choose_version_desc)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.tune),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menuState.showChooseVersionSheet(match, onChosen = onDismiss)
                                }
                            )
                        )
                    }
                    // an Apple row has no Refetch for now: its own refresh (pull to refresh, a re-read
                    // of the page) is the answer, and Refetch here only ever looked for a video
                    if (!isApple) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.refetch)) },
                                description = {
                                    Text(
                                        text = if (importMatch != null) stringResource(R.string.pc_refetch_import_desc)
                                        else stringResource(R.string.refetch_desc)
                                    )
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.sync),
                                        contentDescription = null,
                                        modifier = Modifier.graphicsLayer(rotationZ = rotationAnimation),
                                    )
                                },
                                onClick = {
                                    refetchIconDegree -= 360
                                    val match = importMatch
                                    if (match != null) {
                                        refetchImported(context, database, match)
                                    } else {
                                        scope.launch(Dispatchers.IO) {
                                            YouTube.queue(listOf(song.id)).onSuccess {
                                                val newSong = it.firstOrNull()
                                                if (newSong != null) {
                                                    database.transaction {
                                                        update(song, newSong.toMediaMetadata())
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        )
                    }
                    add(
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.details)) },
                            description = { Text(text = stringResource(R.string.details_desc)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.info),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onDismiss()
                                bottomSheetPageState.show {
                                    ShowMediaInfo(song.id)
                                }
                            }
                        )
                    )
                }
            )
        }
    }
}

/**
 * Refetch on an imported song: searches for the track it was imported as again, and says what came
 * of it. It used to only refresh this song's own details, so a wrong version stayed wrong and the
 * button looked like it did nothing.
 */
private fun refetchImported(context: android.content.Context, database: com.mochyy.mochymusic.db.MusicDatabase, match: TrackMatch) {
    ImportedVersions.scope.launch {
        val switched = ImportedVersions.refetch(context.applicationContext, database, match)
        if (switched != null) {
            TopToaster.show(text = context.getString(R.string.pc_closer_match, switched.title), thumbnail = switched.thumbnail, iconRes = R.drawable.sync)
        } else {
            TopToaster.show(
                text = context.getString(R.string.pc_closest_match),
                iconRes = R.drawable.sync,
                durationMs = 4000L,
            )
        }
    }
}

