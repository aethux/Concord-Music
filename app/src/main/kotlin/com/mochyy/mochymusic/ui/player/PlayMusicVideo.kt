// Concord: new. The one way this app plays a music video: a YouTube video, queued with its own
// metadata, with playback pinned to YouTube.
//
// The provider order asks every module first, and Apple answers any song it can match — even a music
// video, with AAC. Pinning YouTube is also what lets the player draw the picture: Mochy treats a row
// whose metadata says it is a video as the video itself, and a YouTube item is the only kind whose
// stream it can show.
package com.mochyy.mochymusic.ui.player

import com.music.innertube.YouTube
import com.music.innertube.models.WatchEndpoint
import com.mochyy.mochymusic.models.toMediaMetadata
import com.mochyy.mochymusic.modules.SourceModules
import com.mochyy.mochymusic.playback.PlayerConnection
import com.mochyy.mochymusic.playback.queues.YouTubeQueue
import kotlinx.coroutines.delay

/**
 * Queues [videoId] and, once it is the item playing, pins the provider to YouTube.
 *
 * The result carries the queued video's media id, or the failure YouTube gave: the caller decides
 * what to say, because "no such video" and "YouTube wouldn't answer" are different sentences.
 */
suspend fun playYouTubeVideo(playerConnection: PlayerConnection, videoId: String): Result<String> {
    val found = YouTube.queue(listOf(videoId)).getOrElse { return Result.failure(it) }
    val item = found.firstOrNull() ?: return Result.failure(IllegalStateException("YouTube has no video $videoId"))
    playerConnection.playQueue(
        YouTubeQueue(
            WatchEndpoint(videoId = item.id),
            item.toMediaMetadata(),
        ),
    )
    // Mochy waits for the new item before forcing; the switch acts on whatever is current
    for (waited in 0 until 12) {
        if (playerConnection.mediaMetadata.value?.id == item.id) {
            playerConnection.service.providerSwitch.force(SourceModules.YouTubeId)
            break
        }
        delay(200)
    }
    return Result.success(item.id)
}
