// Concord: new — the full list behind an artist page's "View all".
//
// The same two lists the artist page previews: Apple's top songs for that artist, or the artist's
// songs that are in the library. One screen, because the rows and the play behaviour are identical.
package com.mochyy.mochymusic.ui.screens.artist

import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.ui.component.detail.PlaylistEndOfLine
import com.mochyy.mochymusic.ui.component.detail.PlaylistTrackRow
import com.mochyy.mochymusic.ui.utils.backToMain
import com.mochyy.mochymusic.viewmodels.AppleArtistViewModel

@Composable
fun AppleArtistSongsScreen(
    navController: NavController,
    viewModel: AppleArtistViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val page by viewModel.page.collectAsState()
    val librarySongs by viewModel.librarySongs.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val lazyListState = rememberLazyListState()

    val songs = if (viewModel.source == "library") librarySongs else page?.songs.orEmpty()

    fun play(index: Int) {
        if (songs.isEmpty()) return
        playerConnection.playQueue(
            ListQueue(
                title = page?.artist?.name,
                items = songs.map { it.toMediaItem() },
                startIndex = index.coerceIn(0, songs.size - 1),
            )
        )
    }

    LazyColumn(
        state = lazyListState,
        contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom).asPaddingValues(),
    ) {
        item(key = "header") {
            ArtistSubpageHeader(
                title = if (viewModel.source == "library") "In your library" else "Top songs",
                subtitle = page?.artist?.name,
                isGrid = null,
                onGridChange = {},
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        itemsIndexed(items = songs, key = { _, song -> song.id }) { index, song ->
            val active = song.id == mediaMetadata?.id
            PlaylistTrackRow(
                thumbnailUrl = song.song.thumbnailUrl,
                title = song.song.title,
                subtitle = listOfNotNull(
                    song.artists.joinToString { it.name }.takeIf { it.isNotBlank() },
                    song.album?.title,
                ).joinToString(" · "),
                isActive = active,
                isPlaying = active && isPlaying,
                songId = song.id,
                onClick = { if (active) playerConnection.togglePlayPause() else play(index) },
                onLongClick = {},
                onMenuClick = {},
            )
        }
        item(key = "end_of_line") {
            PlaylistEndOfLine(songCount = songs.size, totalSeconds = songs.sumOf { it.song.duration })
        }
    }
}
