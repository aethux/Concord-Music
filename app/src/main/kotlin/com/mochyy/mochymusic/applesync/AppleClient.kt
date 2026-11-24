// Concord: copied from Mocha applesync/AppleClient.kt (apple-sync@c90b8251). Package renamed only; behaviour unchanged.
// Concord: Mocha's read client verbatim, catalogCover() included.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.account.AccountHttp
import com.mochyy.mochymusic.importer.account.AccountImportException
import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.long
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import kotlinx.serialization.json.JsonObject

/**
 * Reads only, on purpose.
 *
 * There is no write method here and there will not be one: writes go through the page transport, and
 * a REST door would be exactly the thing the guard exists to keep closed. Requests reuse
 * [AccountHttp], which already waits out 429s, marks a 401 as "sign in again" and turns transport
 * failures into something a user can read.
 */
class AppleClient(
    private val tokens: AppleTokens.Pair,
    /**
     * Concord: when set, every read goes through the page cache — a revisit is a disk read with a
     * refresh behind it. This class stays a reader; the lifetime, the size cap and the invalidation
     * live in [ApplePageCache].
     */
    private val cache: ApplePageCache? = null,
) {

    suspend fun storefront(): String =
        page("/v1/me/storefront")?.let(AppleLibrary::storefront)
            ?: throw AppleSyncException("Apple didn't say which storefront this account is in.")

    /**
     * Library playlists, Apple's own "Favorite Songs" included: reading it is fine, writing never.
     *
     * `include=catalog` is what makes the owner visible: the library resource has no owner field,
     * but its catalogue counterpart carries `curatorName`, which is the name the web player shows.
     */
    suspend fun playlists(maxPages: Int = 40, onPage: (Int) -> Unit = {}): List<RemotePlaylist> =
        all(
            first = "/v1/me/library/playlists?limit=100&include=catalog",
            maxPages = maxPages,
            parse = AppleLibrary::playlists,
            onPage = onPage,
            // the next page is a bare offset: without this every page after the first has no owner
            keep = "&include=catalog",
        )

    /**
     * Who owns one playlist. The library *list* does not carry it - `include=catalog` there returns
     * the relationship without attributes - but the single resource does, and its catalogue
     * counterpart carries `curatorName`, the same name the web player prints.
     */
    suspend fun playlistCurator(playlistId: String): PlaylistCatalogue? = runCatching {
        val page = page("/v1/me/library/playlists/$playlistId?include=catalog") ?: return null
        AppleLibrary.playlistOwner(page)
    }.getOrNull()

    /**
     * Who this account is: its own name and picture. Concord: added — the one read that stands in for
     * the owner field Apple's playlist resources do not have.
     */
    suspend fun socialProfile(): AppleProfile? = runCatching {
        val page = page("/v1/me/social/profile") ?: return null
        AppleLibrary.socialProfile(page)
    }.getOrNull()

    /** A catalogue playlist's tracks: every one, in Apple's order, not just the library's. */
    suspend fun catalogPlaylistTracks(catalogId: String, storefront: String, maxPages: Int = 40): List<RemoteSong> =
        all(
            first = "/v1/catalog/$storefront/playlists/$catalogId/tracks?limit=100",
            maxPages = maxPages,
            parse = { page -> AppleLibrary.catalogTracks(page) },
        )

    /**
     * How many tracks Apple itself says a playlist holds, before any paging of ours: `meta.total`
     * on the library copy, `attributes.trackCount` on the catalogue counterpart.
     *
     * Imported counts were compared only against themselves, so a read that stopped early looked
     * exactly like a playlist that is that short.
     */
    suspend fun playlistTrackTotal(playlistId: String): Int? =
        runCatching {
            page("/v1/me/library/playlists/$playlistId/tracks?limit=1")
                ?.get("meta").obj()?.long("total")?.toInt()
        }.getOrNull()

    suspend fun catalogPlaylistTotal(catalogId: String, storefront: String): Int? =
        runCatching {
            page("/v1/catalog/$storefront/playlists/$catalogId")
                ?.get("data").array()?.firstOrNull()?.obj()
                ?.get("attributes").obj()?.long("trackCount")?.toInt()
        }.getOrNull()

    /**
     * The shapes a library playlist's entries can be asked for, counted, so a short import can be
     * told apart from a short playlist: `tracks` is the endpoint the import uses, `inline` is the
     * resource's own relationship, `rel` is the relationship endpoint.
     */
    suspend fun probePlaylistShapes(playlistId: String): String {
        val tracks = runCatching { page("/v1/me/library/playlists/$playlistId/tracks?limit=100") }.getOrNull()
        val inline = runCatching { page("/v1/me/library/playlists/$playlistId?include=tracks") }.getOrNull()
        val relationship = runCatching {
            page("/v1/me/library/playlists/$playlistId/relationships/tracks?limit=100")
        }.getOrNull()
        val inlineTracks = inline?.get("data").array()?.firstOrNull()?.obj()
            ?.get("relationships").obj()?.get("tracks").obj()
        return "tracks " + tracks?.get("data").array()?.size + "/" + tracks?.get("meta").obj()?.long("total") +
            " · inline " + inlineTracks?.get("data").array()?.size + "/" + inlineTracks?.get("meta").obj()?.long("total") +
            " · relationship " + relationship?.get("data").array()?.size + "/" + relationship?.get("meta").obj()?.long("total")
    }

    /**
     * Every song in the library. A 9k library is about 90 pages, so the default cap is generous.
     *
     * [onPage] reports after each hundred, which is what keeps a long read honest: a counter that
     * only changes when the whole thing finishes is indistinguishable from a stuck one.
     */
    suspend fun librarySongs(
        maxPages: Int = 400,
        onPage: (Int) -> Unit = {},
    ): List<RemoteSong> = all(
        first = "/v1/me/library/songs?limit=100&include=albums",
        maxPages = maxPages,
        parse = AppleLibrary::songs,
        onPage = onPage,
        // Apple's next page is an offset URL without the include: without this only the first
        // hundred songs come back with their album
        keep = "&include=albums",
    )

    /**
     * The library's own search: one request that finds a song by name.
     *
     * It exists because the library list is not ordered by date added, so the newest page is the
     * wrong place to look for something that was just put in, and a full read is fifteen requests.
     */
    suspend fun librarySearch(term: String, limit: Int = 25): List<RemoteSong> {
        val query = java.net.URLEncoder.encode(term, "UTF-8")
        val page = page("/v1/me/library/search?term=$query&types=library-songs&limit=$limit") ?: return emptyList()
        val songs = page["results"].obj()?.get("library-songs").obj() ?: return emptyList()
        return AppleLibrary.songs(songs)
    }

    /**
     * The first page of the library only.
     *
     * Used to watch for a song that was just added: a full read is fifteen round trips and made a
     * half-minute poll look like a hang. Newly added songs land at the top of this list.
     */
    suspend fun libraryHead(): List<RemoteSong> =
        page("/v1/me/library/songs?limit=100")?.let(AppleLibrary::songs).orEmpty()

    /** Catalog ids for a search term, best match first. Reads only; used to pick sandbox songs. */
    suspend fun searchSongs(term: String, limit: Int = 10): List<String> {
        val storefront = storefront()
        val query = java.net.URLEncoder.encode(term, "UTF-8")
        val page = page("/v1/catalog/$storefront/search?term=$query&types=songs&limit=$limit") ?: return emptyList()
        val results = page["results"].obj() ?: return emptyList()
        return results["songs"].obj()?.get("data").array().orEmpty().mapNotNull { it.obj()?.idString("id") }
    }

    /**
     * Catalogue rows for a search term, with everything the matcher needs to score them.
     *
     * Separate from [searchSongs], which returns bare ids: a match is decided on title, artist,
     * album and length, and none of those exist until the rows themselves are read.
     */
    suspend fun searchCatalog(term: String, limit: Int = 15): List<CatalogSong> {
        val storefront = storefront()
        val query = java.net.URLEncoder.encode(term, "UTF-8")
        val page = page("/v1/catalog/$storefront/search?term=$query&types=songs&limit=$limit") ?: return emptyList()
        val songs = page["results"].obj()?.get("songs").obj() ?: return emptyList()
        return AppleLibrary.catalogSongs(songs)
    }

    /** Every kind of result in one request: songs, artists, albums, playlists, videos. */
    suspend fun searchAll(term: String, limit: Int = 12): JsonObject? {
        val storefront = storefront()
        val query = java.net.URLEncoder.encode(term, "UTF-8")
        return page(
            "/v1/catalog/$storefront/search?term=$query" +
                "&types=songs,artists,albums,playlists,music-videos&limit=$limit",
        )
    }

    /**
     * A catalogue song's covers: its still artwork and, when the album has one, the motion loop.
     *
     * One request, `include=albums`, because the animated artwork belongs to the album and asking
     * for the song alone would never see it.
     */
    suspend fun catalogCover(id: String): com.mochyy.mochymusic.applesync.AppleCovers.AppleCover? {
        val storefront = storefront()
        val song = runCatching {
            page("/v1/catalog/$storefront/songs/$id?extend=editorialVideo&include=albums")
        }.getOrNull() ?: return null
        val row = song["data"].array().orEmpty().firstOrNull()?.obj() ?: return null
        val attributes = row["attributes"].obj()
        val still = attributes?.get("artwork").obj()?.string("url")
        // some songs carry their own motion loop
        val songMotion = attributes?.get("editorialVideo").obj()
            ?.get("motionDetailSquare").obj()?.string("video")
        if (songMotion != null) return com.mochyy.mochymusic.applesync.AppleCovers.AppleCover(songMotion, still)

        // otherwise it belongs to the album, which needs its own request with the extension
        val albumId = row["relationships"].obj()?.get("albums").obj()
            ?.get("data").array().orEmpty().firstOrNull()?.obj()?.idString("id")
        if (albumId == null) return com.mochyy.mochymusic.applesync.AppleCovers.AppleCover(null, still)
        val album = runCatching {
            page("/v1/catalog/$storefront/albums/$albumId?extend=editorialVideo")
        }.getOrNull()
        val albumAttributes = album?.get("data").array().orEmpty().firstOrNull()?.obj()?.get("attributes").obj()
        val motion = albumAttributes?.get("editorialVideo").obj()
            ?.get("motionDetailSquare").obj()?.string("video")
            ?: albumAttributes?.get("editorialVideo").obj()?.get("motionDetailTall").obj()?.string("video")
        return com.mochyy.mochymusic.applesync.AppleCovers.AppleCover(motion, still)
    }

    /** A catalogue song's name and artist, so a write can say what it is about to touch. */
    suspend fun catalogSong(id: String): Pair<String, String>? {
        val storefront = storefront()
        val page = runCatching { page("/v1/catalog/$storefront/songs/$id") }.getOrNull() ?: return null
        val row = page["data"].array().orEmpty().firstOrNull()?.obj() ?: return null
        val attributes = row["attributes"].obj() ?: return null
        val title = attributes.string("name") ?: return null
        return title to (attributes.string("artistName") ?: "")
    }

    /** A catalogue song in full, so the page's list can be imported without guessing metadata. */
    suspend fun catalogSongFull(id: String): com.mochyy.mochymusic.applesync.CatalogSong? {
        val storefront = storefront()
        val page = runCatching { page("/v1/catalog/$storefront/songs/$id") }.getOrNull() ?: return null
        return AppleLibrary.catalogSongs(page).firstOrNull { it.id == id }
    }

    /**
     * Favourites for many songs in one go: 1 is loved, -1 is disliked, anything absent has no rating.
     *
     * Ratings live on api.music.apple.com while the library lives on amp-api, and the endpoint takes
     * a comma separated list, so a whole library costs a handful of requests instead of one per song.
     */
    suspend fun ratings(catalogIds: List<String>): Map<String, Long> {
        if (catalogIds.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, Long>()
        for (chunk in catalogIds.chunked(100)) {
            // Concord: Mocha addressed ratings on api.music.apple.com. From this app that host refuses
            // the page's own token pair ("Failed to fetch" — CORS/DNS, not auth), while amp-api answers
            // the same path 200. Verified from the signed-in page 2026-11-22. Left absolute, this made
            // every chunk retry with backoff and the read look like a hang.
            val page = page("/v1/me/ratings/songs?ids=${chunk.joinToString(",")}")
                ?: continue
            for (item in page["data"].array()) {
                val obj = item.obj() ?: continue
                val id = obj.idString("id") ?: continue
                obj["attributes"].obj()?.long("value")?.let { out[id] = it }
            }
        }
        return out
    }

    /**
     * The account's rating for a library song: 1 loved, -1 disliked, null when it has none.
     *
     * A 404 is the normal answer for "no rating", not a failure, so it becomes null rather than an
     * exception.
     */
    suspend fun rating(songId: String): Long? {
        val page = runCatching { page("/v1/me/ratings/songs/$songId") }.getOrNull() ?: return null
        val row = page["data"].array().orEmpty().firstOrNull()?.obj() ?: return null
        return row["attributes"].obj()?.long("value")
    }

    /**
     * One album's page, read the way Cider's `/am/album/:id` reads it.
     *
     * A library album (`l.…`, which is what the import stored for most albums) is resolved to its
     * catalogue twin first — `/v1/me/library/albums/{id}?include=catalog`, then
     * `relationships.catalog.data[0].id` — because a catalogue read of an `l.` id answers 404. The
     * tracklist then comes from the library album's own `/tracks`, so uploaded and library-only
     * tracks are on the page; a catalogue album reads its own `/tracks` instead.
     */
    suspend fun catalogAlbum(albumId: String, maxPages: Int = 10): AppleAlbumPage? {
        val storefront = storefront()
        if (!albumId.startsWith("l.")) {
            val page = page("/v1/catalog/$storefront/albums/$albumId?include=artists") ?: return null
            val album = AppleAlbumReader.album(page) ?: return null
            return album.copy(
                songs = all(
                    first = "/v1/catalog/$storefront/albums/$albumId/tracks?limit=100",
                    maxPages = maxPages,
                    parse = AppleLibrary::catalogTracks,
                ),
            )
        }
        // A library album's page is its catalogue twin — the usual case. Its tracklist is the
        // catalogue one too: the library's own `/tracks` holds only the songs that are in the
        // library (one row for a sixteen-track album), not the album.
        val twin = libraryAlbumTwin(albumId)
        if (twin != null) {
            val page = page("/v1/catalog/$storefront/albums/$twin?include=artists")
            val album = page?.let(AppleAlbumReader::album)
            if (album != null) {
                return album.copy(
                    songs = all(
                        first = "/v1/catalog/$storefront/albums/$twin/tracks?limit=100",
                        maxPages = maxPages,
                        parse = AppleLibrary::catalogTracks,
                    ),
                )
            }
        }
        // no catalogue twin: an upload or an unmatched album. Its own library resource and rows are
        // all Apple has, and they are playable through the library ids.
        val page = page("/v1/me/library/albums/$albumId") ?: return null
        val album = AppleAlbumReader.album(page) ?: return null
        return album.copy(
            songs = all(
                first = "/v1/me/library/albums/$albumId/tracks?limit=100",
                maxPages = maxPages,
                parse = AppleLibrary::songs,
            ),
        )
    }

    /**
     * Apple's own Home feed: the shelf list the web player asks for, resources sideloaded. Null means
     * the read failed, which the page has to tell apart from "this account has no shelves yet".
     *
     * `timezone` is required — without it Apple answers 400 — and the bracket parameters are
     * percent-encoded because this travels as a plain URL string.
     */
    suspend fun homeGroups(): List<AppleHomeGroup>? {
        val page = page(HomeFeedPath()) ?: return null
        return AppleHomeReader.ordered(AppleHomeReader.groups(page))
    }

    private fun HomeFeedPath(): String = "/v1/me/recommendations?" + listOf(
        "art%5Burl%5D=f",
        "displayFilter%5Bkind%5D=MusicCircleCoverShelf,MusicConcertsEmptyShelf,MusicCoverGrid,MusicCoverShelf,MusicNotesHeroShelf,MusicSocialCardShelf,MusicSuperHeroShelf",
        "extend=editorialArtwork,editorialVideo,plainEditorialCard,plainEditorialNotes",
        "extend%5Bplaylists%5D=artistNames",
        "extend%5Bstations%5D=airTime,supportsAirTimeUpdates",
        "fields%5Bartists%5D=name,artwork,url",
        "format%5Bresources%5D=map",
        "include%5Balbums%5D=artists",
        "include%5Blibrary-playlists%5D=catalog",
        "include%5Bpersonal-recommendation%5D=primary-content",
        "include%5Bstations%5D=radio-show",
        "meta%5Bstations%5D=inflectionPoints",
        "name=listen-now",
        "omit%5Bresource%5D=autos",
        "platform=web",
        "timezone=" + timezoneOffset(),
        "types=activities,albums,apple-curators,artists,concerts,curators,editorial-items,library-albums,library-playlists,music-movies,music-videos,playlists,social-profiles,social-upsells,songs,stations,tv-episodes,tv-shows,uploaded-audios,uploaded-videos",
        "with=friendsMix,library,social",
    ).joinToString("&")

    /** the device's offset from UTC as `+05:30`, which Apple's home feed insists on */
    private fun timezoneOffset(): String {
        val minutes = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (minutes < 0) "-" else "+"
        val abs = kotlin.math.abs(minutes)
        return "%s%02d:%02d".format(sign, abs / 60, abs % 60)
    }

    /** The catalogue id behind a library album id, or null when Apple has no catalogue twin for it. */
    private suspend fun libraryAlbumTwin(libraryId: String): String? = runCatching {
        page("/v1/me/library/albums/$libraryId?include=catalog")
            ?.get("data").array()?.firstOrNull()?.obj()
            ?.get("relationships").obj()?.get("catalog").obj()
            ?.get("data").array()?.firstOrNull()?.obj()
            ?.idString("id")
    }.getOrNull()

    /**
     * One playlist's page: the resource for `p.…` (library) or `pl.…` (catalogue), then its tracks.
     * Read-only — writes belong behind `AppleGuard`, not here.
     */
    suspend fun playlistPage(id: String): ApplePlaylist? {
        val storefront = storefront()
        val inLibrary = id.startsWith("p.")
        val page = page(
            if (inLibrary) "/v1/me/library/playlists/$id" else "/v1/catalog/$storefront/playlists/$id",
        ) ?: return null
        val playlist = ApplePlaylistReader.playlist(page, inLibrary) ?: return null
        val songs = if (inLibrary) playlistTracks(id) else catalogPlaylistTracks(id, storefront)
        // collaborators, the owner's profile and the playlist's own url live on the catalogue
        // representation only: a library playlist's resource carries no views
        val twin = if (inLibrary) ApplePlaylistReader.catalogueTwin(page) else id
        var social = twin?.let { runCatching { playlistSocial(it, storefront) }.getOrNull() }
        // a playlist shared with the account is not always on the catalogue twin's views; the library
        // resource answers `include=collaborators` for exactly that case
        if (inLibrary && social?.collaborators.isNullOrEmpty()) {
            social = runCatching { libraryPlaylistSocial(id) }.getOrNull() ?: social
        }
        timber.log.Timber.tag("ApplePlaylist").d(
            "page %s library=%s twin=%s collaborators=%d",
            id, inLibrary, twin, social?.collaborators?.size ?: -1,
        )
        return playlist.copy(
            songs = songs,
            url = playlist.url ?: social?.url,
            owner = social?.owner ?: playlist.owner,
            collaborators = social?.collaborators.orEmpty(),
        )
    }

    private data class PlaylistSocial(
        val url: String?,
        val owner: AppleCollaborator?,
        val collaborators: List<AppleCollaborator>,
    )

    /** `/v1/me/library/playlists/{id}?include=collaborators,curator` — the library's own answer. */
    private suspend fun libraryPlaylistSocial(id: String): PlaylistSocial? {
        val social = page("/v1/me/library/playlists/$id?include=collaborators,curator") ?: return null
        val row = social["data"].array().firstOrNull()?.obj() ?: return null
        return PlaylistSocial(
            url = row["attributes"].obj()?.string("url"),
            owner = ApplePlaylistReader.owner(row),
            collaborators = ApplePlaylistReader.relationshipsCollaborators(row),
        )
    }

    /** The catalogue twin, asked for the two things the library resource cannot answer. */
    private suspend fun playlistSocial(catalogId: String, storefront: String): PlaylistSocial? {
        val social = page(
            "/v1/catalog/$storefront/playlists/$catalogId" +
                "?include=curator&views=collaborators&with=lightProfiles" +
                "&fields[collaborators]=name,handle,artwork,url,isVerified",
        ) ?: return null
        val row = social["data"].array().firstOrNull()?.obj() ?: return null
        return PlaylistSocial(
            url = row["attributes"].obj()?.string("url"),
            owner = ApplePlaylistReader.owner(row),
            collaborators = ApplePlaylistReader.collaborators(row),
        )
    }

    /**
     * An artist's page: the resource for its name, photo and genres, then its views — top songs,
     * albums, singles, compilations and the artists beside it.
     */
    suspend fun artistPage(id: String): AppleArtist? {
        artistCache[id]?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        val storefront = storefront()
        val base = page("/v1/catalog/$storefront/artists/$id?extend=artistBio") ?: return null
        val artist = AppleArtistReader.artist(base) ?: return null
        val latestRelease = artistView(id, "latest-release", 1)
            ?.let { runCatching { AppleArtistReader.albums(it) }.getOrNull() }
            ?.firstOrNull()
        val videos = artistView(id, "music-videos", 6, extend = "previews")
            ?.let { runCatching { AppleArtistReader.videos(it) }.getOrNull() }
            .orEmpty()
        val topSongs = artistView(id, "top-songs", 10)
            ?.let { runCatching { AppleLibrary.songs(it) }.getOrNull() }
            .orEmpty()
        val albums = artistView(id, "full-albums", 20)
            ?.let { runCatching { AppleArtistReader.albums(it) }.getOrNull() }
            .orEmpty()
        val singles = artistView(id, "singles", 20)
            ?.let { runCatching { AppleArtistReader.albums(it) }.getOrNull() }
            .orEmpty()
        val compilations = artistView(id, "compilation-albums", 12)
            ?.let { runCatching { AppleArtistReader.albums(it) }.getOrNull() }
            .orEmpty()
        val similar = artistView(id, "similar-artists", 12)
            ?.let { runCatching { AppleArtistReader.artists(it) }.getOrNull() }
            .orEmpty()
        val page = artist.copy(
            latestRelease = latestRelease,
            videos = videos,
            topSongs = topSongs,
            albums = albums,
            singles = singles,
            compilations = compilations,
            similar = similar,
        )
        // the songs screens read the same artist; a short cache keeps that from being a second read
        artistCache[id] = page to (System.currentTimeMillis() + ArtistCacheMs)
        return page
    }

    private suspend fun artistView(id: String, view: String, limit: Int, extend: String? = null) =
        page(
            "/v1/catalog/${storefront()}/artists/$id/view/$view?limit=$limit" +
                (extend?.let { "&extend=$it" } ?: ""),
        )

    /**
     * An artist's id from their name, for the places that only know a name — Apple's album resource
     * carries no artist id, and its artists relationship is not always included.
     */
    suspend fun artistIdByName(name: String): String? {
        val storefront = storefront()
        val term = java.net.URLEncoder.encode(name, "UTF-8")
        val page = page("/v1/catalog/$storefront/search?term=$term&types=artists&limit=1") ?: return null
        return page["results"].obj()?.get("artists").obj()
            ?.get("data").array()?.firstOrNull()?.obj()?.idString("id")
    }

    /** A station's own resource: its name and its art. Its songs are asked for separately. */
    suspend fun station(id: String): AppleStation? {
        val page = page("/v1/catalog/${storefront()}/stations/$id") ?: return null
        return AppleStationReader.station(page)
    }

    /**
     * The next songs a station would play, `limit` at a time. Apple answers this one with POST, and
     * an empty body is what the web player sends.
     */
    suspend fun stationNextTracks(id: String, limit: Int = 10): List<RemoteSong> {
        val response = AccountHttp.post(
            url = Host + "/v1/me/stations/next-tracks/$id?limit=$limit",
            headers = tokens.headers,
            body = "{}",
            service = "Apple Music",
        )
        if (response.status !in 200..299) return emptyList()
        val page = AccountHttp.parse(response.body) ?: return emptyList()
        return AppleLibrary.songs(page)
    }

    /** One linked playlist's entries, in order, with each entry's library id. */
    suspend fun playlistTracks(playlistId: String, maxPages: Int = 400): List<RemoteSong> =
        all("/v1/me/library/playlists/$playlistId/tracks?limit=100", maxPages, AppleLibrary::songs)

    private suspend fun <T> all(
        first: String,
        maxPages: Int,
        parse: (JsonObject) -> List<T>,
        onPage: (Int) -> Unit = {},
        /**
         * A query parameter the first page asked for and every page after it must keep. Apple's
         * `next` is a bare offset URL: without this, page two of the library read comes back with
         * no `relationships`, and 1,300 songs arrive with no album.
         */
        keep: String = "",
    ): List<T> {
        val out = mutableListOf<T>()
        var url = first
        var pages = 0
        val kept = keep.removePrefix("&").removePrefix("?").substringBefore("&")
        val key = kept.substringBefore('=')
        while (pages++ < maxPages) {
            val page = page(url) ?: break
            out += parse(page)
            // reported per page, not per call: a fifteen-page read that only speaks at the end is a
            // counter stuck on zero for half a minute
            onPage(out.size)
            val next = AppleLibrary.nextPage(page) ?: break
            url = Host + next + if (key.isNotEmpty() && "?$key=" !in next && "&$key=" !in next) keep else ""
        }
        return out
    }

    // An elvis here would re-ask Apple for a 404: a null from the cache is an answer, not a miss.
    private suspend fun page(url: String): JsonObject? =
        if (cache == null) read(url) else cache.page(url) { read(url) }

    private suspend fun read(url: String): JsonObject? =
        try {
            AccountHttp.getJson(
                url = if (url.startsWith("http")) url else Host + url,
                headers = tokens.headers,
                service = "Apple Music",
                notFoundAsNull = true,
            )
        } catch (e: AccountImportException) {
            throw AppleSyncException(e.message ?: "Apple Music answered with an error.", signedOut = e.signedOut)
        }

    private companion object {
        const val Host = "https://amp-api.music.apple.com"

        /** the artist pages read this run, with the moment they expire */
        val artistCache = java.util.concurrent.ConcurrentHashMap<String, Pair<AppleArtist, Long>>()
        const val ArtistCacheMs = 1000L * 60 * 10
    }
}
