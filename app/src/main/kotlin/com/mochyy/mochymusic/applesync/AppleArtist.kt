// Concord: new — an Apple artist as its page needs one, read the way Cider's /am/artist/:id does.
//
// The artist resource carries the name, the photo and the genres; everything else on the page is a
// view of the same artist: top-songs, full-albums, singles, compilation-albums, similar-artists.
// (`featured-playlists` 404s and `appears-on` is not a path Apple knows; both were checked live.)
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.canvas.AppleArtistEditorial
import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.long
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import com.mochyy.mochymusic.importer.strings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** An album on an artist's page: enough to draw a card and open the album. */
data class AppleAlbumRef(
    val id: String,
    val name: String,
    val artistName: String?,
    val artworkUrl: String?,
    val year: Int?,
    val trackCount: Int?,
)

/** A music video on an artist's page. */
data class AppleMusicVideo(
    val id: String,
    val name: String,
    val artworkUrl: String?,
    val url: String?,
    /** the preview clip Apple ships with a video, asked for with extend=previews; a short mp4 */
    val previewUrl: String? = null,
    /** the still Apple draws before the clip loads */
    val previewFrameUrl: String? = null,
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
    /** `attributes.artistBio`, asked for with extend=artistBio; html, as Apple writes it. */
    val bio: String? = null,
    val latestRelease: AppleAlbumRef? = null,
    val topSongs: List<RemoteSong> = emptyList(),
    val albums: List<AppleAlbumRef> = emptyList(),
    val singles: List<AppleAlbumRef> = emptyList(),
    val compilations: List<AppleAlbumRef> = emptyList(),
    val videos: List<AppleMusicVideo> = emptyList(),
    val similar: List<AppleArtistRef> = emptyList(),
    /**
     * Apple's favourite on this artist, read through `extend=…inFavorites` (the page's own request
     * carries it). Null when the payload did not answer, so the page falls back to what it knows.
     */
    val inFavorites: Boolean? = null,
    /**
     * Concord: the hero video, logo and bio, parsed from this same payload by the canvas provider's
     * one parser (`AppleMusicArtistBackgroundProvider.editorialFrom`). Null when Apple sent no
     * editorial for this artist, or when the caller fetched the artist without `extend`.
     */
    val editorial: AppleArtistEditorial? = null,
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
            bio = attributes?.string("artistBio"),
            // `extend=…inFavorites` sends this as a real boolean; absent means Apple did not answer
            inFavorites = (attributes?.get("inFavorites") as? JsonPrimitive)?.booleanOrNull,
        )
    }

    fun videos(page: JsonObject): List<AppleMusicVideo> =
        page["data"].array().mapNotNull { row ->
            val item = row.obj() ?: return@mapNotNull null
            val attributes = item["attributes"].obj()
            val preview = attributes?.get("previews").array()?.firstOrNull()?.obj()
            AppleMusicVideo(
                id = item.idString("id") ?: return@mapNotNull null,
                name = attributes?.string("name") ?: return@mapNotNull null,
                artworkUrl = attributes?.get("artwork").obj()?.string("url"),
                url = attributes?.string("url"),
                previewUrl = preview?.string("url"),
                previewFrameUrl = preview?.get("artwork").obj()?.string("url"),
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
