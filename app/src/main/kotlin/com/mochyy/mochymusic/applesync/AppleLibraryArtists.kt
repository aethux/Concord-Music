// Concord: new — the library's artists keyed by Apple's own ids, the way Cider keeps them.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.entities.ArtistEntity

/**
 * Writes `/v1/me/library/artists` into the `artist` table and re-keys what was already there.
 *
 * Until this, an artist row was invented from a song's `artistName` with a generated `LA…` id.
 * Apple's list is keyed by `r.…`, and that is the only id its `?include=catalog` read accepts, so
 * each row is matched by name: the links move to Apple's id, the old row is dropped, and a
 * bookmark, a chosen picture and the local flag come with it. Idempotent — a second run over the
 * same list changes nothing.
 *
 * A YouTube channel row is left alone on purpose: its songs play through that channel, and folding
 * it into Apple's id would take them with it. Mochy keeps one artist row per name, so no Apple twin
 * is inserted beside it; `ArtistRoute` still finds the catalogue artist by name for those rows.
 */
object AppleLibraryArtists {

    data class Result(val artists: Int, val rekeyed: Int)

    fun run(database: MusicDatabase, remote: List<RemoteLibraryArtist>): Result {
        var written = 0
        var rekeyed = 0
        database.transaction {
            remote.forEach { artist ->
                if (artist.id.isBlank() || artist.name.isBlank()) return@forEach
                val existing = database.artistByName(artist.name)
                when {
                    existing == null -> {
                        database.insert(
                            ArtistEntity(
                                id = artist.id,
                                name = artist.name,
                                thumbnailUrl = artist.artworkUrl,
                            ),
                        )
                        written++
                    }

                    existing.id == artist.id -> {
                        // the list is the only place an Apple artist can pick up a picture
                        if (existing.thumbnailUrl.isNullOrBlank() && !artist.artworkUrl.isNullOrBlank()) {
                            database.update(existing.copy(thumbnailUrl = artist.artworkUrl))
                            written++
                        }
                    }

                    existing.isYouTubeArtist -> Unit

                    // Concord: the row already is the catalogue artist, which is the id Apple's artist
                    // page and picture answer to. Re-keying it onto the library's grouping id would
                    // trade that for nothing, and the artist merge would only move it back.
                    existing.id.all(Char::isDigit) -> Unit

                    else -> {
                        database.rekey(existing, artist)
                        rekeyed++
                    }
                }
            }
        }
        return Result(remote.size, rekeyed)
    }

    /** One artist row moved from a generated id onto Apple's, links included. */
    private fun MusicDatabase.rekey(existing: ArtistEntity, artist: RemoteLibraryArtist) {
        insert(
            existing.copy(
                id = artist.id,
                name = artist.name,
                thumbnailUrl = existing.thumbnailUrl ?: artist.artworkUrl,
            ),
        )
        // move what can move, then drop the leftovers a primary-key clash held back
        moveSongArtistLinks(from = existing.id, to = artist.id)
        deleteSongArtistLinks(existing.id)
        moveAlbumArtistLinks(from = existing.id, to = artist.id)
        deleteAlbumArtistLinks(existing.id)
        speedDialDao.moveArtistPin(from = existing.id, to = artist.id)
        deleteArtistRow(existing.id)
    }
}
