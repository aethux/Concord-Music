// Concord: new — a Radio card's long press, for every kind the page can draw.
//
// The rows follow Cider's own media-item menu, in the order this app already gives Apple's cards on
// Home (`AppleItemMenu`): what the card plays or opens, then where it goes, then its favourite, then
// Share. What differs is what a card can honestly be given:
//
//  * a station plays; its favourite is this phone's (Apple cannot hold one — `RadioFavorites`);
//  * an album opens its page, knows its artist (`include[albums]=artists`), and has an Apple
//    favourite (`albums`, read through `inFavorites`);
//  * an artist opens its page and starts its seeded radio, and has an Apple favourite (`artists`);
//  * a playlist opens its page and has an Apple favourite (`playlists`);
//  * a show (curator) opens its page, and has no favourite anywhere;
//  * a video plays the way its tap does (the YouTube route), with nothing to favourite.
//
// Cider also offers Play Next / Play Later and Add to Playlist. Neither has a path in this app for
// Apple content yet, so they are not drawn rather than drawn as rows that cannot keep their promise.
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
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.AppleFavorites
import com.mochyy.mochymusic.applesync.AppleRadioItem
import com.mochyy.mochymusic.ui.component.Material3MenuGroup
import com.mochyy.mochymusic.ui.component.Material3MenuItemData
import com.mochyy.mochymusic.ui.component.SheetHeader
import com.mochyy.mochymusic.ui.component.SheetHeaderGap
import kotlinx.coroutines.launch

/** Apple's resource type and id for this card's favourite, when it has one */
private fun appleFavoriteTarget(item: AppleRadioItem): Pair<String, String>? = when (item.kind) {
    AppleRadioItem.Kind.Album -> "albums" to item.id
    AppleRadioItem.Kind.Artist -> "artists" to item.id
    AppleRadioItem.Kind.Playlist ->
        (if (item.id.startsWith("p.")) "library-playlists" else "playlists") to item.id
    else -> null
}

@Composable
fun RadioItemMenu(
    item: AppleRadioItem,
    /** a station's flag, from the list this phone keeps */
    favorited: Boolean,
    /** what the card's tap does: play a station or a video, open an album, a show, an artist or a playlist */
    onPrimary: () -> Unit,
    onToggleStationFavorite: () -> Unit,
    /** open an artist's page — the album's own artist, never the artist card itself */
    onViewArtist: (artistId: String) -> Unit,
    /** start an artist's seeded radio */
    onStartRadio: (artistId: String) -> Unit,
    /** Apple's `inFavorites` for a catalogue object, read when the sheet opens */
    readFavorite: suspend (type: String, id: String) -> Boolean?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appleFavorite = remember(item.id) { appleFavoriteTarget(item) }
    var appleFavorited by remember(item.id) { mutableStateOf<Boolean?>(null) }
    var saving by remember(item.id) { mutableStateOf(false) }

    // an Apple favourite has no read of its own: ask the resource, and draw what it answers
    LaunchedEffect(item.id, appleFavorite) {
        appleFavorite?.let { (type, id) -> appleFavorited = readFavorite(type, id) }
    }

    SheetHeader(
        thumbnailUrl = item.artworkUrl?.let { AppleCovers.sized(it, 240) },
        title = item.title,
        subtitle = item.subtitle,
    )

    Spacer(Modifier.height(SheetHeaderGap))

    // an artist's tap opens its page, so its first row is the radio it seeds; every other card's tap
    // is named by what it opens or plays
    val primaryLabel = when (item.kind) {
        AppleRadioItem.Kind.Album -> R.string.view_album
        AppleRadioItem.Kind.Curator -> R.string.view_show
        AppleRadioItem.Kind.Playlist -> R.string.view_playlist
        AppleRadioItem.Kind.Artist -> R.string.start_radio
        else -> R.string.play
    }
    val primaryIcon = when (item.kind) {
        AppleRadioItem.Kind.Album -> R.drawable.album
        AppleRadioItem.Kind.Curator, AppleRadioItem.Kind.Artist -> R.drawable.radio
        AppleRadioItem.Kind.Playlist -> R.drawable.queue_music
        else -> R.drawable.play
    }
    // only an album's relationship names an artist this menu can open; an artist card's tap does
    val viewArtistId = item.artistId.takeIf { item.kind == AppleRadioItem.Kind.Album }

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
                    Material3MenuItemData(
                        title = { Text(text = stringResource(primaryLabel)) },
                        icon = {
                            Icon(
                                painter = painterResource(primaryIcon),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            onDismiss()
                            if (item.kind == AppleRadioItem.Kind.Artist) {
                                onStartRadio(item.id)
                            } else {
                                onPrimary()
                            }
                        },
                    ),
                    viewArtistId?.let { id ->
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.view_artist)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.artist),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onDismiss()
                                onViewArtist(id)
                            },
                        )
                    },
                    // a station keeps its favourite here; everything else keeps its in the account
                    when {
                        item.kind == AppleRadioItem.Kind.Station -> Material3MenuItemData(
                            title = {
                                Text(
                                    text = stringResource(
                                        if (favorited) R.string.unfavourite else R.string.favourite,
                                    ),
                                )
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(
                                        if (favorited) R.drawable.favorite else R.drawable.favorite_border,
                                    ),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onDismiss()
                                onToggleStationFavorite()
                            },
                        )

                        appleFavorite != null -> Material3MenuItemData(
                            title = {
                                Text(
                                    text = stringResource(
                                        if (appleFavorited == true) R.string.unfavourite else R.string.favourite,
                                    ),
                                )
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(
                                        if (appleFavorited == true) R.drawable.favorite else R.drawable.favorite_border,
                                    ),
                                    contentDescription = null,
                                )
                            },
                            onClick = if (saving) null else ({
                                val (type, id) = appleFavorite
                                val target = appleFavorited != true
                                scope.launch {
                                    saving = true
                                    if (AppleFavorites.set(context, type, id, target)) {
                                        appleFavorited = target
                                    }
                                    saving = false
                                }
                            }),
                        )

                        else -> null
                    },
                    item.url?.let { url ->
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
