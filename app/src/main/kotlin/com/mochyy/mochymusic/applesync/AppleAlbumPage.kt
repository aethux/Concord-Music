// Concord: new — one Apple album as its page needs it, read the way Cider's /am/album/:id does.
//
// Cider's album page always ends at the catalogue resource: a library id is resolved to its catalogue
// twin first (`/v1/me/library/albums/{id}?include=catalog`), the page itself is the catalogue album,
// and the tracklist is the album's own `/tracks` list. Concord's imported albums already carry the
// catalogue id (`AM_ALB_<catalogId>`), so the resolve step is the id itself.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.long
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import kotlinx.serialization.json.JsonObject

/** An album's page: Apple's own attributes, plus the tracklist read separately. */
data class AppleAlbumPage(
    val id: String,
    val title: String,
    val artistName: String,
    /** The artist's catalogue id, when Apple sends one; null keeps the creator line untappable. */
    val artistId: String?,
    val year: Int?,
    val explicit: Boolean,
    /** Apple's artwork template with {w}x{h} in it; null when the album has none. */
    val artworkUrl: String?,
    /** `editorialNotes.standard`, falling back to `.short`; null when Apple wrote none. */
    val description: String?,
    val songs: List<RemoteSong> = emptyList(),
    /**
     * True when Apple gave this library album no catalogue twin, so `songs` is only what the
     * library holds from it — not the release. The page says so instead of looking short.
     */
    val libraryOnly: Boolean = false,
    /**
     * Whether the library holds this album: `inLibrary` on the catalogue resource, asked for the way
     * Cider asks (`fields=inLibrary`, attributes under `resources.albums`). Null when Apple did not
     * answer, so the page shows a save button rather than claiming a state it was not told.
     */
    val inLibrary: Boolean? = null,
)

object AppleAlbumReader {

    fun album(page: JsonObject): AppleAlbumPage? {
        val row = page["data"].array().firstOrNull()?.obj() ?: return null
        val id = row.idString("id") ?: return null
        val attributes = row["attributes"].obj()
        return AppleAlbumPage(
            id = id,
            title = attributes?.string("name") ?: return null,
            artistName = attributes?.string("artistName").orEmpty(),
            // Apple's album resource has no artistId attribute: the id is the artists relationship,
            // asked for with include=artists
            artistId = attributes?.string("artistId") ?: row["relationships"].obj()
                ?.get("artists").obj()?.get("data").array()?.firstOrNull()?.obj()?.idString("id"),
            year = attributes?.string("releaseDate")?.take(4)?.toIntOrNull(),
            explicit = attributes?.string("contentRating") == "explicit",
            artworkUrl = attributes?.get("artwork").obj()?.string("url"),
            description = attributes?.get("editorialNotes").obj()?.let { notes ->
                notes.string("standard") ?: notes.string("short")
            }?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** Apple sometimes answers `meta.total` with more tracks than one page holds. */
    fun trackTotal(page: JsonObject): Int? =
        page["meta"].obj()?.long("total")?.toInt()
}
