// Concord: new — Apple's playlist page, drawn with Mochy's own playlist composables.
//
// Read-only on purpose: renaming, adding, removing and reordering an Apple playlist are writes, and
// they belong behind AppleGuard with the epoch and the audit row, not on a page that only shows one.
package com.mochyy.mochymusic.ui.screens.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.ui.utils.backToMain
import com.mochyy.mochymusic.ui.component.detail.CreatorUi
import com.mochyy.mochymusic.ui.component.detail.PlaylistDetailScaffold
import com.mochyy.mochymusic.ui.component.detail.PlaylistEndOfLine
import com.mochyy.mochymusic.ui.component.detail.PlaylistHeader
import com.mochyy.mochymusic.ui.component.detail.PlaylistTrackBones
import com.mochyy.mochymusic.ui.component.detail.PlaylistTrackRow
import com.mochyy.mochymusic.viewmodels.ApplePlaylistViewModel

@Composable
fun ApplePlaylistScreen(
    navController: NavController,
    viewModel: ApplePlaylistViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val page by viewModel.page.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val lazyListState = rememberLazyListState()

    val current = page
    val songs = current?.songs.orEmpty()

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
                creators = listOfNotNull(
                    current.curator?.let { CreatorUi(name = it, avatarUrl = null, artistId = null) }
                ),
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
                onMenuClick = {},
            )
        }

        item(key = "end_of_line") {
            PlaylistEndOfLine(songCount = current.songs.size, totalSeconds = current.totalSeconds)
        }
    }
}

/** "27 songs · 1h 32m", Mochy's own stat sentence shape */
private fun statsLine(count: Int, totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val length = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    return "$count songs · $length"
}
