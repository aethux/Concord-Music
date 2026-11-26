// Concord: new — a pull on one playlist re-reads that playlist from Apple and nothing else.
//
// Cider's refresh belongs to the page: pulling on a playlist re-reads that playlist, not the
// library. The local page is the one people open for these playlists, so its content is Mochy's
// playlist rows; this reads Apple's copy of *that* playlist and rewrites its tracks in Apple's order,
// adding songs the phone has never seen and dropping the ones Apple no longer has. It touches no
// other playlist, no likes and no library membership.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.PlaylistSongMap
import com.mochyy.mochymusic.db.entities.SongArtistMap
import com.mochyy.mochymusic.db.entities.SongEntity
import kotlinx.coroutines.flow.first
import com.mochyy.mochymusic.db.entities.SongSource

class ApplePlaylistRefresh(
    private val database: MusicDatabase,
    private val tokens: AppleTokens,
    private val pageCache: ApplePageCache,
) {

    data class Result(val tracks: Int, val added: Int, val removed: Int)

    /**
     * [remoteId] is the playlist's Apple id: the library one (`p.…`) for an imported playlist, the
     * catalogue one (`pl.…`) for a playlist opened from Apple's own pages.
     */
    suspend fun run(playlistId: String, remoteId: String): Result {
        val pair = tokens.read() ?: throw AppleSyncException("Sign in to Apple Music first.")
        val client = AppleClient(pair, pageCache)

        // the page cache would answer with the copy the page already drew, so the row goes first
        runCatching {
            pageCache.invalidate(PageKind.PLAYLIST, listOf(remoteId, remoteId.removePrefix("l.")))
        }
        val page = client.playlistPage(remoteId)
            ?: throw AppleSyncException("Apple didn't answer for this playlist.")

        val rows = page.songs.mapNotNull { song ->
            val key = song.catalogId ?: song.libraryId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            key to song
        }
        if (rows.isEmpty()) return Result(0, 0, 0)

        val before = runCatching {
            database.playlistSongs(playlistId).first().map { it.map.songId }.toSet()
        }.getOrDefault(emptySet())

        var added = 0
        database.withTransaction {
            rows.forEach { (key, song) ->
                val songId = "AM_$key"
                if (database.getSongById(songId) != null) return@forEach
                val artistName = song.artist.ifBlank { "Unknown artist" }
                val artistId = database.artistByName(artistName)?.id ?: ArtistEntity.generateArtistId()
                database.insert(
                    SongEntity(
                        id = songId,
                        title = song.title,
                        duration = ((song.durationMs ?: 0L) / 1000L).toInt(),
                        thumbnailUrl = AppleCovers.sized(song.artworkUrl),
                        albumName = song.album,
                        liked = false,
                        source = SongSource.Apple,
                    ),
                )
                database.insert(ArtistEntity(id = artistId, name = artistName, thumbnailUrl = null))
                database.insert(SongArtistMap(songId = songId, artistId = artistId, position = 0))
                added++
            }

            // Apple's order is the playlist's order, and a track it no longer has is gone
            database.clearPlaylist(playlistId)
            rows.forEachIndexed { index, (key, _) ->
                database.insert(
                    PlaylistSongMap(playlistId = playlistId, songId = "AM_$key", position = index),
                )
            }
        }

        val after = rows.map { (key, _) -> "AM_$key" }.toSet()
        return Result(
            tracks = after.size,
            added = added,
            removed = before.count { it !in after },
        )
    }
}
