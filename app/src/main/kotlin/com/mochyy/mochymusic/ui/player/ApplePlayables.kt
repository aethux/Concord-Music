// Concord: new — the two things every Apple card and row needs to play, in one place.
//
// A catalogue row becomes a playable item under the `AM_` id (how the Apple module finds the
// recording), and a music video plays the way this app plays every video: its YouTube counterpart,
// with Apple's own video stream parked (concord/STATE.md). Both were written inside the Search page
// first; the Browse page needs them too, so they live here and Search calls them.
package com.mochyy.mochymusic.ui.player

import android.content.Context
import androidx.media3.common.MediaItem
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.AppleSearchItem
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.db.entities.SongSource
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.playback.PlayerConnection
import com.mochyy.mochymusic.songedit.SongEdits
import com.mochyy.mochymusic.ui.component.TopToaster

/** A catalogue row as a playable item: `AM_<catalogueId>` is what the Apple module resolves. */
internal fun AppleSearchItem.asMediaItem(): MediaItem {
    val song = Song(
        song = SongEntity(
            id = "AM_$id",
            title = title,
            duration = ((durationMs ?: 0L) / 1000L).toInt(),
            thumbnailUrl = AppleCovers.sized(artworkUrl),
            source = SongSource.Apple,
        ),
        artists = listOf(
            ArtistEntity(
                id = ArtistEntity.generateArtistId(),
                name = subtitle?.takeIf { it.isNotBlank() } ?: "Unknown artist",
            ),
        ),
    )
    return song.toMediaItem()
}

/**
 * Plays one Apple music video, through the YouTube twin that stands in for it.
 *
 * A hand pick wins over the search, and "no video" is honoured, so a card that YouTube cannot find is
 * fixable rather than dead. When nothing resolves, [onNoMatch] is what offers the picker — the Search
 * page passes it, every caller that has no picker leaves it null and gets the toast alone.
 */
internal suspend fun openAppleMusicVideo(
    context: Context,
    videoId: String,
    title: String,
    artist: String?,
    playerConnection: PlayerConnection,
    onNoMatch: (() -> Unit)? = null,
) {
    val mediaId = "AM_$videoId"
    val edit = SongEdits.of(mediaId)
    if (edit?.noVideo == true) {
        TopToaster.show(context.getString(R.string.apple_video_none))
        return
    }
    val youtubeId = edit?.videoId ?: findOfficialMusicVideoId(title, artist.orEmpty())
    if (youtubeId == null) {
        TopToaster.show(context.getString(R.string.apple_video_none))
        onNoMatch?.invoke()
        return
    }
    playYouTubeVideo(playerConnection, youtubeId).onFailure {
        TopToaster.show(context.getString(R.string.apple_video_unreachable))
    }
}
