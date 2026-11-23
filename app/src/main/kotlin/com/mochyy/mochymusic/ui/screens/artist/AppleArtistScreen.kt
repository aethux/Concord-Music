// Concord: new — Apple's artist page, drawn with Mochy's own playlist and card composables.
//
// The shapes are Mochy's: the same header the playlist pages use for a name, a line of stats and the
// two round buttons, the same track rows, the same section titles and cards. What is Apple's is the
// content and the order Apple puts it in: top songs, then albums, singles, compilations and the
// artists beside them.
package com.mochyy.mochymusic.ui.screens.artist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.mochyy.mochymusic.applesync.AppleAlbumRef
import com.mochyy.mochymusic.applesync.AppleArtistRef
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.ui.component.HomeCoverCard
import com.mochyy.mochymusic.ui.component.HomeSectionTitle
import com.mochyy.mochymusic.ui.component.detail.PlaylistDetailScaffold
import com.mochyy.mochymusic.ui.component.detail.PlaylistEndOfLine
import com.mochyy.mochymusic.ui.component.detail.PlaylistHeader
import com.mochyy.mochymusic.ui.component.detail.PlaylistTrackBones
import com.mochyy.mochymusic.ui.component.detail.PlaylistTrackRow
import com.mochyy.mochymusic.ui.utils.backToMain
import com.mochyy.mochymusic.viewmodels.AppleArtistViewModel

@Composable
fun AppleArtistScreen(
    navController: NavController,
    viewModel: AppleArtistViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val page by viewModel.page.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val lazyListState = rememberLazyListState()

    val current = page
    val songs = current?.songs.orEmpty()

    // read before the scaffold: its content is a LazyListScope, not a composable body
    val topSongsTitle = stringResource(R.string.artist_top_songs)
    val albumsTitle = stringResource(R.string.artist_albums)
    val singlesTitle = stringResource(R.string.artist_singles_eps)
    val compilationsTitle = "Compilations"
    val similarTitle = "Similar artists"

    fun play(startIndex: Int, shuffled: Boolean) {
        val list = if (shuffled) songs.shuffled() else songs
        if (list.isEmpty()) return
        playerConnection.playQueue(
            ListQueue(
                title = current?.artist?.name,
                items = list.map { it.toMediaItem() },
                startIndex = if (shuffled) 0 else startIndex.coerceIn(0, list.size - 1),
            )
        )
    }

    PlaylistDetailScaffold(
        sharedElementKey = "apple_artist-${viewModel.artistId}",
        coverUrl = current?.artist?.artworkUrl?.let { AppleCovers.sized(it, 1000) },
        lazyListState = lazyListState,
        onBackClick = { navController.navigateUp() },
        onBackLongClick = { navController.backToMain() },
        contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom).asPaddingValues(),
    ) {
        if (current == null) {
            item(key = "loading") {
                if (failed) {
                    Text(
                        text = stringResource(R.string.artist_load_failed),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    PlaylistTrackBones()
                }
            }
            return@PlaylistDetailScaffold
        }

        item(key = "header", contentType = "header") {
            PlaylistHeader(
                title = current.artist.name,
                creators = emptyList(),
                stats = current.artist.genres.joinToString(" · "),
                isPlaying = isPlaying,
                onPlayClick = { play(0, shuffled = false) },
                onShuffleClick = { play(0, shuffled = true) },
            )
        }

        if (songs.isNotEmpty()) {
            item(key = "top_title", contentType = "shelf_title") {
                HomeSectionTitle(title = topSongsTitle)
            }
            itemsIndexed(items = songs, key = { _, song -> song.id }) { index, song ->
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
        }

        albumsSection(
            key = "albums",
            title = albumsTitle,
            albums = current.artist.albums,
            navController = navController,
        )
        albumsSection(
            key = "singles",
            title = singlesTitle,
            albums = current.artist.singles,
            navController = navController,
        )
        albumsSection(
            key = "compilations",
            title = compilationsTitle,
            albums = current.artist.compilations,
            navController = navController,
        )
        similarSection(current.artist.similar, similarTitle, navController)

        item(key = "end_of_line") {
            PlaylistEndOfLine(songCount = songs.size, totalSeconds = songs.sumOf { it.song.duration })
        }
    }
}

/** one row of album cards, drawn with the Home cards every other shelf uses */
private fun androidx.compose.foundation.lazy.LazyListScope.albumsSection(
    key: String,
    title: String,
    albums: List<AppleAlbumRef>,
    navController: NavController,
) {
    if (albums.isEmpty()) return
    item(key = "${key}_title", contentType = "shelf_title") {
        HomeSectionTitle(title = title)
    }
    item(key = "${key}_row", contentType = "cover_row") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            items(items = albums, key = { it.id }) { album ->
                HomeCoverCard(
                    title = album.name,
                    subtitle = album.year?.toString(),
                    thumbnailUrl = album.artworkUrl?.let { AppleCovers.sized(it, 400) },
                    size = 150.dp,
                    isPlaying = { false },
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .clickable { navController.navigate("album/${album.id}") },
                    sharedElementKey = "album-${album.id}",
                )
            }
        }
    }
}

/** the artists beside this one, as round cards the way Home draws an artist */
private fun androidx.compose.foundation.lazy.LazyListScope.similarSection(
    similar: List<AppleArtistRef>,
    title: String,
    navController: NavController,
) {
    if (similar.isEmpty()) return
    item(key = "similar_title", contentType = "shelf_title") {
        HomeSectionTitle(title = title)
    }
    item(key = "similar_row", contentType = "artist_row") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            items(items = similar, key = { it.id }) { artist ->
                HomeCoverCard(
                    title = artist.name,
                    subtitle = null,
                    thumbnailUrl = artist.artworkUrl?.let { AppleCovers.sized(it, 400) },
                    circular = true,
                    size = 120.dp,
                    isPlaying = { false },
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .clickable { navController.navigate("apple_artist/${artist.id}") },
                )
            }
        }
    }
}
