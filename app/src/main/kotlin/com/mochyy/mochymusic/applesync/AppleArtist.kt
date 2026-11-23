// Concord: new — an Apple artist as its page needs one, read the way Cider's /am/artist/:id does.
//
// The artist resource carries the name, the photo and the genres; everything else on the page is a
// view of the same artist: top-songs, full-albums, singles, compilation-albums, similar-artists.
// (`featured-playlists` 404s and `appears-on` is not a path Apple knows; both were checked live.)
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.long
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import com.mochyy.mochymusic.importer.strings
import kotlinx.serialization.json.JsonObject

/** An album on an artist's page: enough to draw a card and open the album. */
data class AppleAlbumRef(
    val id: String,
    val name: String,
    val artistName: String?,
    val artworkUrl: String?,
    val year: Int?,
    val trackCount: Int?,
)

/** Another artist, from the similar-artists view. */
data class AppleArtistRef(
    val id: String,
    val name: String,
    val artworkUrl: String?,
)

data class AppleArtist(
    val id: String,
    val name: String,
    val artworkUrl: String?,
    val genres: List<String>,
    val url: String?,
    val topSongs: List<RemoteSong> = emptyList(),
    val albums: List<AppleAlbumRef> = emptyList(),
    val singles: List<AppleAlbumRef> = emptyList(),
    val compilations: List<AppleAlbumRef> = emptyList(),
    val similar: List<AppleArtistRef> = emptyList(),
)

object AppleArtistReader {

    fun artist(page: JsonObject): AppleArtist? {
        val row = page["data"].array().firstOrNull()?.obj() ?: return null
        val attributes = row["attributes"].obj()
        return AppleArtist(
            id = row.idString("id") ?: return null,
            name = attributes?.string("name") ?: return null,
            artworkUrl = attributes?.get("artwork").obj()?.string("url"),
            genres = attributes?.strings("genreNames").orEmpty(),
            url = attributes?.string("url"),
        )
    }

    fun albums(page: JsonObject): List<AppleAlbumRef> =
        page["data"].array().mapNotNull { row ->
            val item = row.obj() ?: return@mapNotNull null
            val attributes = item["attributes"].obj()
            AppleAlbumRef(
                id = item.idString("id") ?: return@mapNotNull null,
                name = attributes?.string("name") ?: return@mapNotNull null,
                artistName = attributes?.string("artistName"),
                artworkUrl = attributes?.get("artwork").obj()?.string("url"),
                year = attributes?.string("releaseDate")?.take(4)?.toIntOrNull(),
                trackCount = attributes?.long("trackCount")?.toInt(),
            )
        }

    fun artists(page: JsonObject): List<AppleArtistRef> =
        page["data"].array().mapNotNull { row ->
            val item = row.obj() ?: return@mapNotNull null
            val attributes = item["attributes"].obj()
            AppleArtistRef(
                id = item.idString("id") ?: return@mapNotNull null,
                name = attributes?.string("name") ?: return@mapNotNull null,
                artworkUrl = attributes?.get("artwork").obj()?.string("url"),
            )
        }
}
