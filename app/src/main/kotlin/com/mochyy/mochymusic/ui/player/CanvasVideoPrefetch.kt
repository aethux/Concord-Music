// Concord: new. Warm a card's motion loop before the card is on screen.
//
// Apple's editorial videos are HLS: a master playlist, a variant playlist, then segments. A card that
// starts cold pays a playlist round trip and a segment fetch before its first frame, which reads as
// "the animation only starts once the card appears". Cider preloads its covers; this is the same idea
// on Concord's side: the playlists and the first segments go into the media cache the cards read
// from, so the card's own ExoPlayer finds them on disk.
package com.mochyy.mochymusic.ui.player

import android.content.Context
import android.net.Uri
import androidx.core.content.getSystemService
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections

@UnstableApi
internal object CanvasVideoPrefetch {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet())

    // two loops at a time: twenty parallel downloads starve the card being watched, which is the
    // stall this exists to remove
    private val gate = Semaphore(PARALLEL_WARMS)

    /** how a warm-up fetches what it has to read: the same client the players use, no cache in the way */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().cache(null).build()
    }

    /**
     * Warm these loops in the background. Called with every card of a shelf the person is about to
     * see; a url already warmed, or being warmed, costs nothing the second time.
     */
    fun warm(context: Context, urls: List<String>) {
        val app = context.applicationContext
        // a whole loop is a few megabytes each; on a metered connection the cards stream instead
        if (app.getSystemService<android.net.ConnectivityManager>()?.isActiveNetworkMetered == true) return
        urls.filter { it.isNotBlank() && inFlight.add(it) }.forEach { url ->
            scope.launch {
                gate.acquire()
                val ok = runCatching { warmOne(app, url) }
                gate.release()
                // a failure keeps it out of inFlight so a later pass can try again
                if (ok.isFailure && !wasCached(app, url)) inFlight.remove(url)
            }
        }
    }

    private fun warmOne(context: Context, url: String): Boolean {
        // the variant playlist first: the master has to be read to know which one to fetch
        val master = fetchText(url) ?: return false
        val variant = variantUrl(url, master) ?: url
        val playlist = if (variant == url) master else fetchText(variant) ?: return false
        cache(context, url)
        if (variant != url) cache(context, variant)
        // every segment of the loop, not the first two: a half-warm loop stalls mid-playback while
        // the card is on screen, which is the pause this exists to remove
        segments(playlist).forEach { cache(context, absolute(variant, it)) }
        return true
    }

    /**
     * The variant to warm: the smallest bandwidth that is still a picture rather than a placeholder.
     * A card is 170 dp, and the phone is already decoding several loops.
     */
    private fun variantUrl(base: String, master: String): String? {
        val lines = master.lineSequence().map { it.trim() }.toList()
        var bandwidth = -1
        var best: String? = null
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val b = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val uri = lines.getOrNull(i + 1)?.takeIf { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            if (b <= MaxBandwidth && b > bandwidth) {
                best = uri
                bandwidth = b
            }
        }
        return best?.let { absolute(base, it) } ?: lines.firstOrNull {
            it.isNotEmpty() && !it.startsWith("#")
        }?.let { absolute(base, it) }
    }

    private fun fetchText(url: String): String? = runCatching {
        val request = Request.Builder().url(url).header("Accept", "*/*").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    }.getOrNull()

    private fun segments(playlist: String): List<String> =
        playlist.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            // an fMP4 loop is one file cut by #EXT-X-BYTERANGE: the same name repeats per segment,
            // and caching it once covers every range the player will ask for
            .distinct()
            .toList()

    private fun absolute(base: String, uri: String): String =
        runCatching { java.net.URI(base).resolve(uri).toString() }.getOrDefault(uri)

    /** the bytes into the cache the cards play from, keyed by the request's own url */
    private fun cache(context: Context, url: String) {
        val cache = CanvasVideoCache.get(context)
        val upstream = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http))
        val source = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        val spec = DataSpec.Builder().setUri(Uri.parse(url)).build()
        try {
            CacheWriter(source, spec, ByteArray(64 * 1024), null).cache()

        } finally {
            source.close()
        }
    }

    private fun wasCached(context: Context, url: String): Boolean = runCatching {
        val cache = CanvasVideoCache.get(context)
        cache.getKeys().any { key -> key.startsWith(url) }
    }.getOrDefault(false)

    /** the biggest rendition a card can use. Mirrors the light cap the card's player is given. */
    private const val MaxBandwidth = 1_000_000
    private const val PARALLEL_WARMS = 2
}
