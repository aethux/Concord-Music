// Concord: new — what a Radio card opens, for the Radio page and a section's own page.
//
// A station is played, not opened. An album or a show opens the page this app already has for it. A
// video plays the way Search's video card does: its YouTube counterpart, because Apple's own video
// stream is not decryptable here (the same rule as `AppleSearchScreen`).
package com.mochyy.mochymusic.ui.screens

import android.content.Context
import android.net.Uri
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.AppleRadioItem
import com.mochyy.mochymusic.playback.PlayerConnection
import com.mochyy.mochymusic.playback.queues.AppleStationQueue
import com.mochyy.mochymusic.playback.queues.StationStreamQueue
import com.mochyy.mochymusic.ui.component.TopToaster
import com.mochyy.mochymusic.ui.player.findOfficialMusicVideoId
import com.mochyy.mochymusic.ui.player.playYouTubeVideo

internal suspend fun openAppleRadioItem(
    item: AppleRadioItem,
    navController: NavController,
    context: Context,
    playerConnection: PlayerConnection,
    stationQueue: suspend (stationId: String) -> AppleStationQueue?,
) {
    when (item.kind) {
        AppleRadioItem.Kind.Station -> when (item.playback) {
            // a station Apple serves as tracks is a queue of songs
            AppleRadioItem.Playback.Tracks -> stationQueue(item.id)?.let { playerConnection.playQueue(it) }
            // one endless stream: the queue names the station, the player asks for its URL
            AppleRadioItem.Playback.Live -> playerConnection.playQueue(
                StationStreamQueue(
                    stationId = item.id,
                    title = item.title,
                    // the card's artwork is a {w}x{h} template; the player needs a real size
                    artworkUrl = AppleCovers.sized(item.artworkUrl, 600),
                    subtitle = context.getString(R.string.radio_live),
                    live = true,
                ),
            )
            // a radio episode: DRM'ed, but finite, so it plays like a song with a real length
            AppleRadioItem.Playback.Episode -> playerConnection.playQueue(
                StationStreamQueue(
                    stationId = item.id,
                    title = item.title,
                    artworkUrl = AppleCovers.sized(item.artworkUrl, 600),
                    subtitle = context.getString(R.string.radio_episode),
                    live = false,
                ),
            )
            // Apple's DRM live channels: a window that never ends, which this app cannot license yet
            AppleRadioItem.Playback.Unsupported ->
                TopToaster.show(context.getString(R.string.radio_live_unavailable))
        }
        AppleRadioItem.Kind.Album -> navController.navigate("album/${item.id}")
        AppleRadioItem.Kind.Artist -> navController.navigate("apple_artist/${item.id}")
        AppleRadioItem.Kind.Playlist -> navController.navigate("apple_playlist/${item.id}")
        AppleRadioItem.Kind.Curator -> navController.navigate(
            "apple_curator/${item.id}?title=${Uri.encode(item.title)}",
        )
        AppleRadioItem.Kind.Video -> {
            val youtubeId = findOfficialMusicVideoId(item.title, item.subtitle.orEmpty())
            if (youtubeId == null) {
                TopToaster.show(context.getString(R.string.apple_video_none))
            } else {
                playYouTubeVideo(playerConnection, youtubeId).onFailure {
                    TopToaster.show(context.getString(R.string.apple_video_unreachable))
                }
            }
        }
    }
}
