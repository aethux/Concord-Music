// Concord: new — a station Apple streams: one item, no pages, and either live or a finite episode.
//
// The player asks the service for this item's stream like any other (the media id names the station),
// and the service answers from `/v1/play/assets`. There is no queue beyond the one item: neither a
// live station nor a radio episode has a "next song" this app can name.
package com.mochyy.mochymusic.playback.queues

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata as PlayerMetadata
import com.mochyy.mochymusic.models.MediaMetadata

class StationStreamQueue(
    private val stationId: String,
    private val title: String,
    private val artworkUrl: String?,
    /** the player's second line: "Live radio", "Radio episode" */
    private val subtitle: String,
    /** a live channel has no length and no seek; a finite episode plays like a song */
    private val live: Boolean,
) : Queue {

    override val preloadItem: MediaMetadata? = null

    private val mediaId = (if (live) LiveMediaIdPrefix else EpisodeMediaIdPrefix) + stationId

    override suspend fun getInitialStatus(): Queue.Status =
        Queue.Status(title = title, items = listOf(mediaItem()), mediaItemIndex = 0)

    override fun hasNextPage(): Boolean = false

    override suspend fun nextPage(): List<MediaItem> = emptyList()

    private fun mediaItem(): MediaItem {
        val metadata = MediaMetadata(
            id = mediaId,
            title = title,
            artists = listOf(MediaMetadata.Artist(null, subtitle)),
            duration = 0,
            thumbnailUrl = artworkUrl,
        )
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(mediaId)
            .setTag(metadata)
            .setMediaMetadata(
                PlayerMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setArtworkUri(artworkUrl?.let(Uri::parse))
                    .setDisplayTitle(title)
                    .setMediaType(PlayerMetadata.MEDIA_TYPE_MUSIC)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
    }

    companion object {
        /** a live channel: the player draws the LIVE marker instead of a rail */
        const val LiveMediaIdPrefix = "LIVE_"

        /** a radio episode: DRM, but with a real length, so it seeks like a song */
        const val EpisodeMediaIdPrefix = "STREAM_"

        /**
         * The custom cache key the service gives this stream. A live stream must never be written to
         * a cache, so the playback chain reads this marker and skips the caches; an episode is not
         * cached either for now (its segments rotate per session).
         */
        const val CacheKeyPrefix = "RADIO|"

        fun stationIdOf(mediaId: String): String? = when {
            mediaId.startsWith(LiveMediaIdPrefix) -> mediaId.removePrefix(LiveMediaIdPrefix)
            mediaId.startsWith(EpisodeMediaIdPrefix) -> mediaId.removePrefix(EpisodeMediaIdPrefix)
            else -> null
        }

        fun isLive(mediaId: String): Boolean = mediaId.startsWith(LiveMediaIdPrefix)
    }
}
