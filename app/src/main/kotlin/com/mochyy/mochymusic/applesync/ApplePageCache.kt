// Concord: new — the page-payload cache: a detail page opened twice paints from disk.
//
// The shape is Cider's `content/<id>` layer (cider/client-architecture.md:83,93): one row per page
// holding the raw API response, invalidated by writes and refreshed in the background. Two
// differences, both this repo's own: a lifetime per kind decides when a refresh is worth starting
// (Cider revalidates on a 60 s timer; there is no timer here), and the rows are capped so the cache
// cannot grow without bound.
//
// It sits behind `AppleClient`'s one read funnel, so a hit and a miss parse the same body. The
// library list, search, ratings and the storefront are deliberately not cacheable — the allowlist in
// `pageKey` is the whole policy, and anything it does not recognise is a normal read.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.applesync.db.AppleSyncDao
import com.mochyy.mochymusic.applesync.db.PageCacheEntity
import com.mochyy.mochymusic.importer.account.AccountHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** What a page is. The lifetime is how old a row may be before a refresh is worth starting. */
enum class PageKind(val key: String, val ttlMs: Long) {
    ARTIST("artist", 24L * 60 * 60 * 1000),
    ALBUM("album", 6L * 60 * 60 * 1000),
    PLAYLIST("playlist", 6L * 60 * 60 * 1000),
    HOME("home", 15L * 60 * 1000),

    /** the Radio page's section tree: one big read, and Apple edits its sections slowly */
    RADIO("radio", 15L * 60 * 1000),

    /** Apple's Browse ("New") page: the same kind of daily-edited tree as Radio */
    BROWSE("browse", 15L * 60 * 1000),

    /** One section's "View all" room — 100 items, and Apple edits a room far less often */
    BROWSE_ROOM("browse_room", 6L * 60 * 60 * 1000),

    /**
     * The storefront the account is in. Not a page, but it is read in front of every page —
     * `artistPage`, `catalogAlbum` and `playlistPage` each ask it first — so leaving it live put one
     * request in front of every page in the app. It changes when the account moves storefront, and
     * the refresh-behind-the-paint path picks that up after the lifetime.
     */
    STOREFRONT("storefront", 24L * 60 * 60 * 1000),
}

/** The rows, without Room, so the policy below is testable on the JVM. */
interface ApplePageStore {
    suspend fun page(key: String): PageCacheEntity?
    suspend fun put(page: PageCacheEntity)
    suspend fun touch(key: String, at: Long)
    suspend fun delete(kind: String, ids: Collection<String>)
    suspend fun clearKind(kind: String)
    suspend fun count(): Int
    suspend fun bytes(): Long
    suspend fun evict(count: Int)
}

internal class RoomApplePageStore(private val dao: AppleSyncDao) : ApplePageStore {
    override suspend fun page(key: String): PageCacheEntity? = dao.page(key)
    override suspend fun put(page: PageCacheEntity) = dao.savePage(page)
    override suspend fun touch(key: String, at: Long) = dao.touchPage(key, at)
    override suspend fun delete(kind: String, ids: Collection<String>) = dao.clearPagesOf(kind, ids.toList())
    override suspend fun clearKind(kind: String) = dao.clearPages(kind)
    override suspend fun count(): Int = dao.pageCount()
    override suspend fun bytes(): Long = dao.pageBytes()
    override suspend fun evict(count: Int) = dao.evictPages(count)
}

class ApplePageCache(
    private val store: ApplePageStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val inflight = ConcurrentHashMap<String, CompletableDeferred<Result<JsonObject?>>>()

    /**
     * One request, the way a screen reads it.
     *
     * A row on disk answers immediately. A row past its lifetime answers *and* starts a refresh
     * behind the paint. Only a miss waits for Apple — and a URL this cache does not recognise never
     * touches the table at all.
     */
    suspend fun page(url: String, fetch: suspend () -> JsonObject?): JsonObject? {
        val key = pageKey(url) ?: return fetch()
        val at = now()
        val row = store.page(key.value)
        if (row != null) {
            val cached = AccountHttp.parse(row.body)
            if (cached != null) {
                // the size cap evicts by this column; a hit an hour ago is recent enough
                if (at - row.lastUsedAt > TouchEveryMs) store.touch(key.value, at)
                if (at - row.fetchedAt >= key.kind.ttlMs) refresh(key, fetch)
                return cached
            }
            // a row we cannot read is not a hit: fall through and replace it
        }
        return load(key, fetch)
    }

    /** Drop every stored variant of these ids. A write touched them, so a row would be a lie. */
    suspend fun invalidate(kind: PageKind, ids: Collection<String>) {
        if (ids.isEmpty()) return
        ids.toList().chunked(Chunk).forEach { store.delete(kind.key, it) }
    }

    /**
     * The list-derived pages a finished refresh makes stale. The home feed is built from the
     * library, so the one thing that invalidates it is a library that moved — no stored counter
     * needed, because deleting the rows *is* the invalidation.
     */
    suspend fun invalidateLibrary() = store.clearKind(PageKind.HOME.key)

    /** Start a refresh for one page, unless that page already has one in flight. */
    private fun refresh(key: PageKey, fetch: suspend () -> JsonObject?) {
        if (inflight.containsKey(key.value)) return
        scope.launch { runCatching { load(key, fetch) } }
    }

    /**
     * One fetch per key, however many callers want it: a page opening while its own prefetch runs
     * joins the prefetch instead of asking Apple twice.
     */
    private suspend fun load(key: PageKey, fetch: suspend () -> JsonObject?): JsonObject? {
        val mine = CompletableDeferred<Result<JsonObject?>>()
        inflight.putIfAbsent(key.value, mine)?.let { return it.await().getOrThrow() }
        try {
            val result = runCatching { fetchAndStore(key, fetch) }
            mine.complete(result)
            return result.getOrThrow()
        } finally {
            inflight.remove(key.value, mine)
        }
    }

    private suspend fun fetchAndStore(key: PageKey, fetch: suspend () -> JsonObject?): JsonObject? {
        // null is a 404 (or "no such thing"), and a non-2xx or a failed auth is an exception:
        // neither is ever a row
        val json = fetch() ?: return null
        val at = now()
        runCatching {
            store.put(
                PageCacheEntity(
                    cacheKey = key.value,
                    kind = key.kind.key,
                    remoteId = key.remoteId,
                    paramsHash = key.paramsHash,
                    body = json.toString(),
                    fetchedAt = at,
                    lastUsedAt = at,
                ),
            )
            trim()
        }
        return json
    }

    /** Hold the caps. A body too big to fit anything else is left alone once it is the last row. */
    private suspend fun trim() {
        var rows = store.count()
        var bytes = store.bytes()
        repeat(TrimRounds) {
            val over = rows - MaxRows
            if (over <= 0 && bytes <= MaxBytes) return
            store.evict(if (over > 0) over else maxOf(rows / 10, 1))
            rows = store.count()
            bytes = store.bytes()
        }
    }

    companion object {
        /** Caps, not hints: 500 pages and 20 MB is a phone cache, not an archive. */
        const val MaxRows = 500
        const val MaxBytes = 20L * 1024 * 1024

        private const val Chunk = 400
        private const val TouchEveryMs = 60L * 60 * 1000
        private const val TrimRounds = 64

        /** For the one caller that holds a dao and no cache: the refresh flow. */
        suspend fun invalidateLibrary(dao: AppleSyncDao) = dao.clearPages(PageKind.HOME.key)

        /**
         * The key, or null when this URL is not something worth keeping.
         *
         * The four page reads — an artist and its views, an album and its tracks, a playlist and its
         * tracks — plus the home feed and the storefront. Everything else (library lists, search,
         * ratings, a song's cover lookup, station next-tracks) is a normal read: a stale list is what
         * the sync exists to fix, and a cached rating would be wrong.
         */
        internal fun pageKey(url: String): PageKey? {
            val path = if (url.startsWith("http")) url.substringAfter("://").substringAfter('/', "") else url.trimStart('/')
            val query = path.substringAfter('?', "")
            val p = path.substringBefore('?').split('/').filter { it.isNotEmpty() }
            fun at(i: Int) = p.getOrNull(i)

            val kind: PageKind
            val id: String
            when {
                // /v1/catalog/{sf}/artists/{id} and /v1/catalog/{sf}/artists/{id}/view/{view}
                p.size in 5..7 && at(0) == "v1" && at(1) == "catalog" && at(3) == "artists" &&
                    (p.size == 5 || (p.size == 7 && at(5) == "view")) -> {
                    kind = PageKind.ARTIST
                    id = p[4]
                }
                // /v1/catalog/{sf}/albums/{id}[/tracks]
                p.size in 5..6 && at(0) == "v1" && at(1) == "catalog" && at(3) == "albums" &&
                    (p.size == 5 || at(5) == "tracks") -> {
                    kind = PageKind.ALBUM
                    id = p[4]
                }
                // /v1/me/library/albums/{id}[/tracks]
                p.size in 5..6 && at(0) == "v1" && at(1) == "me" && at(2) == "library" && at(3) == "albums" &&
                    (p.size == 5 || at(5) == "tracks") -> {
                    kind = PageKind.ALBUM
                    id = p[4]
                }
                // /v1/catalog/{sf}/playlists/{id}[/tracks]
                p.size in 5..6 && at(0) == "v1" && at(1) == "catalog" && at(3) == "playlists" &&
                    (p.size == 5 || at(5) == "tracks") -> {
                    kind = PageKind.PLAYLIST
                    id = p[4]
                }
                // /v1/me/library/playlists/{id}[/tracks]
                p.size in 5..6 && at(0) == "v1" && at(1) == "me" && at(2) == "library" && at(3) == "playlists" &&
                    (p.size == 5 || at(5) == "tracks") -> {
                    kind = PageKind.PLAYLIST
                    id = p[4]
                }
                // /v1/me/recommendations — Apple's own home feed
                p.size == 3 && at(0) == "v1" && at(1) == "me" && at(2) == "recommendations" -> {
                    kind = PageKind.HOME
                    id = ""
                }
                // /v1/editorial/{sf}/groupings?name=radio — the Radio page's whole section tree
                p.size == 4 && at(0) == "v1" && at(1) == "editorial" && at(3) == "groupings" &&
                    query.contains("name=radio") -> {
                    kind = PageKind.RADIO
                    id = "radio"
                }
                // /v1/editorial/{sf}/groupings?name=music — Apple's Browse page, the same shape
                p.size == 4 && at(0) == "v1" && at(1) == "editorial" && at(3) == "groupings" &&
                    query.contains("name=music") -> {
                    kind = PageKind.BROWSE
                    id = "music"
                }
                // /v1/editorial/{sf}/rooms/{id} — one section's "View all" list
                p.size == 5 && at(0) == "v1" && at(1) == "editorial" && at(3) == "rooms" -> {
                    kind = PageKind.BROWSE_ROOM
                    id = p[4]
                }
                // /v1/me/storefront — asked before every page, so it is worth one row
                p.size == 3 && at(0) == "v1" && at(1) == "me" && at(2) == "storefront" -> {
                    kind = PageKind.STOREFRONT
                    id = ""
                }
                else -> return null
            }
            return PageKey(kind, id, hashOf(query))
        }

        /**
         * A stable hash of the query with its pairs sorted, so `?views=…` and `?extend=…` on one id
         * are two rows and neither can answer the other. Folding `&` order also keeps two spellings
         * of one request together.
         */
        internal fun hashOf(query: String): String {
            val sorted = query.split('&').filter { it.isNotEmpty() }.sorted().joinToString("&")
            val digest = MessageDigest.getInstance("SHA-256").digest(sorted.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        }
    }
}

internal data class PageKey(val kind: PageKind, val remoteId: String, val paramsHash: String) {
    val value: String get() = kind.key + "|" + remoteId + "|" + paramsHash
}
