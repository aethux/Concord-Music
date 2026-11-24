// Concord: new — the page-payload cache's policy, without Room or the network.
//
// The store is a fake map and the clock is a variable, which is the whole point of `ApplePageStore`
// being an interface: these seven rules are what the cache promises, and none of them needs a device
// to prove.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.applesync.db.PageCacheEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePageCacheTest {

    private class FakeStore : ApplePageStore {
        val rows = LinkedHashMap<String, PageCacheEntity>()
        override suspend fun page(key: String): PageCacheEntity? = rows[key]
        override suspend fun put(page: PageCacheEntity) { rows[page.cacheKey] = page }
        override suspend fun touch(key: String, at: Long) { rows[key]?.let { rows[key] = it.copy(lastUsedAt = at) } }
        override suspend fun delete(kind: String, ids: Collection<String>) {
            rows.values.removeAll { it.kind == kind && it.remoteId in ids }
        }
        override suspend fun clearKind(kind: String) {
            rows.values.removeAll { it.kind == kind }
        }
        override suspend fun count(): Int = rows.size
        override suspend fun bytes(): Long = rows.values.sumOf { it.body.toByteArray().size.toLong() }
        override suspend fun evict(count: Int) {
            rows.values.sortedBy { it.lastUsedAt }.take(count).forEach { rows.remove(it.cacheKey) }
        }
    }

    private val store = FakeStore()
    private var clock = 1_000L
    private val cache = ApplePageCache(
        store = store,
        now = { clock },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private val album = "/v1/catalog/us/albums/abc?include=artists"
    private val artist = "/v1/catalog/us/artists/123?extend=artistBio"
    private val home = "/v1/me/recommendations?name=listen-now&timezone=%2B05%3A30"

    @Test
    fun `a second read is answered from the row, not the network`() = runBlocking {
        var fetches = 0
        val first = cache.page(album) { fetches++; body("one") }
        val second = cache.page(album) { fetches++; body("two") }
        assertEquals(1, fetches)
        assertEquals("one", first.text("v"))
        assertEquals("one", second.text("v"))
        assertEquals(1, store.rows.size)
    }

    @Test
    fun `the query is part of the key, so two spellings never answer each other`() = runBlocking {
        var fetches = 0
        val withArtists = cache.page("/v1/catalog/us/albums/abc?include=artists") { fetches++; body("artists") }
        val withVideo = cache.page("/v1/catalog/us/albums/abc?extend=editorialVideo") { fetches++; body("video") }
        assertEquals(2, fetches)
        assertEquals("artists", withArtists.text("v"))
        assertEquals("video", withVideo.text("v"))
        assertEquals(2, store.rows.size)

        // and the & order inside one request does not split a row in two
        assertEquals(ApplePageCache.hashOf("a=1&b=2"), ApplePageCache.hashOf("b=2&a=1"))
    }

    @Test
    fun `a stale row answers first and refreshes behind the paint`() = runBlocking {
        cache.page(album) { body("old") }
        clock += PageKind.ALBUM.ttlMs

        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val painted = cache.page(album) { fetches++; gate.await(); body("new") }

        assertEquals("old", painted.text("v"))
        assertEquals(1, fetches)
        assertEquals("old", store.rows.values.single().stored().text("v"))

        gate.complete(Unit)
        assertEquals("new", store.rows.values.single().stored().text("v"))
        assertEquals("new", cache.page(album) { error("no third read") }.text("v"))
    }

    @Test
    fun `a fresh row starts no refresh at all`() = runBlocking {
        cache.page(artist) { body("first") }
        clock += PageKind.ARTIST.ttlMs - 1
        var fetches = 0
        val value = cache.page(artist) { fetches++; body("second") }
        assertEquals(0, fetches)
        assertEquals("first", value.text("v"))
    }

    @Test
    fun `one key has one fetch, however many callers want it`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var fetches = 0

        val first = async(Dispatchers.Unconfined) { cache.page(album) { fetches++; gate.await(); body("shared") } }
        val second = async(Dispatchers.Unconfined) { cache.page(album) { fetches++; gate.await(); body("shared") } }

        assertEquals(1, fetches)
        gate.complete(Unit)
        assertEquals("shared", first.await().text("v"))
        assertEquals("shared", second.await().text("v"))
        assertEquals(1, fetches)
    }

    @Test
    fun `a failure or a 404 is never a row`() = runBlocking {
        val failed = runCatching { cache.page(artist) { throw IllegalStateException("429") } }
        assertTrue(failed.isFailure)
        assertEquals(0, store.rows.size)

        assertNull(cache.page(artist) { null })
        assertEquals(0, store.rows.size)
    }

    @Test
    fun `a list, a search or a rating is a normal read`() = runBlocking {
        val uncacheable = listOf(
            "/v1/me/library/songs?limit=100&include=albums",
            "/v1/me/library/playlists?limit=100&include=catalog",
            "/v1/me/library/albums?limit=100",
            "/v1/catalog/us/search?term=faded&types=songs&limit=10",
            "/v1/me/ratings/songs?ids=1,2",
            "/v1/me/storefront",
            "/v1/catalog/us/songs/99?extend=editorialVideo&include=albums",
            "/v1/catalog/us/stations/ra.123",
        )
        var fetches = 0
        uncacheable.forEach { url -> cache.page(url) { fetches++; body("x") } }
        assertEquals(uncacheable.size, fetches)
        assertEquals(0, store.rows.size)
    }

    @Test
    fun `a write's ids drop every variant of those pages and nothing else`() = runBlocking {
        cache.page("/v1/catalog/us/artists/1?extend=artistBio") { body("a") }
        cache.page("/v1/catalog/us/artists/1") { body("b") }
        cache.page("/v1/catalog/us/artists/2") { body("c") }
        cache.page(album) { body("d") }

        cache.invalidate(PageKind.ARTIST, listOf("1"))

        assertEquals(2, store.rows.size)
        assertTrue(store.rows.values.none { it.kind == "artist" && it.remoteId == "1" })
        assertTrue(store.rows.values.any { it.kind == "artist" && it.remoteId == "2" })
        assertTrue(store.rows.values.any { it.kind == "album" })
    }

    @Test
    fun `a finished refresh drops the home feed and keeps the pages`() = runBlocking {
        cache.page(home) { body("h") }
        cache.page(album) { body("a") }
        cache.invalidateLibrary()
        assertEquals(listOf("album"), store.rows.values.map { it.kind })
    }

    @Test
    fun `the row cap evicts the page drawn longest ago`() = runBlocking {
        repeat(ApplePageCache.MaxRows + 5) { i ->
            clock += 1
            cache.page("/v1/catalog/us/albums/$i") { body("page $i") }
        }
        assertEquals(ApplePageCache.MaxRows, store.rows.size)
        assertTrue(store.rows.values.none { it.remoteId == "0" })
        assertTrue(store.rows.values.any { it.remoteId == (ApplePageCache.MaxRows + 4).toString() })
    }

    private fun body(value: String): JsonObject = buildJsonObject { put("v", JsonPrimitive(value)) }

    private fun JsonObject?.text(key: String): String = (this!!.get(key) as JsonPrimitive).content

    private fun PageCacheEntity.stored(): JsonObject = Json.parseToJsonElement(body) as JsonObject
}
