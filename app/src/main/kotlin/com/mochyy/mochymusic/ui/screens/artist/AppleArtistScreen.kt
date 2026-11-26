// Concord: new — Apple's artist page, built out of Mochy's artist page: the same banner with its
// blurred bands, the same avatar that fades away, the same name, the same release-and-about pager,
// the same shelf titles and cards. Apple supplies the content; Mochy supplies every pixel of the look.
package com.mochyy.mochymusic.ui.screens.artist

import com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState
import com.mochyy.mochymusic.ui.component.rememberRestoredListState
import com.mochyy.mochymusic.ui.screens.library.showLibraryImportSheet
import com.mochyy.mochymusic.ui.component.AppleSignInPrompt
import com.mochyy.mochymusic.importer.account.AccountService
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.statusBars
import com.mochyy.mochymusic.ui.screens.search.SearchColors
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import com.mochyy.mochymusic.canvas.appleArtworkUrl
import com.mochyy.mochymusic.canvas.motionFor
import com.mochyy.mochymusic.constants.ShowArtistBackgroundVideoKey
import com.mochyy.mochymusic.db.entities.AlbumEntity
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.ui.component.BoneColor
import com.mochyy.mochymusic.ui.component.GlassState
import com.mochyy.mochymusic.ui.component.HomeCardHighlight
import com.mochyy.mochymusic.ui.component.HomeCardSpacing
import com.mochyy.mochymusic.ui.component.HomeCoverCard
import com.mochyy.mochymusic.ui.component.revealOnAppear
import com.mochyy.mochymusic.ui.component.rememberRevealSeenState
import com.mochyy.mochymusic.ui.component.detail.PlaylistInset
import com.mochyy.mochymusic.ui.component.detail.PlaylistStatsStyle
import com.mochyy.mochymusic.ui.component.detail.PlaylistTrackBones
import com.mochyy.mochymusic.ui.component.detail.playlistCoverHeight
import com.mochyy.mochymusic.ui.utils.backToMain
import com.mochyy.mochymusic.utils.makeTimeString
import com.mochyy.mochymusic.utils.rememberPreference
import com.mochyy.mochymusic.ui.component.rememberCoverBlurAvailable
import com.mochyy.mochymusic.ui.theme.rememberCoverThemeColor
import com.mochyy.mochymusic.viewmodels.AppleArtistViewModel
import com.mochyy.mochymusic.viewmodels.ArtistRelease
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.WindowInsets

@Composable
fun AppleArtistScreen(
    navController: NavController,
    viewModel: AppleArtistViewModel = hiltViewModel(),
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
    val librarySongs by viewModel.librarySongs.collectAsState()
    val libraryAlbums by viewModel.libraryAlbums.collectAsState()
    val liked by viewModel.liked.collectAsState()
    val editorial by viewModel.editorial.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val showBackgroundVideo by rememberPreference(key = ShowArtistBackgroundVideoKey, defaultValue = true)
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    val current = page
    val artist = current?.artist
    val songs = current?.songs.orEmpty()
    val name = artist?.name.orEmpty()

    val topTitle = stringResource(R.string.artist_top_songs)
    val libraryTitle = stringResource(R.string.artist_in_your_library)
    val savedTitle = stringResource(R.string.artist_saved_albums_of, name)
    val albumsTitle = stringResource(R.string.artist_albums)
    val singlesTitle = stringResource(R.string.artist_singles_eps)
    val videosTitle = stringResource(R.string.artist_videos)
    val similarTitle = stringResource(R.string.artist_similar)

    val lazyListState = rememberRestoredListState(hasContent = page != null)
    val revealSeen = rememberRevealSeenState()

    val bannerHeight = playlistCoverHeight()
    val headerFoot = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + 6.dp
    val bannerHeightPx = with(LocalDensity.current) { bannerHeight.toPx() }
    val scrollProgress = remember(lazyListState, bannerHeightPx) {
        derivedStateOf {
            if (lazyListState.firstVisibleItemIndex > 0) 1f
            else (lazyListState.firstVisibleItemScrollOffset / bannerHeightPx).coerceIn(0f, 1f)
        }
    }
    val collapsed by remember(scrollProgress) { derivedStateOf { scrollProgress.value > 0.62f } }
    val morph = remember { Animatable(0f) }
    LaunchedEffect(collapsed) {
        morph.animateTo(
            targetValue = if (collapsed) 1f else 0f,
            animationSpec = spring(dampingRatio = if (collapsed) 0.62f else 0.9f, stiffness = 360f),
        )
    }
    val glassState = remember { GlassState() }

    // Cider's per-page pull: this artist is re-read, and nothing else in the library is touched.
    val refreshing by viewModel.refreshing.collectAsState()
    val refreshState = rememberPullToRefreshState()
    val refreshHold by animateFloatAsState(
        targetValue = if (refreshing) 1f else 0f,
        animationSpec = tween(durationMillis = if (refreshing) 180 else 320, easing = FastOutSlowInEasing),
        label = "refreshHold",
    )
    val refreshProgress = maxOf(refreshState.distanceFraction, refreshHold)
    val refreshShift = 72.dp * refreshProgress

    // the loop only starts once the page has opened and only runs while the banner sits still, the
    // way Mochy's does: every frame of it repaints the banner and both blur bands
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(420)
        opened = true
    }
    val atRest by remember { derivedStateOf { scrollProgress.value < 0.02f } }
    val edgeBlur = rememberCoverBlurAvailable()

    val stillUrl = editorial?.previewFrameUrl?.let { appleArtworkUrl(it, 1200, 1200, "webp") }
        ?: artist?.artworkUrl?.let { AppleCovers.sized(it, 1200) }
    val themeColor by rememberCoverThemeColor(stillUrl ?: artist?.artworkUrl)
    val background = animateColorAsState(
        targetValue = themeColor ?: MaterialTheme.colorScheme.background,
        animationSpec = tween(600),
        label = "artistTheme",
    )
    val bannerAspect = bannerHeight.value / androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val motionUrl = editorial?.motionFor(bannerAspect)

    val avatarAlpha by remember(scrollProgress) {
        derivedStateOf { (1f - scrollProgress.value / 0.32f).coerceIn(0f, 1f) }
    }
    val avatarGone = avatarAlpha <= 0.01f

    fun play(list: List<Song>, shuffled: Boolean, startIndex: Int = 0) {
        val queue = if (shuffled) list.shuffled() else list
        if (queue.isEmpty()) return
        playerConnection.playQueue(
            ListQueue(
                title = name,
                items = queue.map { it.toMediaItem() },
                startIndex = if (shuffled) 0 else startIndex.coerceIn(0, queue.size - 1),
            )
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom).asPaddingValues(),
            modifier = Modifier
                .fillMaxSize()
                .pullToRefresh(
                    state = refreshState,
                    isRefreshing = refreshing,
                    onRefresh = viewModel::refresh,
                )
                .graphicsLayer { translationY = refreshShift.toPx() },
        ) {
            item(key = "header", contentType = "header") {
                val landscape = com.mochyy.mochymusic.ui.utils.isLandscapeLayout()
                Box(
                    contentAlignment = Alignment.BottomStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        // portrait lets the block sit past the photo, so the actions are never clipped
                        .then(if (landscape) Modifier.height(bannerHeight - headerFoot) else Modifier),
                ) {
                    ArtistBanner(
                        photoUrl = artist?.artworkUrl?.let { AppleCovers.sized(it, 1200) },
                        stillUrl = stillUrl,
                        motionUrl = motionUrl?.takeIf { showBackgroundVideo },
                        playMotion = { opened && atRest && showBackgroundVideo },
                        edgeBlur = edgeBlur,
                        height = bannerHeight,
                        scrollProgress = { scrollProgress.value },
                        background = background,
                    )
                    // the foot of the cover is always smoked, whether the photo is blurred or not:
                    // the name and the actions sit on it, and a bright banner would swallow them
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(bannerHeight * 0.55f)
                            .align(Alignment.BottomCenter)
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.55f to Color.Black.copy(alpha = 0.55f),
                                    1f to Color.Black.copy(alpha = 0.88f),
                                )
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = bannerHeight * ArtistAvatarAt)
                            .padding(start = PlaylistInset - 3.dp, end = PlaylistInset),
                    ) {
                        // its room stays, so the name and the list don't jump when the circle leaves
                        if (avatarGone) {
                            Spacer(Modifier.height(ArtistAvatarSize))
                        } else {
                            ArtistAvatar(
                                url = stillUrl ?: artist?.artworkUrl,
                                modifier = Modifier.graphicsLayer { alpha = avatarAlpha },
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        ArtistName(
                            name = name,
                            logoUrl = editorial?.logoUrl?.let { template ->
                                val aspect = editorial?.logoAspect ?: 3f
                                appleArtworkUrl(template, 1200, (1200 / aspect).toInt().coerceAtLeast(1), "webp")
                            },
                            logoAspect = editorial?.logoAspect,
                        )
                        val genres = artist?.genres.orEmpty()
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
                            onPlay = { if (isPlaying) playerConnection.togglePlayPause() else play(songs, false) },
                            onLike = viewModel::toggleLike,
                            onSubscribe = {},
                            onRadio = {
                                scope.launch { viewModel.stationQueue()?.let { playerConnection.playQueue(it) } }
                            },
                            onShuffle = { play(songs, shuffled = true) },
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

            // Mochy's release-and-about pager: the newest release with the bio a swipe away
            val release = artist?.latestRelease
            if (release != null || !editorial?.bio.isNullOrBlank()) {
                item(key = "release", contentType = "release") {
                    ArtistReleasePager(
                        release = release?.let {
                            ArtistRelease(
                                label = "Latest release",
                                title = it.name,
                                subtitle = name,
                                thumbnailUrl = it.artworkUrl?.let { url -> AppleCovers.sized(url, 600) },
                                dateLabel = it.year?.toString(),
                            )
                        },
                        bio = editorial?.bio,
                        artistName = name,
                        releaseSaved = false,
                        isReleasePlaying = false,
                        onReleaseClick = { release?.let { navController.navigate("album/${it.id}") } },
                        onReleasePlay = { release?.let { navController.navigate("album/${it.id}") } },
                        onReleaseSave = {},
                        onBioClick = {},
                        modifier = Modifier.padding(top = 20.dp),
                    )
                }
            }

            if (songs.isNotEmpty()) {
                item(key = "top_title", contentType = "shelf_title") {
                    // every top song is already on the page, so the title has nowhere to send you
                    ArtistShelfTitle(
                        title = topTitle,
                        modifier = Modifier
                            .padding(top = 30.dp, bottom = 8.dp)
                            .revealOnAppear(key = "top_title", seenState = revealSeen),
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
                        onViewAll = null,
                        modifier = Modifier.revealOnAppear(key = "top_grid", seenState = revealSeen),
                    )
                }
            }

            if (librarySongs.isNotEmpty()) {
                item(key = "library_title", contentType = "shelf_title") {
                    ArtistShelfTitle(
                        title = libraryTitle,
                        onClick = { navController.navigate("apple_artist_songs/${viewModel.artistId}/library") },
                        modifier = Modifier
                            .padding(top = 30.dp, bottom = 8.dp)
                            .revealOnAppear(key = "library_title", seenState = revealSeen),
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
                        modifier = Modifier.revealOnAppear(key = "library_grid", seenState = revealSeen),
                    )
                }
            }

            coverRow(
                key = "saved_albums",
                title = savedTitle,
                seen = revealSeen,
            ) {
                libraryAlbums.map { album ->
                    AlbumCard(
                        id = album.id,
                        title = album.title,
                        subtitle = album.year?.toString(),
                        artworkUrl = album.thumbnailUrl,
                        onClick = { navController.navigate("album/${album.id}") },
                    )
                }
            }

            coverRow("albums", albumsTitle, revealSeen) {
                artist?.albums.orEmpty().map { it.toCard() }
            }
            coverRow("singles", singlesTitle, revealSeen) {
                artist?.singles.orEmpty().map { it.toCard() }
            }
            coverRow("compilations", "Compilations", revealSeen) {
                artist?.compilations.orEmpty().map { it.toCard() }
            }

            if (!artist?.videos.isNullOrEmpty()) {
                coverRow("videos", videosTitle, revealSeen) {
                    artist!!.videos.map { video ->
                        AlbumCard(
                            id = "video_${video.id}",
                            title = video.name,
                            subtitle = null,
                            artworkUrl = video.artworkUrl?.let { AppleCovers.sized(it, 600) },
                            wide = true,
                            onClick = { video.url?.let { uriHandler.openUri(it) } },
                        )
                    }
                }
            }

            coverRow("similar", similarTitle, revealSeen, circular = true) {
                artist?.similar.orEmpty().map { other ->
                    AlbumCard(
                        id = other.id,
                        title = other.name,
                        subtitle = null,
                        artworkUrl = other.artworkUrl?.let { AppleCovers.sized(it, 600) },
                        circular = true,
                        onClick = { navController.navigate("apple_artist/${other.id}") },
                    )
                }
            }
        }

        ArtistTopBar(
            glassState = glassState,
            morph = { morph.value },
            title = name,
            background = background,
            profileImage = null,
            onBackClick = { navController.navigateUp() },
            onBackLongClick = { navController.backToMain() },
            onProfileClick = {},
        )

        if (refreshProgress > 0f) {
            val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusBar + 12.dp)
                    .size(40.dp)
                    .alpha(refreshProgress.coerceIn(0f, 1f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = SearchColors.Secondary,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

private data class CardItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val circular: Boolean = false,
    val wide: Boolean = false,
    val onClick: () -> Unit,
)

private fun AppleAlbumRef.toCard(): CardItem =
    CardItem(
        id = id,
        title = name,
        subtitle = year?.toString(),
        artworkUrl = artworkUrl?.let { AppleCovers.sized(it, 600) },
        onClick = {},
    )

/** a shelf title and its row of cards, revealed on the way in like every other Mochy shelf */
private fun androidx.compose.foundation.lazy.LazyListScope.coverRow(
    key: String,
    title: String,
    seen: com.mochyy.mochymusic.ui.component.RevealSeenState,
    circular: Boolean = false,
    cards: () -> List<CardItem>,
) {
    val items = cards()
    if (items.isEmpty()) return
    item(key = "${key}_title", contentType = "shelf_title") {
        ArtistShelfTitle(
            title = title,
            modifier = Modifier
                .padding(top = 30.dp, bottom = 8.dp)
                .revealOnAppear(key = "${key}_title", seenState = seen),
        )
    }
    item(key = "${key}_row", contentType = "cover_row") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = PlaylistInset),
            horizontalArrangement = Arrangement.spacedBy(HomeCardSpacing),
            modifier = Modifier.revealOnAppear(key = "${key}_row", seenState = seen),
        ) {
            items(items = items, key = { it.id }) { card ->
                CoverCard(card, circular = circular)
            }
        }
    }
}

@Composable
private fun CoverCard(card: CardItem, circular: Boolean = false) {
    HomeCoverCard(
        title = card.title,
        subtitle = card.subtitle,
        thumbnailUrl = card.artworkUrl,
        circular = card.circular || circular,
        size = if (card.wide) 190.dp else 150.dp,
        isPlaying = { false },
        modifier = Modifier.combinedClickable(
            interactionSource = null,
            indication = HomeCardHighlight,
            onClick = card.onClick,
            onLongClick = {},
        ),
    )
}

/** album cards handed to the shelf builder already carrying their own tap */
private fun AlbumCard(
    id: String,
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    circular: Boolean = false,
    wide: Boolean = false,
    onClick: () -> Unit,
): CardItem = CardItem(id, title, subtitle, artworkUrl, circular, wide, onClick)

/**
 * The grid the page had: three rows tall, scrolling sideways, each cell a compact song. It stops at
 * three columns now — eight songs and a View all in the ninth cell — so it reads as a batch rather
 * than an endless strip.
 */
@Composable
private fun ArtistSongGrid(
    songs: List<Song>,
    playingId: () -> String?,
    isPlaying: () -> Boolean,
    onSong: (Int) -> Unit,
    /** when given, the grid stops at three columns and its ninth cell is View all */
    onViewAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val rows = minOf(3, songs.size).coerceAtLeast(1)
    val shown = if (onViewAll != null) songs.take(8) else songs
    val more = onViewAll != null && songs.size > shown.size
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columnWidth = maxWidth * 0.78f
        androidx.compose.foundation.lazy.grid.LazyHorizontalGrid(
            state = gridState,
            rows = androidx.compose.foundation.lazy.grid.GridCells.Fixed(rows),
            // the shelf title starts at PlaylistInset - 4dp, so the rows do too: the grid's left
            // edge sits on the title's, not four dp past it
            contentPadding = PaddingValues(start = 34.dp, end = PlaylistInset),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp * rows),
        ) {
            itemsIndexed(items = shown, key = { _, song -> song.id }) { index, song ->
                CompactTrackRow(
                    song = song,
                    active = song.id == playingId(),
                    playing = song.id == playingId() && isPlaying(),
                    onClick = { onSong(index) },
                    modifier = Modifier.width(columnWidth),
                )
            }
            if (more && onViewAll != null) {
                item(key = "view_all") {
                    ViewAllTile(onClick = onViewAll, modifier = Modifier.width(columnWidth).height(52.dp))
                }
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
            .clickable(onClick = onClick),
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
