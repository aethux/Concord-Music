// Concord: new — Apple's artist page, in Mochy's artist-page clothes.
//
// The header is Mochy's: a full-bleed photo, the round avatar over its foot, the name in the artist
// title style, and the same play pill and heart. The shelves are Mochy's too — the Most Played grid
// for songs, cover rows for albums, round cards for similar artists, the same about at the end. What
// is Apple's is the content: its artist resource, its five views, its music videos and its bio.
package com.mochyy.mochymusic.ui.screens.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.AppleAlbumRef
import com.mochyy.mochymusic.applesync.AppleArtistRef
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.AppleMusicVideo
import com.mochyy.mochymusic.db.entities.AlbumEntity
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.ui.component.BoneColor
import com.mochyy.mochymusic.ui.component.HomeCardHighlight
import com.mochyy.mochymusic.ui.component.HomeCardSpacing
import com.mochyy.mochymusic.ui.component.HomeCoverCard
import com.mochyy.mochymusic.ui.component.HomeSectionTitle
import com.mochyy.mochymusic.ui.component.detail.PlaylistInset
import com.mochyy.mochymusic.ui.component.detail.PlaylistStatsStyle
import com.mochyy.mochymusic.ui.component.detail.PlaylistTrackBones
import com.mochyy.mochymusic.ui.utils.backToMain
import com.mochyy.mochymusic.utils.makeTimeString
import com.mochyy.mochymusic.viewmodels.AppleArtistViewModel

@Composable
fun AppleArtistScreen(
    navController: NavController,
    viewModel: AppleArtistViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val page by viewModel.page.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val librarySongs by viewModel.librarySongs.collectAsState()
    val libraryAlbums by viewModel.libraryAlbums.collectAsState()
    val liked by viewModel.liked.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val lazyListState = rememberLazyListState()
    val uriHandler = LocalUriHandler.current

    val current = page
    val songs = current?.songs.orEmpty()

    val topSongsTitle = stringResource(R.string.artist_top_songs)
    val libraryTitle = stringResource(R.string.artist_in_your_library)
    val savedAlbumsTitle = stringResource(R.string.artist_saved_albums_of, current?.artist?.name.orEmpty())
    val albumsTitle = stringResource(R.string.artist_albums)
    val singlesTitle = stringResource(R.string.artist_singles_eps)
    val videosTitle = stringResource(R.string.artist_videos)

    fun play(list: List<Song>, shuffled: Boolean, startIndex: Int = 0) {
        val queue = if (shuffled) list.shuffled() else list
        if (queue.isEmpty()) return
        playerConnection.playQueue(
            ListQueue(
                title = current?.artist?.name,
                items = queue.map { it.toMediaItem() },
                startIndex = if (shuffled) 0 else startIndex.coerceIn(0, queue.size - 1),
            )
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val bannerHeight = maxWidth * 0.98f
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom).asPaddingValues(),
        ) {
            item(key = "header", contentType = "header") {
                Column {
                    Box(modifier = Modifier.fillMaxWidth().height(bannerHeight)) {
                        if (current != null) {
                            AsyncImage(
                                model = current.artist.artworkUrl?.let { AppleCovers.sized(it, 1200) },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().background(BoneColor),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.55f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = 0.72f),
                                    )
                                ),
                        )
                    }
                    Column(modifier = Modifier.padding(start = PlaylistInset, end = PlaylistInset, bottom = 8.dp)) {
                        ArtistAvatar(url = current?.artist?.artworkUrl?.let { AppleCovers.sized(it, 544) })
                        Spacer(Modifier.height(14.dp))
                        ArtistName(name = current?.artist?.name.orEmpty(), logoUrl = null, logoAspect = null)
                        val genres = current?.artist?.genres.orEmpty()
                        if (genres.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = genres.joinToString(" · "),
                                style = PlaylistStatsStyle,
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        ArtistActions(
                            isPlaying = isPlaying,
                            liked = liked,
                            subscribed = null,
                            showSubscribe = false,
                            onPlay = { if (isPlaying) playerConnection.togglePlayPause() else play(songs, shuffled = false) },
                            onLike = viewModel::toggleLike,
                            onSubscribe = {},
                        )
                    }
                }
            }

            if (current == null) {
                item(key = "loading") {
                    if (failed) {
                        Text(
                            text = stringResource(R.string.artist_load_failed),
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(PlaylistInset),
                        )
                    } else {
                        Column(modifier = Modifier.padding(top = 24.dp)) {
                            PlaylistTrackBones(rows = 5)
                        }
                    }
                }
                return@LazyColumn
            }

            if (songs.isNotEmpty()) {
                item(key = "top_title", contentType = "shelf_title") {
                    HomeSectionTitle(
                        title = topSongsTitle,
                        onClick = { navController.navigate("apple_artist_songs/${viewModel.artistId}/top") },
                    )
                }
                item(key = "top_grid", contentType = "track_grid") {
                    ArtistSongGrid(
                        songs = songs,
                        playingId = { mediaMetadata?.id },
                        isPlaying = { isPlaying },
                        onSong = { index ->
                            val id = songs[index].id
                            if (id == mediaMetadata?.id) playerConnection.togglePlayPause() else play(songs, false, index)
                        },
                        onViewAll = { navController.navigate("apple_artist_songs/${viewModel.artistId}/top") },
                    )
                }
            }

            if (librarySongs.isNotEmpty()) {
                item(key = "library_title", contentType = "shelf_title") {
                    HomeSectionTitle(
                        title = libraryTitle,
                        onClick = { navController.navigate("apple_artist_songs/${viewModel.artistId}/library") },
                    )
                }
                item(key = "library_grid", contentType = "track_grid") {
                    ArtistSongGrid(
                        songs = librarySongs,
                        playingId = { mediaMetadata?.id },
                        isPlaying = { isPlaying },
                        onSong = { index ->
                            val id = librarySongs[index].id
                            if (id == mediaMetadata?.id) playerConnection.togglePlayPause()
                            else play(librarySongs, false, index)
                        },
                        onViewAll = { navController.navigate("apple_artist_songs/${viewModel.artistId}/library") },
                    )
                }
            }

            if (libraryAlbums.isNotEmpty()) {
                item(key = "saved_albums_title", contentType = "shelf_title") {
                    HomeSectionTitle(title = savedAlbumsTitle)
                }
                item(key = "saved_albums_row", contentType = "cover_row") {
                    AlbumRow(
                        albums = libraryAlbums.map { it.id to (it.title to it.thumbnailUrl) },
                        onAlbum = { navController.navigate("album/$it") },
                    )
                }
            }

            albumSection("albums", albumsTitle, current.artist.albums, navController)
            albumSection("singles", singlesTitle, current.artist.singles, navController)
            albumSection("compilations", "Compilations", current.artist.compilations, navController)

            if (current.artist.videos.isNotEmpty()) {
                item(key = "videos_title", contentType = "shelf_title") {
                    HomeSectionTitle(title = videosTitle)
                }
                item(key = "videos_row", contentType = "video_row") {
                    VideoRow(current.artist.videos) { video ->
                        // Apple plays music videos through its own player; the page opens them on
                        // music.apple.com until that player exists here
                        video.url?.let { url -> uriHandler.openUri(url) }
                    }
                }
            }

            if (current.artist.similar.isNotEmpty()) {
                item(key = "similar_title", contentType = "shelf_title") {
                    HomeSectionTitle(title = "Similar artists")
                }
                item(key = "similar_row", contentType = "artist_row") {
                    SimilarRow(current.artist.similar) { artist ->
                        navController.navigate("apple_artist/${artist.id}")
                    }
                }
            }

            if (!current.artist.bio.isNullOrBlank()) {
                item(key = "about") {
                    ArtistAbout(name = current.artist.name, bio = plainBio(current.artist.bio.orEmpty()))
                }
            }
        }
    }
}

/** the Most Played grid: a few rows tall and scrolling sideways, with the last cell opening the rest */
@Composable
private fun ArtistSongGrid(
    songs: List<Song>,
    playingId: () -> String?,
    isPlaying: () -> Boolean,
    onSong: (Int) -> Unit,
    onViewAll: () -> Unit,
) {
    val rows = minOf(3, songs.size).coerceAtLeast(1)
    val gridState = rememberLazyGridState()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columnWidth = maxWidth * 0.78f
        LazyHorizontalGrid(
            state = gridState,
            rows = GridCells.Fixed(rows),
            contentPadding = PaddingValues(horizontal = PlaylistInset),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp * rows),
        ) {
            itemsIndexed(items = songs, key = { _, song -> song.id }) { index, song ->
                CompactTrackRow(
                    song = song,
                    active = song.id == playingId(),
                    playing = song.id == playingId() && isPlaying(),
                    onClick = { onSong(index) },
                    modifier = Modifier.width(columnWidth),
                )
            }
            item(key = "view_all") {
                ViewAllTile(onClick = onViewAll, modifier = Modifier.width(columnWidth).height(52.dp))
            }
        }
    }
}

/** one song in the grid: cover, title, artist and length, the shape Mochy's Most Played uses */
@Composable
private fun CompactTrackRow(
    song: Song,
    active: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        AsyncImage(
            model = song.song.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(BoneColor),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.song.title,
                color = if (active) Color(0xFFB6F0C8) else Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    song.artists.joinToString { it.name }.takeIf { it.isNotBlank() },
                    makeTimeString(song.song.duration * 1000L),
                ).joinToString(" · "),
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ViewAllTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
    ) {
        Text(
            text = "View all",
            color = Color.White.copy(alpha = 0.86f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(4.dp))
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White.copy(alpha = 0.7f)),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun AlbumRow(
    albums: List<Pair<String, Pair<String, String?>>>,
    onAlbum: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = PlaylistInset),
        horizontalArrangement = Arrangement.spacedBy(HomeCardSpacing),
    ) {
        items(items = albums, key = { it.first }) { (id, meta) ->
            val (title, art) = meta
            HomeCoverCard(
                title = title,
                subtitle = null,
                thumbnailUrl = art,
                size = 150.dp,
                isPlaying = { false },
                sharedElementKey = "album-$id",
                modifier = Modifier.combinedClickable(
                    interactionSource = null,
                    indication = HomeCardHighlight,
                    onClick = { onAlbum(id) },
                    onLongClick = {},
                ),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.albumSection(
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
            contentPadding = PaddingValues(horizontal = PlaylistInset),
            horizontalArrangement = Arrangement.spacedBy(HomeCardSpacing),
        ) {
            items(items = albums, key = { it.id }) { album ->
                HomeCoverCard(
                    title = album.name,
                    subtitle = album.year?.toString(),
                    thumbnailUrl = album.artworkUrl?.let { AppleCovers.sized(it, 400) },
                    size = 150.dp,
                    isPlaying = { false },
                    sharedElementKey = "album-${album.id}",
                    modifier = Modifier.combinedClickable(
                        interactionSource = null,
                        indication = HomeCardHighlight,
                        onClick = { navController.navigate("album/${album.id}") },
                        onLongClick = {},
                    ),
                )
            }
        }
    }
}

@Composable
private fun VideoRow(videos: List<AppleMusicVideo>, onClick: (AppleMusicVideo) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = PlaylistInset),
        horizontalArrangement = Arrangement.spacedBy(HomeCardSpacing),
    ) {
        items(items = videos, key = { it.id }) { video ->
            HomeCoverCard(
                title = video.name,
                subtitle = null,
                thumbnailUrl = video.artworkUrl?.let { AppleCovers.sized(it, 400) },
                size = 190.dp,
                isPlaying = { false },
                modifier = Modifier.clickable { onClick(video) },
            )
        }
    }
}

@Composable
private fun SimilarRow(similar: List<AppleArtistRef>, onClick: (AppleArtistRef) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = PlaylistInset),
        horizontalArrangement = Arrangement.spacedBy(HomeCardSpacing),
    ) {
        items(items = similar, key = { it.id }) { artist ->
            HomeCoverCard(
                title = artist.name,
                subtitle = null,
                thumbnailUrl = artist.artworkUrl?.let { AppleCovers.sized(it, 400) },
                circular = true,
                size = 120.dp,
                isPlaying = { false },
                modifier = Modifier.clickable { onClick(artist) },
            )
        }
    }
}

/** the bio at the foot of the page: six lines, and a tap opens the rest in place */
@Composable
private fun ArtistAbout(name: String, bio: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = PlaylistInset).padding(top = 26.dp, bottom = 18.dp)) {
        Text(
            text = stringResource(R.string.artist_about, name),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = bio,
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { expanded = !expanded },
        )
    }
}

/** Apple writes the bio as html; this is its text, with its paragraph breaks kept */
private fun plainBio(html: String): String =
    html.replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>"), "\n\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
