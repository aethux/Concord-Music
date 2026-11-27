// Concord: new — Apple's music-video stream, resolved the way its audio stream is.
//
// Probed on this account 2026-11-26: `POST play.itunes.apple.com/…/webPlayback` with a music video's
// adam id (and no `subscriptionAdamId`) answers 200 with ONE `songList` entry whose keys are
// kebab-case — `hls-playlist-url`, `hls-key-cert-url`, `hls-key-server-url`, `widevine-cert-url` —
// and an empty `assets` list. That is why the audio path in AppleMusicModule (which reads camelCase
// `hlsKeyServerURL` and `assets`) draws nothing for a video: the shape is different, the stream is
// not.
//
// This file turns that response into the three strings the player needs. It does not do the licence
// handshake; AppleMusicModule already owns one, and the video branch should pass these values to it.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import java.net.URL

object AppleVideoStream {

    /**
     * A video's stream: the HLS playlist, and the DRM values the module's licence step needs. All
     * three are null-safe because Apple omits the DRM half on some accounts.
     */
    data class Stream(
        val playlistUrl: String,
        val keyServerUrl: String?,
        val keyCertUrl: String?,
        val widevineCertUrl: String?,
    )

    /** One webPlayback call for the video's adam id; null when Apple answers nothing playable. */
    suspend fun resolve(tokens: AppleTokens.Pair, videoId: String): Stream? = withContext(Dispatchers.IO) {
        val body = """{"salableAdamId":"$videoId","language":"en-US"}"""
        val connection = (URL(AppleClient.WebPlaybackUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            tokens.headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        runCatching {
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode !in 200..299) return@runCatching null
            val json = kotlinx.serialization.json.Json.parseToJsonElement(
                connection.inputStream.bufferedReader().readText(),
            ).obj() ?: return@runCatching null
            // the response is a list of the ids it answered for; take the entry for this video
            val entry = json["songList"].array().orEmpty()
                .mapNotNull { it.obj() }
                .firstOrNull() ?: return@runCatching null
            val playlist = entry.string("hls-playlist-url") ?: return@runCatching null
            Stream(
                playlistUrl = playlist,
                keyServerUrl = entry.string("hls-key-server-url"),
                keyCertUrl = entry.string("hls-key-cert-url"),
                widevineCertUrl = entry.string("widevine-cert-url"),
            )
        }.getOrNull()
    }
}
