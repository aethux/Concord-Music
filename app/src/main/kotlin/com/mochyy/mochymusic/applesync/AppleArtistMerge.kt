// Concord: new. One act, one artist row.
//
// Three writers made artist rows for the same name and never met, because every lookup in this app is
// by exact name: the Apple library list (`r.…`), a song's catalogue artist (`1234…`), and the older
// YouTube import (`UC…`, or a generated `LA…`, or a bare name id). "Conor Maynard" ended up as three
// rows — 32 songs on one, 55 on another — and his artist page showed whichever one was tapped.
//
// This pass folds rows whose names are the same once letters and digits are compared (so `K‐391`
// matches `K-391`, `Talia  Lahoud` matches `Talia Lahoud`, `Sigur Rós` matches `Sigur Ros`). The
// survivor is the catalogue artist when a group has one: that is the id Apple's artist page and
// picture answer to. Links, album credits, a pinned speed-dial entry, a chosen picture and a bookmark
// move across before the other rows are dropped.
//
// A group with two catalogue ids is left alone. Two acts share a name often enough ("Mochi", "Kado"),
// and a merge cannot be undone from inside the app.

package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.entities.ArtistEntity
import java.text.Normalizer

object AppleArtistMerge {

    data class Result(
        /** Name groups that had more than one row. */
        val groups: Int,
        /** Rows folded into their group's survivor. */
        val merged: Int,
        /** Groups left alone because two catalogue artists share the name. */
        val skipped: Int,
    )

    /** Groups per run. A library this size has a few hundred; the cap only bounds the first pass. */
    private const val Limit = 500

    fun run(database: MusicDatabase): Result {
        val artists = runCatching { database.artistsOnce() }.getOrDefault(emptyList())
        if (artists.isEmpty()) return Result(0, 0, 0)
        val groups = artists
            .groupBy { artistNameKey(it.name) }
            .filter { (key, rows) -> key.isNotEmpty() && rows.size > 1 }
        var merged = 0
        var skipped = 0
        runCatching {
            database.transaction {
                groups.values.take(Limit).forEach { rows ->
                    val survivor = pickSurvivor(rows)
                    if (survivor == null) {
                        skipped++
                        return@forEach
                    }
                    val rest = rows.filter { it.id != survivor.id }
                    if (rest.isEmpty()) return@forEach
                    // what only one of the rows has comes with the songs
                    val keep = survivor.copy(
                        thumbnailUrl = survivor.thumbnailUrl ?: rest.firstNotNullOfOrNull { it.thumbnailUrl },
                        bookmarkedAt = survivor.bookmarkedAt ?: rest.firstNotNullOfOrNull { it.bookmarkedAt },
                        isLocal = rows.any { it.isLocal },
                    )
                    if (keep != survivor) update(keep)
                    rest.forEach { other ->
                        moveSongArtistLinks(from = other.id, to = keep.id)
                        deleteSongArtistLinks(other.id)
                        moveAlbumArtistLinks(from = other.id, to = keep.id)
                        deleteAlbumArtistLinks(other.id)
                        speedDialDao.moveArtistPin(from = other.id, to = keep.id)
                        deleteArtistRow(other.id)
                    }
                    merged += rest.size
                }
            }
        }.onFailure { timber.log.Timber.e(it, "merging duplicate artists stopped part way") }
        timber.log.Timber.i(
            "artists: %d rows merged, %d name groups, %d groups with two catalogue artists left alone",
            merged,
            groups.size,
            skipped,
        )
        return Result(groups.size, merged, skipped)
    }

    /** The row a group keeps, or null when the group may be two different acts. */
    internal fun pickSurvivor(rows: List<ArtistEntity>): ArtistEntity? {
        if (rows.count { it.id.all(Char::isDigit) } > 1) return null
        return rows.minWithOrNull(SurvivorOrder)
    }

    private val SurvivorOrder = compareBy<ArtistEntity>(
        // the catalogue artist first: Apple's artist page and artwork are keyed on it
        { when { it.id.all(Char::isDigit) -> 0; it.id.startsWith("r.") -> 1; it.isYouTubeArtist -> 3; else -> 2 } },
        // then whichever already has a picture, so a merge does not blank a face
        { if (it.thumbnailUrl.isNullOrBlank()) 1 else 0 },
        { it.id },
    )
}

/** A name as the row-comparison sees it: accents folded, everything but letters and digits dropped. */
internal fun artistNameKey(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFKD)
    .replace(Marks, "")
    .lowercase()
    .filter { it.isLetterOrDigit() }

private val Marks = Regex("\\p{Mn}+")
