// Concord: new — Apple's own play report, the shape read out of MusicKit (concord/cider/play-reporting.md).
//
// A play is a PLAY_START / PLAY_END pair to universal-activity-service, and it is held until it
// counts: min(30 % of the song, 60 s) of real playing time. A song skipped before that sends nothing.
// Apple's own client sends its start late for exactly this reason — `milliseconds-since-play` is
// computed when the request goes out, from when the event happened.
//
// No guard, no audit row and no re-login prompt: this is telemetry, not a change to anything the
// person owns, and a failure here must never look like a write or a lapsed session.
package com.mochyy.mochymusic.applesync

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.mochyy.mochymusic.importer.account.AccountHttp
import com.mochyy.mochymusic.models.MediaMetadata
import java.util.TimeZone
import java.util.UUID
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class ApplePlayReporter(
    private val context: Context,
    // read fresh each time, the service swaps in a new scope after its old one ends
    private val scope: () -> CoroutineScope,
    // where the player is in the song, only read on the main thread
    private val positionMs: () -> Long,
) {

    /** The app's switch, with offline mode folded in by the service. */
    var isEnabled = true
        set(value) {
            field = value
            if (!value) forget()
        }

    /** Private listening: the play is never sent at all. */
    var privateListening = false
        set(value) {
            field = value
            if (value) forget()
        }

    // its own scope so the last report of a song still goes out while the service shuts down
    private val network = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serial = 0
    private var listen: Listen? = null

    private class Listen(
        /** the player's own id for the row, to notice a song swapped before its stop arrived */
        val songId: String,
        val catalogId: String,
        val durationMs: Long,
        /** when the song actually started: a late PLAY_START is timed from here */
        val startedAtMs: Long,
        /** playing time still to go before the play counts */
        var remainingMs: Long,
    ) {
        var counted = false
        var runningSince = 0L
        /** a stretch is open: Apple has the START and is waiting for its END */
        var open = false

        /** where the open stretch began: the START's position, and the END's start-position */
        var stretchStartMs = 0L
        var job: Job? = null
    }

    fun onSongStart(metadata: MediaMetadata?, durationMs: Long) {
        onSongStop()
        val catalogId = metadata?.appleCatalogId() ?: return
        val lengthMs = if (durationMs > 0) durationMs else metadata.duration * 1000L
        val song = Listen(metadata.id, catalogId, lengthMs, System.currentTimeMillis(), countAfter(lengthMs))
        listen = song
        resume(song)
    }

    fun onPlayingChanged(isPlaying: Boolean, metadata: MediaMetadata?, durationMs: Long) {
        // a skip while crossfading can swap the song before the stop arrives
        if (listen != null && metadata?.id != null && metadata.id != listen?.songId) {
            onSongStop()
        }
        val song = listen
        when {
            !isPlaying -> pause(song)
            song == null -> onSongStart(metadata, durationMs)
            else -> resume(song)
        }
    }

    fun onSongStop() {
        val song = listen ?: return
        listen = null
        close(song)
    }

    private fun forget() {
        val song = listen ?: return
        listen = null
        close(song)
    }

    /** The song is over or dropped: an open stretch gets its END, so Apple is not left waiting for one. */
    private fun close(song: Listen) {
        song.job?.cancel()
        song.job = null
        song.runningSince = 0
        if (song.open) {
            song.open = false
            send(song, start = false, endAtMs = positionMs())
        }
    }

    private fun resume(song: Listen) {
        if (song.runningSince != 0L) return
        song.runningSince = SystemClock.elapsedRealtime()
        if (song.counted) {
            // a resume after a pause is its own stretch, opened where the player is
            if (!song.open) {
                song.open = true
                song.stretchStartMs = positionMs()
                send(song, start = true, eventAtMs = System.currentTimeMillis())
            }
            return
        }
        if (song.remainingMs <= 0) return
        song.job = scope().launch {
            delay(song.remainingMs)
            song.remainingMs = 0
            song.job = null
            if (listen !== song) return@launch
            song.counted = true
            song.open = true
            // timed from when the song began, so the delay is the play's real elapsed time
            send(song, start = true, eventAtMs = song.startedAtMs)
        }
    }

    private fun pause(song: Listen?) {
        song ?: return
        if (song.runningSince == 0L) return
        song.job?.cancel()
        song.job = null
        song.remainingMs = (song.remainingMs - (SystemClock.elapsedRealtime() - song.runningSince)).coerceAtLeast(1)
        song.runningSince = 0
        if (song.open) {
            song.open = false
            send(song, start = false, endAtMs = positionMs())
        }
    }

    private fun send(
        song: Listen,
        start: Boolean,
        endAtMs: Long = 0,
        eventAtMs: Long = System.currentTimeMillis(),
    ) {
        if (!isEnabled || privateListening) return
        network.launch {
            val tokens = runCatching { AppleTokens(context).read() }.getOrNull() ?: return@launch
            val storefront = runCatching { AppleStorefronts.read(context) }.getOrNull()
            val body = buildJsonObject {
                put("client_id", "JSCLIENT")
                put("event_type", "JSPLAY")
                putJsonArray("data") {
                    addJsonObject {
                        // MusicKit's own descriptor carries these three before any field of the play
                        put("id", (++serial).toString())
                        put("client", "JSCLIENT")
                        put("isAlexa", false)
                        put("event-type", if (start) 1 else 0)
                        put("type", 4)
                        putJsonObject("ids") {
                            put("cloud-id", song.catalogId)
                            put("subscription-adam-id", song.catalogId)
                        }
                        put("media-type", 0)
                        put("media-duration-in-milliseconds", song.durationMs)
                        put("start-position-in-milliseconds", song.stretchStartMs)
                        if (!start) put("end-position-in-milliseconds", endAtMs)
                        put("milliseconds-since-play", (System.currentTimeMillis() - eventAtMs).coerceAtLeast(0))
                        put("private-enabled", privateListening)
                        put("is-private", privateListening)
                        put("sb-enabled", true)
                        put("offline", false)
                        put("siri-initiated", false)
                        put("source-type", 10) // MusicKit: the closest of Apple's own values
                        put("utc-offset-in-seconds", utcOffsetSeconds())
                        put("build-version", "Concord/0.1.0 ${Build.VERSION.RELEASE} model/${Build.MODEL}")
                        put("bundle-id", context.packageName)
                        storefront?.let { put("store-front", it) }
                        put("device-name", Build.MODEL)
                        put("persistent-id", UUID.randomUUID().toString())
                        put("developer-token", tokens.developer)
                        put("user-token", tokens.user)
                    }
                }
            }
            val answer = runCatching { AccountHttp.post(Url, tokens.headers, body.toString(), "Apple Music") }.getOrNull()
            android.util.Log.e(
                "ApplePlay",
                "play ${if (start) "start" else "end"} ${song.catalogId} start=${song.stretchStartMs} end=$endAtMs: ${answer?.status} ${answer?.body?.take(120)}",
            )
        }
    }

    private fun utcOffsetSeconds(): Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000

    private fun countAfter(durationMs: Long): Long = min((durationMs * 0.30f).toLong(), MaxWaitMs)

    private companion object {
        const val Url = "https://universal-activity-service.itunes.apple.com/play"
        const val MaxWaitMs = 60_000L
    }
}

/** `AM_<catalogId>` only: an upload's `AM_i.…` id is not a catalogue song, and reporting it needs its own shape. */
private fun MediaMetadata.appleCatalogId(): String? {
    if (!id.startsWith("AM_")) return null
    val catalogId = id.removePrefix("AM_")
    return catalogId.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
}
