// Concord: copied from Mocha applesync/AppleLibraryPull.kt (apple-sync@c90b8251). Package renamed only.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.applesync.db.AppleLibrarySongEntity
import com.mochyy.mochymusic.applesync.db.ApplePlaylistEntity
import com.mochyy.mochymusic.applesync.db.AppleSyncDao

/**
 * Reads the Apple library — songs, favourites and playlists — into the replica.
 *
 * One direction and no writes to Apple, so it is safe to repeat: both replicas are replaced wholesale
 * rather than merged, and a song removed on Apple disappears here on the next pull instead of
 * lingering as a ghost.
 *
 * Progress goes through [AppleSyncStatus] rather than a callback, because the library list, the
 * settings page and the refresh button all show it and would otherwise each keep their own counter.
 */
class AppleLibraryPull(
    private val dao: AppleSyncDao,
    private val tokens: AppleTokens,
    /**
     * A light read is the pull gesture's: the songs and their favourites, and nothing else. The
     * playlists are 25 more requests and their rows are not touched by a refresh of the list itself,
     * the way Cider's library refresh leaves the playlist pages to their own cache.
     */
    private val light: Boolean = false,
) {

    data class Result(
        val songs: Int,
        val playlists: Int,
        val remote: List<RemoteSong>,
        /** catalogue id -> Apple's rating, read here so the import does not read it again */
        val rated: Map<String, Long> = emptyMap(),
    )

    suspend fun run(): Result {
        AppleSyncStatus.start("Reading your Apple Music library…")
        try {
            val pair = tokens.read() ?: throw AppleSyncException("Sign in to Apple Music first.")
            val client = AppleClient(pair)

            val songs = client.librarySongs { read ->
                AppleSyncStatus.update(phase = "Reading your library…", songs = read, totalSongs = 0)
            }
            // diagnostic: how many rows actually carried Apple's artist relationship this read
            timber.log.Timber.d(
                "ApplePull: %d songs, %d with artists, %d with more than one",
                songs.size,
                songs.count { it.artists.isNotEmpty() },
                songs.count { it.artists.size > 1 },
            )
            AppleSyncStatus.update(phase = "Reading your favourites…", songs = songs.size, totalSongs = songs.size)

            // favourites are a rating, and they come in one call per hundred songs
            val rated = runCatching { client.ratings(songs.mapNotNull { it.catalogId }) }
                .getOrDefault(emptyMap())

            val now = System.currentTimeMillis()
            val rows = songs.mapNotNull { song ->
                // Concord: an upload has no catalogue id. Apple's own app addresses it by its library
                // id (`i.…`) and `cider/uploads.md` says the same, so that id is the row's key — the
                // song reaches the library instead of being dropped here.
                val catalogId = song.catalogId ?: song.libraryId
                AppleLibrarySongEntity(
                    catalogId = catalogId,
                    libraryId = song.libraryId,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                    isrc = song.isrc,
                    addedAt = null,
                    pulledAt = now,
                    // a favourite is a catalogue rating; an upload cannot be one
                    rated = song.catalogId?.let { rated[it] == 1L } ?: false,
                )
            }
            AppleSyncStatus.update(
                phase = "Saving ${rows.size} songs…",
                songs = rows.size,
                totalSongs = songs.size,
            )
            dao.replaceLibrarySongs(rows)

            AppleSyncStatus.update(phase = "Reading your playlists…", songs = rows.size, totalSongs = rows.size)
            val playlists = if (light) {
                emptyList()
            } else {
                client.playlists { read ->
                    AppleSyncStatus.update(
                        phase = "Reading your playlists…",
                        songs = rows.size,
                        playlists = read,
                        totalSongs = rows.size,
                    )
                }
            }
            AppleSyncStatus.update(
                phase = "Saving ${playlists.size} playlists…",
                songs = rows.size,
                playlists = playlists.size,
                totalSongs = rows.size,
                totalPlaylists = playlists.size,
            )
            if (playlists.isNotEmpty()) {
                dao.replacePlaylists(
                    playlists.map {
                        ApplePlaylistEntity(
                            remoteId = it.id,
                            name = it.name,
                            trackCount = 0,
                            canEdit = it.canEdit,
                            pulledAt = now,
                        )
                    },
                )
            }

            AppleSyncStatus.setSynced(dao.syncedSongIds().toSet())
            // the songs go back to the caller: the import needs the same list, and reading it twice
            // is what looked like a sync that finished and then started over
            return Result(songs = rows.size, playlists = playlists.size, remote = songs, rated = rated)
        } catch (e: Exception) {
            AppleSyncStatus.fail(e.message ?: "Couldn't reach Apple Music")
            throw e
        }
    }
}
