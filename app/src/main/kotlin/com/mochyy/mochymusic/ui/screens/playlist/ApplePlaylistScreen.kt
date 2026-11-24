// Concord: new — Apple's playlist page, drawn with Mochy's own playlist composables.
//
// Read-only on purpose: renaming, adding, removing and reordering an Apple playlist are writes, and
// they belong behind AppleGuard with the epoch and the audit row, not on a page that only shows one.
package com.mochyy.mochymusic.ui.screens.playlist

import com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState
import com.mochyy.mochymusic.ui.screens.library.showLibraryImportSheet
import com.mochyy.mochymusic.ui.component.AppleSignInPrompt
import com.mochyy.mochymusic.importer.account.AccountService
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.AppleCollaborator
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.ui.utils.backToMain
import com.mochyy.mochymusic.ui.component.ListDialog
import com.mochyy.mochymusic.ui.component.ListItem
import com.mochyy.mochymusic.ui.component.detail.CreatorUi
import com.mochyy.mochymusic.ui.component.detail.PlaylistDetailScaffold
import com.mochyy.mochymusic.ui.component.detail.PlaylistEndOfLine
import com.mochyy.mochymusic.ui.component.detail.PlaylistHeader
import com.mochyy.mochymusic.ui.component.detail.PlaylistTrackBones
import com.mochyy.mochymusic.ui.component.detail.PlaylistTrackRow
import com.mochyy.mochymusic.ui.component.detail.AppleAddedByAvatar
import com.mochyy.mochymusic.ui.component.detail.AppleCreatorsMenu
import com.mochyy.mochymusic.ui.component.detail.appleProfileRoute
import com.mochyy.mochymusic.ui.component.LocalMenuState
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.viewmodels.ApplePlaylistViewModel

@Composable
fun ApplePlaylistScreen(
    navController: NavController,
    viewModel: ApplePlaylistViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val page by viewModel.page.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val signedOut by viewModel.signedOut.collectAsState()

    // Concord: signing in is Mochy's own import sheet, opened on Apple's sign-in
    val sheets = LocalZoomOutOverlayState.current
// Concord: no Apple session means the page cannot exist — the prompt is the whole screen
    if (signedOut) {
        AppleSignInPrompt(onSignIn = { sheets.showLibraryImportSheet(AccountService.APPLE_MUSIC) })
        return
    }
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val lazyListState = rememberLazyListState()
    val uriHandler = LocalUriHandler.current
    var showPlaylistMenu by remember { mutableStateOf(false) }
    var menuSong by remember { mutableStateOf<Song?>(null) }

    val current = page
    val songs = current?.songs.orEmpty()
    val creators = remember(current) {
        current?.let { creatorsOf(it.owner, it.collaborators, it.curator) }.orEmpty()
    }
    // a face opens a list of everyone, not Apple's website
    val creatorsMenu = LocalMenuState.current


    fun play(startIndex: Int, shuffled: Boolean) {
        val list = if (shuffled) songs.shuffled() else songs
        if (list.isEmpty()) return
        playerConnection.playQueue(
            ListQueue(
                title = current?.name,
                items = list.map { it.toMediaItem() },
                startIndex = if (shuffled) 0 else startIndex.coerceIn(0, list.size - 1),
            )
        )
    }

    PlaylistDetailScaffold(
        sharedElementKey = "apple_playlist-${viewModel.playlistId}",
        coverUrl = current?.artworkUrl,
        lazyListState = lazyListState,
        onBackClick = { navController.navigateUp() },
        onBackLongClick = { navController.backToMain() },
        contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom).asPaddingValues(),
        onMoreClick = { showPlaylistMenu = true },
    ) {
        if (current == null) {
            item(key = "loading") {
                if (failed) {
                    Text(
                        text = stringResource(R.string.playlist_load_failed),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(24.dp)
                            .clickable(onClick = viewModel::retry),
                    )
                } else {
                    PlaylistTrackBones()
                }
            }
            return@PlaylistDetailScaffold
        }

        item(key = "header", contentType = "header") {
            PlaylistHeader(
                title = current.name,
                creators = creators,
                onCreatorClick = {
                    creatorsMenu.show {
                        AppleCreatorsMenu(
                            people = creators,
                            onDismiss = { creatorsMenu.dismiss() },
                            onPersonClick = { person ->
                                appleProfileRoute(person.appleProfileId, person.name, person.avatarUrl)
                                    ?.let { route -> navController.navigate(route) }
                            },
                        )
                    }
                },
                stats = statsLine(current.songs.size, current.totalSeconds),
                isPlaying = isPlaying,
                onPlayClick = { play(0, shuffled = false) },
                onShuffleClick = { play(0, shuffled = true) },
                description = current.description,
            )
        }

        itemsIndexed(items = current.songs, key = { _, song -> song.id }) { index, song ->
            val active = song.id == mediaMetadata?.id
            PlaylistTrackRow(
                thumbnailUrl = song.song.thumbnailUrl,
                title = song.song.title,
                subtitle = song.artists.joinToString { it.name },
                isActive = active,
                isPlaying = active && isPlaying,
                songId = song.id,
                onClick = {
                    if (active) playerConnection.togglePlayPause() else play(index, shuffled = false)
                },
                onLongClick = {},
                onMenuClick = { menuSong = song },
                badge = {
                    // a shared playlist credits every row; one person's own playlist draws nothing
                    current?.addedBy?.get(song.id.removePrefix("AM_"))?.let { person ->
                        AppleAddedByAvatar(
                            avatarUrl = person.avatarUrl,
                            onClick = appleProfileRoute(person.id, person.name, person.avatarUrl)
                                ?.let { route -> { navController.navigate(route) } },
                        )
                    }
                },
            )
        }

        item(key = "end_of_line") {
            PlaylistEndOfLine(songCount = current.songs.size, totalSeconds = current.totalSeconds)
        }
    }

    if (showPlaylistMenu && current != null) {
        ListDialog(onDismiss = { showPlaylistMenu = false }) {
            item {
                MenuRow(
                    title = stringResource(R.string.play_next),
                    icon = R.drawable.play,
                ) {
                    playerConnection.playNext(current.songs.map { it.toMediaItem() })
                    showPlaylistMenu = false
                }
            }
            item {
                MenuRow(
                    title = stringResource(R.string.add_to_queue),
                    icon = R.drawable.queue_music,
                ) {
                    playerConnection.addToQueue(current.songs.map { it.toMediaItem() })
                    showPlaylistMenu = false
                }
            }
            current.url?.let { url ->
                item {
                    MenuRow(
                        title = "Open in Apple Music",
                        icon = R.drawable.play_applemusic,
                    ) {
                        showPlaylistMenu = false
                        uriHandler.openUri(url)
                    }
                }
            }
        }
    }

    menuSong?.let { song ->
        ListDialog(onDismiss = { menuSong = null }) {
            item {
                MenuRow(
                    title = stringResource(R.string.play_next),
                    icon = R.drawable.play,
                ) {
                    playerConnection.playNext(listOf(song.toMediaItem()))
                    menuSong = null
                }
            }
            item {
                MenuRow(
                    title = stringResource(R.string.add_to_queue),
                    icon = R.drawable.queue_music,
                ) {
                    playerConnection.addToQueue(listOf(song.toMediaItem()))
                    menuSong = null
                }
            }
        }
    }
}

/** a menu row: Mochy's own list item with one of its own icons */
@Composable
private fun MenuRow(title: String, icon: Int, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        title = title,
        thumbnailContent = {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.size(22.dp),
            )
        },
    )
}

/**
 * Apple's people, in Apple's order: the owner's profile first, then everyone else on the list.
 * Avatars are the 500px templates Apple sends for social profiles.
 */
private fun creatorsOf(
    owner: AppleCollaborator?,
    collaborators: List<AppleCollaborator>,
    curator: String?,
): List<CreatorUi> {
    // Apple's own mixes name the curator "Apple Music for <person>": that line stays text, the way
    // Apple draws it. People are shown — with their profiles — on a playlist people actually share.
    val editorial = curator?.contains("Apple Music", ignoreCase = true) == true && collaborators.isEmpty()
    val people = if (editorial) emptyList() else buildList {
        owner?.let(::add)
        addAll(collaborators)
    }
        .distinctBy { it.handle ?: it.name }
        .map {
            CreatorUi(
                name = it.name,
                avatarUrl = it.avatarUrl?.let { url -> AppleCovers.sized(url, 160) },
                artistId = null,
                appleProfileId = it.id,
            )
        }
    return people.ifEmpty { listOfNotNull(curator?.let { CreatorUi(name = it, avatarUrl = null, artistId = null) }) }
}

/** "27 songs · 1h 32m", Mochy's own stat sentence shape */
private fun statsLine(count: Int, totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val length = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    return "$count songs · $length"
}
