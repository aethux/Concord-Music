// Concord: new — an Apple station, which is a queue and not a page.
//
// A station's songs are not a list Apple will hand over: each page comes from
// `/v1/me/stations/next-tracks/{id}`, so the queue asks again whenever the player runs dry and never
// says it is finished — until Apple hands back an empty page, which is the only end it has.
package com.mochyy.mochymusic.playback.queues

import androidx.media3.common.MediaItem
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.AppleStation
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.db.entities.SongSource
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppleStationQueue(
    private val client: AppleClient,
    private val stationId: String,
    private val station: AppleStation? = null,
    private val pageSize: Int = 10,
) : Queue {

    override val preloadItem: MediaMetadata? = null

    private var exhausted = false

    override suspend fun getInitialStatus(): Queue.Status = withContext(Dispatchers.IO) {
        val items = readPage()
        Queue.Status(
            title = station?.name ?: "Station",
            items = items,
            mediaItemIndex = 0,
        )
    }

    override fun hasNextPage(): Boolean = !exhausted

    override suspend fun nextPage(): List<MediaItem> = withContext(Dispatchers.IO) { readPage() }

    private suspend fun readPage(): List<MediaItem> {
        val songs = runCatching { client.stationNextTracks(stationId, pageSize) }.getOrDefault(emptyList())
        if (songs.isEmpty()) {
            exhausted = true
            return emptyList()
        }
        return songs.mapNotNull { song ->
            val key = song.catalogId ?: song.libraryId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artistName = song.artist.ifBlank { "Unknown artist" }
            Song(
                song = SongEntity(
                    id = "AM_$key",
                    title = song.title,
                    duration = ((song.durationMs ?: 0L) / 1000L).toInt(),
                    thumbnailUrl = AppleCovers.sized(song.artworkUrl ?: station?.artworkUrl),
                    albumName = song.album,
                    source = SongSource.Apple,
                ),
                artists = listOf(ArtistEntity(id = artistName, name = artistName)),
            ).toMediaItem()
        }
    }
}
