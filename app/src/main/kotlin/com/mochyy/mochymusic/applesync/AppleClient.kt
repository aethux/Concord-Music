// Concord: copied from Mocha applesync/AppleClient.kt (apple-sync@c90b8251). Package renamed only; behaviour unchanged.
// Concord: Mocha's read client verbatim, catalogCover() included.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.canvas.AppleMusicArtistBackgroundProvider
import com.mochyy.mochymusic.importer.account.AccountHttp
import com.mochyy.mochymusic.importer.account.AccountImportException
import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.long
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
            first = "/v1/me/library/playlists?limit=100&include=catalog,parent",
            maxPages = maxPages,
            parse = AppleLibrary::playlists,
            onPage = onPage,
            // the next page is a bare offset: without these every page after the first has no owner
            // and answers 25 rows instead of 100. `include=parent` is the only way the list says
            // which folder a playlist sits in, so it is kept too.
            keep = "include=catalog,parent&limit=100",
        )

    /** `/v1/me/library/playlist-folders`: every folder Apple keeps for this account. */
    suspend fun playlistFolders(maxPages: Int = 10): List<RemotePlaylistFolder> =
        all(
            first = "/v1/me/library/playlist-folders?limit=100",
            maxPages = maxPages,
            parse = AppleLibrary::folders,
            keep = "limit=100",
        )

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
            keep = "limit=100",
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
    ): List<RemoteSong> {
        // The first page carries `meta.total`, which is every offset the library has: the rest are
        // read at once instead of one `next` URL after another. Eight at a time; a 429 costs one
        // `Retry-After` wait and the read carries on. Measured on 1488 songs: fifteen pages in a row
        // took 26 s, four at a time 10 s, eight at a time most of that again.
        // `include=albums,artists,catalog` plus the catalogue row's own artists: the library row's
        // single artist is a grouping bucket, the catalogue twin carries the split artists
        val query = "include=albums,artists,catalog&include[songs]=artists&limit=100"
        val first = page("/v1/me/library/songs?$query") ?: return emptyList()
        val songs = AppleLibrary.songs(first).toMutableList()
        onPage(songs.size)

        val total = first["meta"].obj()?.long("total")?.toInt() ?: return songs
        if (total <= songs.size) return songs

        val gate = Semaphore(4)
        // each page is parsed inside its own read and only the songs are kept: the JSON tree of a
        // page with `include=catalog` is far larger than its rows, and holding fourteen of them at
        // once is what ran this phone's 256 MB debug heap out of memory
        val rest = coroutineScope {
            (songs.size until total step 100)
                .take((maxPages - 1).coerceAtLeast(0))
                .map { offset ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            runCatching {
                                page("/v1/me/library/songs?$query&offset=$offset")?.let(AppleLibrary::songs)
                            }.getOrNull()
                        }
                    }
                }
                .awaitAll()
        }
        for (page in rest) {
            if (page == null) continue
            songs += page
            onPage(songs.size)
        }
        return songs
    }

    /**
     * Cider's library-artists read: every artist in the account's library, keyed by Apple's own
     * `r.…` ids and sorted by name. `include=catalog` carries the catalogue twin (and whatever
     * artwork Apple has for it). `all()` keeps `sort` and `include` on every page, because Apple's
     * `next` is a bare offset URL and drops them.
     */
    suspend fun libraryArtists(maxPages: Int = 20): List<RemoteLibraryArtist> {
        val query = "limit=100&sort=name&include=catalog&art%5Burl%5D=f"
        return all(
            first = "/v1/me/library/artists?$query",
            maxPages = maxPages,
            parse = AppleLibrary::artists,
            keep = query,
        )
    }

    /**
     * The catalogue artist behind an `r.…` library artist. Cider's read: the `/catalog` suffix form
     * 404s for artists, so this has to be `?include=catalog`, and the relationship can be empty.
     */
    suspend fun catalogIdForLibraryArtist(libraryArtistId: String): String? {
        val page = runCatching {
            page("/v1/me/library/artists/$libraryArtistId?include=catalog&fields%5Bartists%5D=name,url,artwork")
        }.getOrNull() ?: return null
        return AppleLibrary.catalogArtistId(page)
    }

    /** Catalog ids for a search term, best match first. Reads only; used to pick sandbox songs. */
    suspend fun searchSongs(term: String, limit: Int = 10): List<String> {
        val storefront = storefront()
        val query = java.net.URLEncoder.encode(term, "UTF-8")
        // `fields` trims the payload the way Cider does; this caller reads ids, so a name is all the
        // row needs besides the id Apple always answers
        val page = page(
            "/v1/catalog/$storefront/search?term=$query&types=songs&limit=$limit&fields%5Bsongs%5D=name",
        ) ?: return emptyList()
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
        // Cider's own field list for a song search, plus `isrc`: ours scores a match on it, and a
        // trimmed payload that drops it silently costs the exact-id path. Measured on this account:
        // 31,374 -> 14,423 bytes for 25 rows, 54 % off, with every field the reader parses intact.
        val page = page(
            "/v1/catalog/$storefront/search?term=$query&types=songs&limit=$limit" +
                "&fields%5Bsongs%5D=albumName,artistName,artwork,contentRating,durationInMillis," +
                "isrc,name,playParams",
        ) ?: return emptyList()
        val songs = page["results"].obj()?.get("songs").obj() ?: return emptyList()
        return AppleLibrary.catalogSongs(songs)
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

    /**
     * Favourites for many songs in one go: 1 is loved, -1 is disliked, anything absent has no rating.
     *
     * Ratings live on api.music.apple.com while the library lives on amp-api, and the endpoint takes
     * a comma separated list, so a whole library costs a handful of requests instead of one per song.
     */
    suspend fun ratings(catalogIds: List<String>): Map<String, Long> {
        if (catalogIds.isEmpty()) return emptyMap()
        // Cider reads its favourites in more than one request at a time; one after another here put
        // fifteen round trips in a row in front of the pull-to-refresh. Six at once, still under the
        // rate limit that costs a `Retry-After` wait.
        val gate = Semaphore(6)
        val pages = kotlinx.coroutines.coroutineScope {
            catalogIds.chunked(100)
                .map { chunk ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            // Concord: Mocha addressed ratings on api.music.apple.com. From this app that
                            // host refuses the page's own token pair ("Failed to fetch" — CORS/DNS, not
                            // auth), while amp-api answers the same path 200. Verified from the
                            // signed-in page 2026-11-22. Left absolute, this made every chunk retry
                            // with backoff and the read look like a hang.
                            runCatching { page("/v1/me/ratings/songs?ids=${chunk.joinToString(",")}") }
                                .getOrNull()
                        }
                    }
                }
                .awaitAll()
        }
        val out = mutableMapOf<String, Long>()
        for (response in pages) {
            for (item in response?.get("data").array()) {
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
        if (!albumId.startsWith("l.")) return catalogueAlbumPage(albumId, storefront, maxPages)
        // A library album's page is its catalogue twin — the usual case. Its tracklist is the
        // catalogue one too: the library's own `/tracks` holds only the songs that are in the
        // library (one row for a sixteen-track album), not the album.
        val twin = libraryAlbumTwin(albumId)
        if (twin != null) catalogueAlbumPage(twin, storefront, maxPages)?.let { return it }
        // no catalogue twin: an upload or an unmatched album. Its own library resource and rows are
        // all Apple has, and they are playable through the library ids.
        val page = page("/v1/me/library/albums/$albumId") ?: return null
        val album = AppleAlbumReader.album(page) ?: return null
        return album.copy(
            songs = all(
                first = "/v1/me/library/albums/$albumId/tracks?limit=100",
                maxPages = maxPages,
                parse = AppleLibrary::songs,
                keep = "limit=100",
            ),
            // Apple keeps the album but not which catalogue release it is (`catalog` comes back
            // empty), so this is what the library holds, not the album. The page says so.
            libraryOnly = true,
        )
    }

    /**
     * One catalogue album, its tracklist and its artist in a single request — the shape Cider's
     * `/am/album/:id` uses.
     *
     * `include=tracks` answers the whole list (probed on this account 2026-11-24: 31 of 31 on a
     * 31-track album), so the paged `/tracks` walk below is only the fallback for a list Apple
     * truncated. The relationship object is `{data:[…], meta:{total}}`, the same shape the tracks
     * endpoint returns, so the existing parser reads it unchanged.
     */
    private suspend fun catalogueAlbumPage(
        albumId: String,
        storefront: String,
        maxPages: Int,
    ): AppleAlbumPage? {
        val page = page("/v1/catalog/$storefront/albums/$albumId?include=tracks,artists") ?: return null
        val album = AppleAlbumReader.album(page) ?: return null
        val row = page["data"].array().orEmpty().firstOrNull()?.obj()
        val included = row?.get("relationships").obj()?.get("tracks").obj()
        val tracks = included?.let(AppleLibrary::catalogTracks).orEmpty()
        // `meta` was absent on the probed album, so the resource's own trackCount is the usable
        // count; without either, a list Apple truncated could not be told from a short album
        val total = included?.get("meta").obj()?.long("total")?.toInt()
            ?: row?.get("attributes").obj()?.long("trackCount")?.toInt()
        val complete = tracks.isNotEmpty() && (total == null || tracks.size >= total)
        return album.copy(
            songs = if (complete) {
                tracks
            } else {
                all(
                    first = "/v1/catalog/$storefront/albums/$albumId/tracks?limit=100",
                    maxPages = maxPages,
                    parse = AppleLibrary::catalogTracks,
                    keep = "limit=100",
                )
            },
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

    /**
     * One Home shelf as its own page.
     *
     * Apple answers with the group alone and its **whole** card list (`limit`/`offset` are ignored —
     * measured: Recently Played answers 40 cards where the feed's row showed 12), but only when the
     * request carries the feed's own context: `name=listen-now`, the timezone and `platform=web`.
     * Without them the same path answers 404, which is why this is not a plain page read.
     *
     * The group id is minted per feed read, so a shelf opened after a refresh can find its id gone;
     * that is a null, not an error. Not cached: the id rotates, so a row per id would only bloat the
     * page cache, and this page is opened once per tap.
     */
    suspend fun homeShelf(groupId: String): AppleHomeGroup? {
        val page = page(
            "/v1/me/recommendations/$groupId/view/see-all?" + listOf(
                "format%5Bresources%5D=map",
                "name=listen-now",
                "platform=web",
                "timezone=" + timezoneOffset(),
            ).joinToString("&")
        ) ?: return null
        return AppleHomeReader.shelf(page)
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
        // A playlist shared with the account is not always on the catalogue twin's views, and when it
        // is, the view can still answer with the owner alone on a playlist that is in fact shared
        // ("Faith" answers one person while Apple flags the playlist as collaborative). The library
        // resource answers `include=collaborators` for exactly that case, so it is asked whenever the
        // catalogue read came back with fewer than two people — not only when it came back empty.
        if (inLibrary) {
            val known = (listOfNotNull(social?.owner) + social?.collaborators.orEmpty())
                .map { it.id ?: it.handle ?: it.name }
                .distinct()
                .size
            if (known < 2) {
                val fromLibrary = runCatching { libraryPlaylistSocial(id) }.getOrNull()
                if (fromLibrary != null) social = mergedSocial(social, fromLibrary)
                // Still one person on a playlist Apple itself flags as collaborative: the `fields`
                // trim drops a collaborator whose profile is private, because a trimmed row with no
                // `name` parses as nobody. The same view, asked for everything, is the last door.
                val peopleNow = (listOfNotNull(social?.owner) + social?.collaborators.orEmpty())
                    .map { it.id ?: it.handle ?: it.name }
                    .distinct()
                    .size
                if (peopleNow < 2) {
                    val untrimmed = twin?.let {
                        runCatching { playlistSocial(it, storefront, trim = false) }.getOrNull()
                    }
                    if (untrimmed != null) social = mergedSocial(social, untrimmed)
                }
            }
        }
        // more than one person on the playlist: the owner Apple lists plus anyone in collaborators
        val people = (listOfNotNull(social?.owner ?: playlist.owner) + social?.collaborators.orEmpty())
            .map { it.id ?: it.name }
            .distinct()
        // Apple can say a playlist is shared while naming only one person ("Faith": collaborators
        // answers Faizaan alone, and the second profile is not disclosed to this app). The
        // per-track associations are still worth asking for on a playlist Apple itself flags, and
        // they are what names the other adder.
        val flaggedShared = page["data"].array().firstOrNull()?.obj()
            ?.get("attributes").obj()?.get("hasCollaboration")
            .let { it is kotlinx.serialization.json.JsonPrimitive && it.content == "true" }
        val shared = people.size > 1 || flaggedShared
        timber.log.Timber.tag("ApplePlaylist").d(
            "page %s library=%s twin=%s collaborators=%d",
            id, inLibrary, twin, social?.collaborators?.size ?: -1,
        )
        // who added each song: only the catalogue representation says so, and only a playlist with
        // someone else on it can have an answer worth drawing, so one read and only then
        val addedBy = if (twin != null && shared) {
            runCatching { playlistAddedBy(twin, storefront) }.getOrNull().orEmpty()
        } else {
            emptyMap()
        }
        return playlist.copy(
            songs = songs,
            url = playlist.url ?: social?.url,
            owner = social?.owner ?: playlist.owner,
            collaborators = social?.collaborators.orEmpty(),
            // the associations can name a second adder the collaborators view would not
            shared = shared || addedBy.values.map { it.id ?: it.handle ?: it.name }.distinct().size > 1,
            addedBy = addedBy,
        )
    }

    /**
     * Who added each song, in one read of the catalogue twin's own tracks.
     *
     * The resource form is the only one that answers it: `associate[playlists:tracks]=collaborators`
     * on `/…/playlists/{id}/tracks` returns the same rows with no `meta` at all. `limit[tracks]=100`
     * is the resource's ceiling, so a longer playlist keeps no avatar past row 100 rather than a
     * guessed one. The read goes through [page], so a page cache put in front of this client keeps it
     * for the same six hours as the playlist itself.
     */
    private suspend fun playlistAddedBy(
        playlistId: String,
        storefront: String,
    ): Map<String, AppleCollaborator> =
        page(
            "/v1/catalog/$storefront/playlists/$playlistId?include=tracks" +
                "&limit%5Btracks%5D=100&associate%5Bplaylists%3Atracks%5D=collaborators" +
                "&fields%5Bsongs%5D=name" +
                // without this the association is a bare id and href for anyone whose profile is
                // not public; with it Apple fills in the name and the picture (probed: 23 of 23 rows
                // on a playlist that answered nobody before)
                "&with=lightProfiles",
        )?.let(ApplePlaylistReader::addedBy).orEmpty()

    private data class PlaylistSocial(
        val url: String?,
        val owner: AppleCollaborator?,
        val collaborators: List<AppleCollaborator>,
    )

    /** `/v1/me/library/playlists/{id}?include=collaborators,curator` — the library's own answer. */
    /** The same playlist read through two doors: one person from each, one list, no duplicates. */
    private fun mergedSocial(first: PlaylistSocial?, second: PlaylistSocial): PlaylistSocial =
        if (first == null) {
            second
        } else {
            PlaylistSocial(
                url = first.url ?: second.url,
                owner = first.owner ?: second.owner,
                collaborators = (first.collaborators + second.collaborators)
                    .distinctBy { it.id ?: it.handle ?: it.name },
            )
        }

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
    private suspend fun playlistSocial(
        catalogId: String,
        storefront: String,
        /** false asks for every attribute Apple has; see the fallback in [playlistPage] */
        trim: Boolean = true,
    ): PlaylistSocial? {
        val social = page(
            "/v1/catalog/$storefront/playlists/$catalogId" +
                "?include=curator&views=collaborators&with=lightProfiles" +
                if (trim) "&fields[collaborators]=name,handle,artwork,url,isVerified" else "",
        ) ?: return null
        val row = social["data"].array().firstOrNull()?.obj() ?: return null
        return PlaylistSocial(
            url = row["attributes"].obj()?.string("url"),
            owner = ApplePlaylistReader.owner(row),
            collaborators = ApplePlaylistReader.collaborators(row),
        )
    }

    /**
     * Just an artist's picture, for a shelf whose title names them. One light read (`fields` trims it
     * to name and artwork) and the page cache holds the row for a day, so Home pays for it once.
     */
    suspend fun artistArtwork(id: String): String? {
        val storefront = storefront()
        val page = page("/v1/catalog/$storefront/artists/$id?fields%5Bartists%5D=name,artwork") ?: return null
        return page["data"].array().orEmpty().firstOrNull()?.obj()
            ?.get("attributes").obj()?.get("artwork").obj()?.string("url")
    }

    /** the years Apple keeps a Replay for, newest first */
    suspend fun replayYears(): List<AppleReplayYear> {
        val page = page("/v1/me/music-summaries/search?period=year&fields%5Bmusic-summaries%5D=period,year")
            ?: return emptyList()
        return AppleReplayReader.years(page)
    }

    /** one year's summary, and the Replay playlist it carries (`pl.…`, so the playlist page opens) */
    suspend fun replayPlaylistId(summaryId: String): String? {
        val page = page("/v1/me/music-summaries/$summaryId?include=playlist") ?: return null
        return AppleReplayReader.playlistId(page)
    }

    /**
     * An artist's page in one request.
     *
     * The six shelves are `views` on the artist resource itself, so nothing is asked per shelf. They
     * live under `data[0].views` — *not* under `relationships`, which is where an earlier read of
     * this payload looked and found nothing — and each one is `{data:[…]}`, the same shape a
     * `/view/{name}` response has, so the existing readers parse them unchanged.
     *
     * Sizes come from `limit[<view>]`; a bare `limit` on this resource is a 400 (probed 2026-11-24).
     */
    suspend fun artistPage(id: String): AppleArtist? {
        artistCache[id]?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        val storefront = storefront()
        val root = page(ArtistPagePath(id, storefront)) ?: return null
        val artist = AppleArtistReader.artist(root) ?: return null
        val views = root["data"].array().orEmpty().firstOrNull()?.obj()?.get("views").obj()
        val row = root["data"].array().orEmpty().firstOrNull()?.obj()
        fun view(name: String): JsonObject? = views?.get(name).obj()

        val page = artist.copy(
            // the same payload already carries the hero video, logo and bio, so the banner costs
            // nothing extra; the canvas provider parses it so there is one parser, not two
            editorial = row?.let { AppleMusicArtistBackgroundProvider.editorialFrom(it) },
            latestRelease = view("latest-release")
                ?.let { runCatching { AppleArtistReader.albums(it) }.getOrNull() }
                ?.firstOrNull(),
            videos = view("music-videos")
                ?.let { runCatching { AppleArtistReader.videos(it) }.getOrNull() }
                .orEmpty(),
            topSongs = view("top-songs")
                ?.let { runCatching { AppleLibrary.songs(it) }.getOrNull() }
                .orEmpty(),
            albums = view("full-albums")
                ?.let { runCatching { AppleArtistReader.albums(it) }.getOrNull() }
                .orEmpty(),
            singles = view("singles")
                ?.let { runCatching { AppleArtistReader.albums(it) }.getOrNull() }
                .orEmpty(),
            compilations = view("compilation-albums")
                ?.let { runCatching { AppleArtistReader.albums(it) }.getOrNull() }
                .orEmpty(),
            similar = view("similar-artists")
                ?.let { runCatching { AppleArtistReader.artists(it) }.getOrNull() }
                .orEmpty(),
        )
        // the songs screens read the same artist; a short cache keeps that from being a second read
        artistCache[id] = page to (System.currentTimeMillis() + ArtistCacheMs)
        return page
    }

    /**
     * The artist resource with every shelf it can carry, and the sizes the page draws.
     *
     * `extend` is the full editorial list (Cider's §F): it brings the bio, the hero video, the logo
     * and `hero` in the same response, so the banner needs no read of its own. The bracket names are
     * percent-encoded because this travels as a plain URL string.
     */
    private fun ArtistPagePath(id: String, storefront: String): String = buildString {
        append("/v1/catalog/").append(storefront).append("/artists/").append(id)
        append("?extend=")
        append("centeredFullscreenBackground,artistBio,bornOrFormed,editorialArtwork,editorialVideo,hero,inFavorites,isGroup,origin")
        append("&views=music-videos,similar-artists,top-songs,full-albums,singles,compilation-albums,latest-release")
        append("&limit%5Btop-songs%5D=10")
        append("&limit%5Bfull-albums%5D=20")
        append("&limit%5Bsingles%5D=20")
        append("&limit%5Bcompilation-albums%5D=12")
        append("&limit%5Bsimilar-artists%5D=12")
        append("&limit%5Bmusic-videos%5D=6")
        append("&limit%5Blatest-release%5D=1")
    }

    /**
     * An artist's id from their name, for the places that only know a name — Apple's album resource
     * carries no artist id, and its artists relationship is not always included.
     */
    /**
     * An Apple profile and the playlists it shares, the resource Cider reads
     * (`apple-api-reference.md` §A): `/v1/social/{sf}/social-profiles/{id}` plus
     * `include=shared-playlists`, which is the only place another person's playlists are listed.
     *
     * A private profile answers 404 for this page while its name still hydrates on a playlist, so a
     * null answer is a real state: the caller shows "Apple did not answer", not an error.
     */
    suspend fun socialProfile(profileId: String): ApplePersonProfile? {
        if (!profileId.startsWith("sp.")) return null
        val storefront = storefront()
        return page(
            "/v1/social/$storefront/social-profiles/$profileId?art%5Burl%5D=f" +
                "&include=shared-playlists&limit%5Bshared-playlists%5D=50" +
                "&fields%5Bplaylists%5D=name,artwork,trackCount,curatorName,url,playParams",
        )?.let(AppleProfileReader::profile)
    }

    suspend fun artistIdByName(name: String): String? {
        val storefront = storefront()
        val term = java.net.URLEncoder.encode(name, "UTF-8")
        val page = page(
            "/v1/catalog/$storefront/search?term=$term&types=artists&limit=1" +
                "&fields%5Bartists%5D=url,name,artwork",
        ) ?: return null
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

    /**
     * The file behind an upload — a library song Apple has no catalogue entry for.
     *
     * The same `webPlayback` call the Apple module makes for a catalogue song, with a different
     * body: `universalLibraryId` alone, no `subscriptionAdamId` (`cider/uploads.md`, captured live
     * on an account with 74 uploads). Apple answers one asset carrying an `md5` and a signed
     * blobstore URL — no HLS playlist and no Widevine key — so the reply is a plain m4a the player
     * can open as a file.
     *
     * Null means Apple had no file to give for this id, not that it wanted a licence.
     */
    suspend fun uploadPlaybackUrl(libraryId: String): String? {
        val response = AccountHttp.post(
            url = WebPlaybackUrl,
            headers = tokens.headers,
            body = """{"universalLibraryId":"$libraryId"}""",
            service = "Apple Music",
        )
        if (response.status !in 200..299) return null
        val page = AccountHttp.parse(response.body) ?: return null
        val song = page["songList"].array().orEmpty().firstOrNull()?.obj() ?: return null
        // an upload's asset is the one with an `md5`; a catalogue answer is a set of flavours
        return song["assets"].array().orEmpty()
            .mapNotNull { it.obj() }
            .firstOrNull { it.string("md5") != null }
            ?.string("URL")
    }

    /** One linked playlist's entries, in order, with each entry's library id. */
    suspend fun playlistTracks(playlistId: String, maxPages: Int = 400): List<RemoteSong> =
        all("/v1/me/library/playlists/$playlistId/tracks?limit=100", maxPages, AppleLibrary::songs, keep = "limit=100")

    private suspend fun <T> all(
        first: String,
        maxPages: Int,
        parse: (JsonObject) -> List<T>,
        onPage: (Int) -> Unit = {},
        /**
         * Query parameters the first page asked for and every page after it must keep.
         *
         * Apple's `next` is a bare offset URL, and it drops everything else. Without `include`
         * page two of the library read comes back with no `relationships`, so 1,300 songs arrive
         * with no album — and without `limit` it answers 25 rows where the first page answered 100,
         * which is why a 1,489-song library cost ~60 requests instead of 15.
         */
        keep: String = "",
    ): List<T> {
        val out = mutableListOf<T>()
        var url = first
        var pages = 0
        val kept = keep.split('&')
        while (pages++ < maxPages) {
            val page = page(url) ?: break
            out += parse(page)
            // reported per page, not per call: a fifteen-page read that only speaks at the end is a
            // counter stuck on zero for half a minute
            onPage(out.size)
            val next = AppleLibrary.nextPage(page) ?: break
            url = Host + withKeptParams(next, kept)
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

    internal companion object {
        const val Host = "https://amp-api.music.apple.com"

        /** playback's own host, not amp-api: the call every Apple player makes for a stream */
        const val WebPlaybackUrl = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback"

        /**
         * Forget one artist's page. The map is a ten-minute memory on top of the page cache, and a
         * pull on an artist's page has to outrun both or the refresh would read its own copy back.
         */
        fun forgetArtist(id: String) {
            artistCache.remove(id)
        }

        /** the artist pages read this run, with the moment they expire */
        val artistCache = java.util.concurrent.ConcurrentHashMap<String, Pair<AppleArtist, Long>>()
        const val ArtistCacheMs = 1000L * 60 * 10
    }
}

/**
 * Apple's `next` is a bare offset URL: it carries `offset` and nothing else the first request asked
 * for. Re-attach only the parameters it is missing, so a page keeps `limit` (100 rows, not 25) and
 * `include` (the album on every song) without duplicating what `next` already has.
 */
internal fun withKeptParams(next: String, kept: List<String>): String {
    val missing = kept
        .map { it.trim().removePrefix("?").removePrefix("&") }
        .filter { '=' in it }
        .filter { pair ->
            val key = pair.substringBefore('=')
            "?$key=" !in next && "&$key=" !in next
        }
    if (missing.isEmpty()) return next
    return next + (if ('?' in next) "&" else "?") + missing.joinToString("&")
}
