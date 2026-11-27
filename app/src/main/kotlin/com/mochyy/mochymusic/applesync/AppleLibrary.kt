// Concord: copied from Mocha applesync/AppleLibrary.kt (apple-sync@c90b8251). Package renamed only; behaviour unchanged.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.long
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import com.mochyy.mochymusic.importer.strings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.Normalizer

/**
 * Apple's JSON, read leniently. Pure functions, no HTTP: the client below fetches, and the tests
 * feed these recorded shapes.
 *
 * Apple's library rows carry the account's own id in `id` ("i.…") and the global catalogue id inside
 * `attributes.playParams.catalogId`, which is absent for uploads and matched files.
 */
internal object AppleLibrary {

    fun storefront(page: JsonObject): String? = page["data"].array().firstOrNull()?.obj()?.string("id")

    fun playlists(page: JsonObject): List<RemotePlaylist> = page["data"].array().mapNotNull { item ->

        val obj = item.obj() ?: return@mapNotNull null
        val id = obj.idString("id") ?: return@mapNotNull null
        val attributes = obj["attributes"].obj()
        // The catalogue twin the list includes answers both things a per-playlist read used to be
        // spent on: its id is the playlist's own `pl.…` id and its `curatorName` is the owner.
        // (It arrives on every page because `all()` keeps `include=catalog`; before that was fixed,
        // page two and after came back with a relationship but no attributes.)
        val catalog = obj["relationships"].obj()
            ?.get("catalog").obj()
            ?.get("data").array()
            ?.firstOrNull()?.obj()
        RemotePlaylist(
            id = id,
            // a deleted playlist stays in Apple's list as a nameless tombstone; keep it empty here
            // so the import can skip it instead of importing "Untitled playlist" with no songs
            name = attributes?.string("name").orEmpty(),
            canEdit = attributes?.bool("canEdit") ?: true,
            // Apple leaves this out for a playlist it has no artwork for; the import then falls
            // back to the first track rather than writing a null over a cover already there
            artworkUrl = attributes?.get("artwork").obj()?.string("url"),
            curator = catalog?.get("attributes").obj()?.string("curatorName"),
            catalogId = catalog?.idString("id"),
            // Apple has shipped this under more than one name across revisions of the API
            collaborative = attributes?.bool("isCollaborative") == true ||
                attributes?.bool("hasCollaboration") == true ||
                obj["relationships"].obj()?.get("collaborators") != null,
            lastModified = attributes?.string("lastModifiedDate"),
            canDelete = attributes?.bool("canDelete") ?: false,
            folderId = obj["relationships"].obj()
                ?.get("parent").obj()
                ?.get("data").array()
                ?.firstOrNull()?.obj()
                ?.idString("id"),
        )
    }

    /** `/v1/me/library/playlist-folders`: the account's folders, in Apple's order. */
    fun folders(page: JsonObject): List<RemotePlaylistFolder> = page["data"].array().mapNotNull { item ->
        val obj = item.obj() ?: return@mapNotNull null
        val id = obj.idString("id") ?: return@mapNotNull null
        val name = obj["attributes"].obj()?.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        RemotePlaylistFolder(id = id, name = name)
    }

    /**
     * `/v1/me/library/artists` rows: the account's own artist list, keyed by Apple's `r.…` ids.
     * `attributes` carries the name and nothing else; `include=catalog` puts the catalogue twin
     * (with its picture) in `relationships.catalog`, and that is allowed to be empty.
     */
    fun artists(page: JsonObject): List<RemoteLibraryArtist> = page["data"].array().mapNotNull { item ->
        val obj = item.obj() ?: return@mapNotNull null
        val id = obj.idString("id") ?: return@mapNotNull null
        val attributes = obj["attributes"].obj()
        val catalog = obj["relationships"].obj()
            ?.get("catalog").obj()
            ?.get("data").array()
            ?.firstOrNull()?.obj()
        val catalogAttributes = catalog?.get("attributes").obj()
        val name = attributes?.string("name")?.takeIf { it.isNotBlank() }
            ?: catalogAttributes?.string("name")?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        RemoteLibraryArtist(
            id = id,
            name = name,
            catalogId = catalog?.idString("id"),
            artworkUrl = catalogAttributes?.get("artwork").obj()?.string("url"),
        )
    }

    /** The catalogue artist behind a library one: `?include=catalog`, the read Cider uses. */
    fun catalogArtistId(page: JsonObject): String? = page["data"].array().firstOrNull()?.obj()
        ?.get("relationships").obj()
        ?.get("catalog").obj()
        ?.get("data").array()
        ?.firstOrNull()?.obj()
        ?.idString("id")

    /** `personal-social-profiles`: the account's own name, handle and picture. */
    fun socialProfile(page: JsonObject): AppleProfile? {
        val attributes = page["data"].array().firstOrNull()?.obj()?.get("attributes").obj() ?: return null
        return AppleProfile(
            name = attributes.string("name")?.trim()?.takeIf { it.isNotEmpty() },
            handle = attributes.string("handle")?.trim()?.takeIf { it.isNotEmpty() },
            avatarUrl = attributes.get("artwork").obj()?.string("url"),
            profileUrl = attributes.string("url")?.takeIf { it.startsWith("http") },
        )
    }

    /**
     * A catalogue playlist's tracks. Same fields as a catalogue song, plus its position in the
     * playlist, which the playlist's own order needs.
     */
    fun catalogTracks(page: JsonObject): List<RemoteSong> = page["data"].array().mapNotNull { item ->
        val obj = item.obj() ?: return@mapNotNull null
        val id = obj.idString("id") ?: return@mapNotNull null
        val attributes = obj["attributes"].obj()
        RemoteSong(
            catalogId = id,
            // a catalogue track has no library id: the song may not be in the library at all
            libraryId = "",
            isrc = attributes?.string("isrc"),
            title = attributes?.string("name") ?: return@mapNotNull null,
            artist = attributes?.string("artistName").orEmpty(),
            album = attributes?.string("albumName"),
            albumId = attributes?.get("playParams").obj()?.string("id"),
            albumArtworkUrl = attributes?.get("artwork").obj()?.string("url"),
            albumTrackCount = null,
            albumYear = null,
            trackNumber = attributes?.long("trackNumber")?.toInt(),
            durationMs = attributes?.long("durationInMillis"),
            artworkUrl = attributes?.get("artwork").obj()?.string("url"),
        )
    }

    /**
     * Catalogue rows, which carry the same fields as library rows except that the id *is* the
     * catalogue id rather than a library id, and there is no playParams to dig it out of.
     */
    fun catalogSongs(page: JsonObject): List<CatalogSong> = page["data"].array().mapNotNull { item ->
        val obj = item.obj() ?: return@mapNotNull null
        val id = obj.idString("id") ?: return@mapNotNull null
        val attributes = obj["attributes"].obj()
        CatalogSong(
            id = id,
            title = attributes?.string("name") ?: return@mapNotNull null,
            artist = attributes?.string("artistName").orEmpty(),
            album = attributes?.string("albumName"),
            durationMs = attributes?.long("durationInMillis"),
            isrc = attributes?.string("isrc"),
            artworkUrl = attributes?.get("artwork").obj()?.string("url"),
        )
    }

    fun songs(page: JsonObject): List<RemoteSong> = page["data"].array().mapNotNull { item ->
        val obj = item.obj() ?: return@mapNotNull null
        val libraryId = obj.idString("id") ?: return@mapNotNull null
        val attributes = obj["attributes"].obj()
        // `?include=albums` puts the album in relationships; a row without one keeps a null album
        val albumObj = obj["relationships"].obj()
            ?.get("albums").obj()
            ?.get("data").array()
            ?.firstOrNull()?.obj()
        val albumAttributes = albumObj?.get("attributes").obj()
        // Apple's artist grouping: the library row's `relationships.artists` is one library artist
        // and is sometimes a combined "A & B" entity; the catalogue twin, when the read included it,
        // carries the split list under its own `relationships.artists` — that is the one to keep.
        val relationships = obj["relationships"].obj()
        val catalogObj = relationships?.get("catalog").obj()
            ?.get("data").array()
            ?.firstOrNull()?.obj()
        fun artistRows(owner: JsonObject?): List<RemoteArtist> = owner
            ?.get("relationships").obj()
            ?.get("artists").obj()
            ?.get("data").array()
            .orEmpty()
            .mapNotNull { item ->
                val row = item.obj() ?: return@mapNotNull null
                val artistId = row.idString("id") ?: return@mapNotNull null
                val artistName = row["attributes"].obj()?.string("name")?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                RemoteArtist(id = artistId, name = artistName)
            }
        val linked = artistRows(catalogObj).ifEmpty { artistRows(obj) }.distinctBy { it.id }
        // Concord: a twin's artists only stand in for a song they belong to. Apple matches an upload
        // to a catalogue recording by its title, so a library item by one artist can carry another
        // recording's artists — `AM_1546780224`, "Kan Ena Tahoun" by Talia Lahoud, answers Cungur
        // Gede and Wut Pabon. An artist the item's own `artistName` never names is not this song's
        // artist, and the importer falls back to writing that credit line.
        val artists = if (namedInCredit(linked, attributes?.string("artistName"))) linked else emptyList()
        RemoteSong(
            libraryId = libraryId,
            catalogId = attributes?.get("playParams").obj()?.string("catalogId"),
            title = attributes?.string("name") ?: return@mapNotNull null,
            artist = attributes?.string("artistName").orEmpty(),
            album = attributes?.string("albumName"),
            durationMs = attributes?.long("durationInMillis"),
            isrc = attributes?.string("isrc"),
            genre = attributes?.strings("genreNames")?.firstOrNull(),
            artworkUrl = attributes?.get("artwork").obj()?.string("url"),
            // the catalogue id when the album has one, so the same album is one row everywhere
            albumId = albumObj?.let { row ->
                row["attributes"].obj()?.get("playParams").obj()?.string("catalogId")
                    ?: row.idString("id")
            },
            albumArtworkUrl = albumAttributes?.get("artwork").obj()?.string("url"),
            albumTrackCount = albumAttributes?.long("trackCount")?.toInt(),
            albumYear = albumAttributes?.string("releaseDate")?.take(4)?.toIntOrNull(),
            trackNumber = attributes?.long("trackNumber")?.toInt(),
            artists = artists,
        )
    }

    /**
     * Whether Apple's first linked artist is the artist the song's own credit line names. A bucket
     * ("Various Artists"), a compilation's grouping artist, or another recording's artists after a
     * bad upload match never are. Names are compared by their letters and digits only, so `K‐391`
     * (U+2010), `HUNTR/X` and `Fousheé` all match how the line spells them; a row with no credit line
     * at all keeps whatever Apple linked.
     */
    private fun namedInCredit(artists: List<RemoteArtist>, credit: String?): Boolean {
        val first = artists.firstOrNull() ?: return false
        val line = credit?.let(::creditKey).orEmpty()
        if (line.isEmpty()) return true
        val name = creditKey(first.name)
        return name.isNotEmpty() && line.contains(name)
    }

    private fun creditKey(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        .replace(Marks, "")
        .lowercase()
        .filter { it.isLetterOrDigit() }

    private val Marks = Regex("\\p{Mn}+")

    /** Apple sends booleans as real JSON booleans, which the lenient string reader would drop. */
    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    /** Apple pages with a path relative to the host, and no `next` on the last page. */
    fun nextPage(page: JsonObject): String? =
        page.string("next")?.takeIf { it.startsWith("/v1/") }
}
