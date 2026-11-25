// Concord: new — what a Home card opens, for Home and for a shelf's own page.
package com.mochyy.mochymusic.ui.screens

import androidx.navigation.NavController
import com.mochyy.mochymusic.applesync.AppleHomeItem
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.importer.ImportSource
import com.mochyy.mochymusic.playback.queues.AppleStationQueue

/**
 * Routed by the id, not by the shelf's kind: a mood shelf can arrive typed as a station, and the id
 * is what Apple actually promises.
 *
 * A playlist the account already has opens as that playlist — the import's local row — rather than
 * as a second copy of the same list. A station has no page to open, so tapping one starts it, the
 * way Apple's own app does.
 */
internal suspend fun openAppleHomeItem(
    item: AppleHomeItem,
    navController: NavController,
    database: MusicDatabase,
    stationQueue: suspend (stationId: String) -> AppleStationQueue?,
    playStation: (AppleStationQueue) -> Unit,
    openReplay: suspend () -> Unit,
) {
    when {
        item.kind == AppleHomeItem.Kind.Artist -> navController.navigate("apple_artist/${item.id}")
        // Replay has a page of its own (music-summaries); anything else Apple serves itself — the
        // concerts card, which is Apple's location prompt — says so in the app, not in a browser
        item.kind == AppleHomeItem.Kind.Editorial -> {
            if (item.url?.contains("replay", ignoreCase = true) == true) {
                openReplay()
            } else {
                navController.navigate("coming_soon/${android.net.Uri.encode(item.title)}")
            }
        }
        item.id.startsWith("pl.") || item.id.startsWith("p.") -> {
            val local = runCatching {
                database.importedPlaylistByRemoteId(ImportSource.APPLE_MUSIC.key, item.id)
            }.getOrNull()
            if (local != null) {
                navController.navigate("local_playlist/${local.playlistId}")
            } else {
                navController.navigate("apple_playlist/${item.id}")
            }
        }
        item.id.startsWith("ra.") || item.id.startsWith("AM_STN") -> {
            stationQueue(item.id)?.let(playStation)
        }
        else -> navController.navigate("album/${item.id}")
    }
}
