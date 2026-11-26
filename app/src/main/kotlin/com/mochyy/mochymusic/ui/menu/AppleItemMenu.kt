// Concord: new — one Apple card's menu: the actions Cider puts behind a card's hover button,
// for the cards Apple's own shelves draw on Home (an artist, an album, a playlist, a station).
//
// One sheet for every kind, the way Cider has one builder for every media item: a row's presence is
// the card's kind, never a per-shelf menu. Favourite reads and writes Apple itself — no local row is
// touched, since none of these cards has one.
//
// An artist has two card shapes on Apple's Home: an `artists` row, and a station Apple marks
// `artistOnly` whose id is `ra.a-<artistId>` (the "Favourite Artists" shelf). Both draw the same menu
// from `AppleHomeItem.artistId`, and both favourite and open the ARTIST, never the station.
package com.mochyy.mochymusic.ui.menu

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.AppleFavorites
import com.mochyy.mochymusic.applesync.AppleHomeItem
import com.mochyy.mochymusic.ui.component.Material3MenuGroup
import com.mochyy.mochymusic.ui.component.Material3MenuItemData
import com.mochyy.mochymusic.ui.component.SheetHeader
import com.mochyy.mochymusic.ui.component.SheetHeaderGap
import kotlinx.coroutines.launch

/** The artist a card stands for: an `artists` card's id, or the one an artist-only station encodes. */
private val AppleHomeItem.cardArtistId: String?
    get() = artistId ?: id.takeIf { kind == AppleHomeItem.Kind.Artist }

/** Apple's resource type and id for this card's favourite, or null when it cannot be favourited. */
private fun favoriteTargetOf(item: AppleHomeItem): Pair<String, String>? {
    val artist = item.cardArtistId
    return when {
        artist != null -> "artists" to artist
        item.kind == AppleHomeItem.Kind.Album -> "albums" to item.id
        item.kind == AppleHomeItem.Kind.Playlist ->
            (if (item.id.startsWith("p.")) "library-playlists" else "playlists") to item.id
        else -> null
    }
}

@Composable
fun AppleItemMenu(
    item: AppleHomeItem,
    navController: NavController,
    /** what tapping the card does: View album / playlist, or play a station */
    onOpen: () -> Unit,
    readFavorite: suspend (type: String, id: String) -> Boolean?,
    onStartRadio: suspend (artistId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val artistId = item.cardArtistId
    val favorite = remember(item.id) { favoriteTargetOf(item) }
    var favorited by remember(item.id) { mutableStateOf<Boolean?>(null) }
    var saving by remember(item.id) { mutableStateOf(false) }

    // the state is read when the sheet opens, never assumed: a favourite has no read endpoint of its
    // own, so this asks the resource and draws the answer
    LaunchedEffect(item.id, favorite) {
        favorite?.let { (type, id) -> favorited = readFavorite(type, id) }
    }

    // an artist card shares the artist's page; an artist-only station's own url is the station's
    val shareUrl = when {
        artistId == null -> item.url
        item.kind == AppleHomeItem.Kind.Artist -> item.url ?: "https://music.apple.com/artist/$artistId"
        else -> "https://music.apple.com/artist/$artistId"
    }
    val openLabel = when {
        artistId != null -> R.string.view_artist
        item.kind == AppleHomeItem.Kind.Album -> R.string.view_album
        item.kind == AppleHomeItem.Kind.Playlist -> R.string.view_playlist
        else -> null
    }
    val openIcon = when {
        artistId != null -> R.drawable.artist
        item.kind == AppleHomeItem.Kind.Album -> R.drawable.album
        item.kind == AppleHomeItem.Kind.Playlist -> R.drawable.queue_music
        else -> null
    }

    SheetHeader(
        thumbnailUrl = item.artworkUrl?.let { AppleCovers.sized(it, 240) },
        title = item.title,
        subtitle = item.subtitle,
    )

    Spacer(Modifier.height(SheetHeaderGap))

    LazyColumn(
        contentPadding = PaddingValues(
            start = 0.dp,
            top = 0.dp,
            end = 0.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        item {
            Material3MenuGroup(
                items = listOfNotNull(
                    // Cider's order: Start Radio, then the favourite, then where the card goes, then share
                    if (artistId != null) {
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.start_radio)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.radio),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onDismiss()
                                // an artist-only station is that artist's radio already
                                if (item.kind == AppleHomeItem.Kind.Artist) {
                                    scope.launch { onStartRadio(artistId) }
                                } else {
                                    onOpen()
                                }
                            },
                        )
                    } else null,
                    favorite?.let { (type, id) ->
                        Material3MenuItemData(
                            title = {
                                Text(
                                    text = stringResource(
                                        if (favorited == true) R.string.subscribed else R.string.subscribe,
                                    ),
                                )
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(
                                        if (favorited == true) R.drawable.favorite else R.drawable.favorite_border,
                                    ),
                                    contentDescription = null,
                                )
                            },
                            onClick = if (saving) null else ({
                                val target = favorited != true
                                scope.launch {
                                    saving = true
                                    if (AppleFavorites.set(context, type, id, target)) favorited = target
                                    saving = false
                                }
                            }),
                        )
                    },
                    if (openLabel != null && openIcon != null) {
                        Material3MenuItemData(
                            title = { Text(text = stringResource(openLabel)) },
                            icon = {
                                Icon(
                                    painter = painterResource(openIcon),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onDismiss()
                                // a View row goes to the card's own page; for an artist-only station
                                // the card's tap plays it, so the artist page is named by its id
                                if (artistId != null) {
                                    navController.navigate("apple_artist/$artistId")
                                } else {
                                    onOpen()
                                }
                            },
                        )
                    } else null,
                    // a plain station has no page: its card's tap is what plays it
                    if (item.kind == AppleHomeItem.Kind.Station && artistId == null) {
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.play)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.play),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onDismiss()
                                onOpen()
                            },
                        )
                    } else null,
                    shareUrl?.let { url ->
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
                                val intent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, url)
                                }
                                // a phone with no share sheet must not crash on a menu row
                                runCatching { context.startActivity(Intent.createChooser(intent, null)) }
                            },
                        )
                    },
                ),
            )
        }
    }
}
