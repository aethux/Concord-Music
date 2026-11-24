// Concord: copied from Mocha applesync/AppleAlbums.kt (apple-sync@c90b8251). Package renamed only.
// Apple's albums, written into Mocha's own album table so the Albums shelf is not empty for music
// that came from Apple.
//
// A song reaches the shelf two ways and both are needed: `song.albumId` is what the shelf's query
// looks at ("albums with a song in the library"), and a `song_album_map` row is what the album's own
// page lists. Writing one without the other is an album that appears and then opens empty.
//
// Everything here is idempotent: a re-import refreshes the count, the year and the artwork it owns,
// and leaves a bookmarked album, a chosen cover and the song rows themselves alone.
//
// Done as one pass rather than one transaction per album. Per album it used to be a relation query
// (`album(id)`, which loads the album's songs), a cover read, an artist read and a row read per
// song; 918 albums made that minutes of work. Now it is three reads for the whole set, and the
// writing happens in batches.

package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.applesync.db.AppleStateEntity
import com.mochyy.mochymusic.applesync.db.AppleSyncDao
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.entities.AlbumArtistMap
import com.mochyy.mochymusic.db.entities.AlbumEntity
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.SongAlbumMap
import java.time.LocalDateTime

object AppleAlbums {

    /** Apple's albums have their own id space: a catalogue album id is not a song's. */
    const val PREFIX = "AM_ALB_"

    fun id(remoteAlbumId: String): String = "$PREFIX$remoteAlbumId"

    /**
     * The Apple id behind an album id the app may be showing: an imported Apple album is
     * `AM_ALB_<apple id>`, a recommendation card carries the bare id, and anything else (`MPREb_…`,
     * a local album) is Mochy's own and not Apple's to read.
     *
     * The id inside is not always a catalogue id: Apple's library albums are `l.…` (that is what the
     * import stored, because a song's `relationships.albums` row carries its library id), and those
     * have to be resolved to their catalogue twin before a catalogue read. Cider does exactly this
     * in its album page, and `/v1/catalog/{sf}/albums/l.…` answers 404.
     */
    fun remoteIdOf(albumId: String): String? = when {
        albumId.startsWith(PREFIX) -> albumId.removePrefix(PREFIX).takeIf { it.isNotBlank() }
        albumId.isNotEmpty() && albumId.all { it.isDigit() } -> albumId
        albumId.startsWith("l.") -> albumId
        else -> null
    }


    data class Result(val albums: Int, val songs: Int)

    /** Albums per transaction: big enough that the fixed cost disappears, small enough to stay bounded. */
    private const val Batch = 200

    /**
     * Apple hands out one artwork template with {w}x{h} in it; 300 is the size the app's lists ask
     * for, and asking for more only to scale it down is the slowest option there is.
     */
    private fun artwork(template: String?): String? =
        template?.replace("{w}", "300")?.replace("{h}", "300")

    /**
     * Concord: an upload album's cover is a signed blobstore URL that lives a day, so every read
     * hands over the next one — the same rule the songs follow, and the reason an album tile used
     * to go blank a day after its first import. Anything permanent (a catalogue `mzstatic` template,
     * or a picture the person chose) is left exactly as it is.
     */
    private fun renewed(stored: String?, fresh: String?): String? = when {
        fresh == null -> stored
        stored == null -> fresh
        stored.contains("blobstore", true) || stored.contains("X-Amz", true) -> fresh
        else -> stored
    }

    /**
     * The signed covers only, renewed on every read: one row read, then one conditional update per
     * album that can go stale. The SQL keeps the promise the full pass makes — a chosen picture or a
     * permanent catalogue template never matches, so it is never overwritten.
     */
    private suspend fun renewSignedCovers(
        database: MusicDatabase,
        songs: List<RemoteSong>,
    ) {
        val stored = runCatching { database.appleSongDao.appleAlbums() }.getOrNull() ?: return
        val signed = stored.filter { album ->
            album.thumbnailUrl?.let { url ->
                url.contains("blobstore", true) || url.contains("X-Amz", true)
            } == true
        }
        if (signed.isEmpty()) return
        // Read off every song, not the grouped list the album pass uses: that one keeps catalogue
        // rows only, so an upload album (no catalogId) would never be handed its fresh cover.
        val fresh = songs.mapNotNull { song ->
            val album = song.albumId ?: return@mapNotNull null
            val url = artwork(song.albumArtworkUrl) ?: return@mapNotNull null
            id(album) to url
        }.toMap()
        if (fresh.isEmpty()) return
        runCatching {
            database.withTransaction {
                signed.forEach { album ->
                    database.appleSongDao.renewSignedAlbumCover(album.id, fresh[album.id] ?: return@forEach)
                }
            }
        }
    }

    /**
     * Writes an album row per album these songs belong to, and links each song to it.
     *
     * Called after the songs are in the database: the link tables carry foreign keys, so an album
     * cannot be pointed at a song row that does not exist yet.
     */
    suspend fun import(
        database: MusicDatabase,
        dao: AppleSyncDao,
        songs: List<RemoteSong>,
    ): Result {
        val groups = songs
            .mapNotNull { song ->
                val album = song.albumId ?: return@mapNotNull null
                val catalogId = song.catalogId ?: return@mapNotNull null
                Triple("AM_$catalogId", id(album), song)
            }
            .groupBy { it.second }

        if (groups.isEmpty()) return Result(0, 0)

        // Concord: an upload album's cover is a signed blobstore URL that dies a day after the read
        // that wrote it, and the pass below is skipped whenever the album shape is unchanged — which
        // is the normal case on a refresh. So the signed covers are renewed here, on every read.
        renewSignedCovers(database, songs)

        // The pass rewrites every album row and every song→album link. When neither the albums nor
        // which song is on which has changed - the usual case on a refresh - that is a minute of
        // writes for nothing, so a stamp of the resulting shape decides whether to run it at all.
        // It is a digest: the shape itself is 44 KB on a 917-album library, and only equality matters.
        val signature = java.security.MessageDigest.getInstance("SHA-256")
            .digest(
                groups.entries
                    .flatMap { (albumId, entries) -> entries.map { "$albumId:${it.first}" } }
                    .sorted()
                    .joinToString(",")
                    .toByteArray(),
            )
            .joinToString("") { "%02x".format(it) }
            .take(32)
        if (runCatching { dao.state(SIGNATURE_KEY) }.getOrNull() == signature) return Result(0, 0)

        // Three reads for the whole pass, not one per album and one per song. A failed read is a
        // failed read: it is allowed to fail the import rather than pass for "no albums yet".
        val existing = database.appleSongDao.appleAlbums().associateBy { it.id }
        val linked = database.appleSongDao.appleAlbumLinks().associate { it.id to it.albumId }
        val covers = runCatching { dao.coverOverrides() }.getOrNull()
            ?.associate { it.key to it.value }
            ?: emptyMap()

        val artistIds = HashMap<String, String>()
        var albums = 0
        var songsLinked = 0

        groups.entries.chunked(Batch).forEach { batch ->
            database.transaction {
                batch.forEach { (albumId, entries) ->
                    val first = entries.first().third
                    val title = first.album ?: "Unknown album"
                    // a copy the person chose is the cover; Apple's is the one that fills in behind it
                    val chosen = covers[CoverOverrides.key(CoverOverrides.Kind.ALBUM, albumId)]
                    val current = existing[albumId]

                    val row = (current ?: AlbumEntity(id = albumId, title = title, songCount = 0, duration = 0)).copy(
                        title = title.ifBlank { current?.title ?: "Unknown album" },
                        year = first.albumYear ?: current?.year,
                        // Apple's artwork only when nothing was chosen; a signed one is renewed by
                        // this read instead of being kept until its signature dies
                        thumbnailUrl = chosen ?: renewed(current?.thumbnailUrl, artwork(first.albumArtworkUrl)),
                        // Apple knows the whole album; the library may only hold a few of its songs
                        songCount = maxOf(first.albumTrackCount ?: 0, entries.size),
                        duration = entries.sumOf { (it.third.durationMs ?: 0L) / 1000L }.toInt(),
                        lastUpdateTime = LocalDateTime.now(),
                    )

                    // insert is IGNORE: an album that is already there keeps its bookmarks, and
                    // update then fills in what Apple knows
                    insert(row)
                    update(row)

                    val artistName = first.artist.ifBlank { "Unknown artist" }
                    val artistId = artistIds.getOrPut(artistName) {
                        database.artistByName(artistName)?.id ?: ArtistEntity.generateArtistId()
                    }
                    insert(ArtistEntity(id = artistId, name = artistName))
                    insert(AlbumArtistMap(albumId = albumId, artistId = artistId, order = 0))

                    entries.forEachIndexed { position, entry ->
                        insert(
                            SongAlbumMap(
                                songId = entry.first,
                                albumId = albumId,
                                // Apple's own track number when it has one, so the album page's
                                // order is the album's order and not the library's
                                index = entry.third.trackNumber ?: position + 1,
                            ),
                        )
                        // the shelf looks at the song, not the map; only written when it changed
                        if (linked[entry.first] != albumId) setSongAlbum(entry.first, albumId)
                    }

                    albums++
                    songsLinked += entries.size
                }
            }
        }

        runCatching { dao.saveState(AppleStateEntity(key = SIGNATURE_KEY, value = signature)) }
        return Result(albums = albums, songs = songsLinked)
    }

    /** The albums and their songs, as one string: equal means the pass has nothing to write. */
    private const val SIGNATURE_KEY = "album_signature"
}
