// Concord: new — a station Apple streams, resolved to what the player can actually open.
//
// Two shapes, both from `/v1/play/assets` built out of the station's own `playParams` (the call
// Cider's `playStreamedRadio` makes; probed 2026-11-27, concord/cider/live-radio-streams.md):
//
//  * no DRM   -> one progressive (or clear HLS) URL — the broadcaster tier;
//  * `hasDrm` -> an HLS manifest whose Widevine key is licensed from the asset's `keyServerUrl` with
//                Apple's own licence body. The manifest is rewritten by `AppleRadioHls` on its way to
//                the player, because Apple names the key with a bare key id.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.account.AccountHttp
import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import com.mochyy.mochymusic.module.applemusic.AppleLicence
import com.mochyy.mochymusic.module.applemusic.AppleRadioHls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

object AppleStationStream {

    sealed interface Stream {
        data class Progressive(val url: String, val isHls: Boolean) : Stream
        data class Drm(val playlistUrl: String, val licence: Licence) : Stream
    }

    data class Licence(
        /** the asset's own licence host — `linear.tv.apple.com` on this account, not the song host */
        val keyServerUrl: String,
        /** the Widevine key's `data:` URI as Apple wrote it: the licence body echoes it back */
        val keyUri: String,
        /** what the licence server is asked about — the station's id, which is what Cider sends */
        val adamId: String,
    )

    suspend fun resolve(tokens: AppleTokens.Pair, stationId: String): Stream? = withContext(Dispatchers.IO) {
        val client = AppleClient(tokens)
        val station = runCatching { client.station(stationId) }.getOrNull() ?: return@withContext null
        val play = station.play ?: return@withContext null
        val asset = runCatching { client.stationStreamAsset(play) }.getOrNull() ?: return@withContext null
        val entry = asset["results"].obj()?.get("assets").array().firstOrNull()?.obj()
            ?: return@withContext null
        val url = entry.string("url") ?: return@withContext null

        if (!play.hasDrm) {
            val direct = unwrapTuneIn(url)
            return@withContext Stream.Progressive(
                url = direct,
                isHls = direct.substringBefore('?').endsWith(".m3u8", ignoreCase = true),
            )
        }

        // a DRM station: the asset names the licence host, the manifest names the key
        val keyServer = entry.string("keyServerUrl") ?: return@withContext null
        val playlistUrl = hlsVariant(url) ?: return@withContext null
        val manifest = text(playlistUrl) ?: return@withContext null
        val keyUri = AppleRadioHls.keyUri(manifest) ?: return@withContext null
        Stream.Drm(
            playlistUrl = playlistUrl,
            licence = Licence(keyServerUrl = keyServer, keyUri = keyUri, adamId = stationId),
        )
    }

    /**
     * One licence for one CDM challenge, in Apple's own body. Blocking on purpose: the player asks
     * for it from a loading thread, so this waits on the app's own transport (proxy, DNS, timeouts)
     * the way the module's licensing does.
     */
    fun licence(tokens: AppleTokens.Pair, licence: Licence, challenge: ByteArray): ByteArray {
        val body = String(AppleLicence.body(challenge, licence.keyUri, licence.adamId), Charsets.UTF_8)
        val response = kotlinx.coroutines.runBlocking {
            AccountHttp.post(licence.keyServerUrl, tokens.headers, body, "Apple Music")
        }
        if (response.status !in 200..299) {
            throw java.io.IOException("Apple's radio licence server answered ${response.status}")
        }
        val answer = AccountHttp.parse(response.body)
            ?: throw java.io.IOException("Apple's radio licence answered something unreadable")
        val status = answer["status"]?.jsonPrimitive?.intOrNull
        val key = answer["license"]?.jsonPrimitive?.contentOrNull
        if (status != 0 || key == null) {
            val message = answer["customerMessage"]?.jsonPrimitive?.contentOrNull ?: "status $status"
            throw java.io.IOException("Apple refused the radio licence: $message")
        }
        return java.util.Base64.getDecoder().decode(key)
    }

    /**
     * A TuneIn wrapper names the stream in its own `url` parameter. Its body is not parsed: the first
     * entry in that file is a TuneIn advert, and Cider skips it the same way.
     */
    private fun unwrapTuneIn(url: String): String {
        if (!url.contains("playlist.pls", ignoreCase = true)) return url
        val param = url.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("url=") } ?: return url
        return runCatching { java.net.URLDecoder.decode(param.removePrefix("url="), "UTF-8") }
            .getOrDefault(url)
    }

    /**
     * The AAC-LC variant of a master playlist (the rendition Cider picks too), or the URL itself when
     * it is already a media playlist.
     */
    private suspend fun hlsVariant(masterUrl: String): String? {
        val master = text(masterUrl) ?: return null
        if (!master.contains("#EXT-X-STREAM-INF")) return masterUrl
        var codec: String? = null
        var fallback: String? = null
        for (line in master.lines()) {
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                codec = Regex("""CODECS="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
            } else if (line.isNotBlank() && !line.startsWith("#") && codec != null) {
                val resolved = runCatching { URI(masterUrl).resolve(line.trim()).toString() }.getOrNull()
                if (resolved != null) {
                    if (codec.contains("mp4a.40.2")) return resolved
                    if (fallback == null) fallback = resolved
                }
                codec = null
            }
        }
        return fallback
    }

    private suspend fun text(url: String): String? =
        runCatching { AccountHttp.get(url, emptyMap(), "Apple Music") }
            .getOrNull()
            ?.takeIf { it.status in 200..299 }
            ?.body
}
