// Concord: new — an Apple station, as far as this app needs one.
//
// A station has no track list of its own: `/v1/catalog/{storefront}/stations/{id}` names it and
// draws it, and the songs come a page at a time from `/v1/me/stations/next-tracks/{id}`. That is
// why a station is a queue here and not a page.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

data class AppleStation(
    val id: String,
    val name: String,
    /** artwork template; the same {w}x{h} placeholders the rest of Apple's art uses */
    val artworkUrl: String?,
    /** Apple's own word: `songSeeded`, `genre`, `discovery`… */
    val kind: String?,
    val isLive: Boolean,
    val url: String?,
    /**
     * The exact parameters `/v1/play/assets` asks for. A station Apple serves as tracks has no
     * stream asset; one Apple streams (`format: "stream"`) is played from this instead.
     */
    val play: PlayParams? = null,
) {
    data class PlayParams(
        val id: String,
        val kind: String?,
        /** `tracks` (a queue of songs) or `stream` (one live/programmed stream) */
        val format: String?,
        val hasDrm: Boolean,
        val mediaType: Int?,
        val stationHash: String?,
        val streamingKind: String?,
    )
}

object AppleStationReader {

    fun station(page: JsonObject): AppleStation? {
        val row = page["data"].array().firstOrNull()?.obj() ?: return null
        val attributes = row["attributes"].obj()
        return AppleStation(
            id = row.idString("id") ?: return null,
            name = attributes?.string("name") ?: return null,
            artworkUrl = attributes?.get("artwork").obj()?.string("url"),
            kind = attributes?.string("kind"),
            isLive = attributes?.string("isLive") == "true",
            url = attributes?.string("url"),
            play = attributes?.get("playParams").obj()?.let { play ->
                val playId = play.string("id") ?: return@let null
                AppleStation.PlayParams(
                    id = playId,
                    kind = play.string("kind"),
                    format = play.string("format"),
                    hasDrm = (play["hasDrm"] as? JsonPrimitive)?.booleanOrNull == true,
                    mediaType = (play["mediaType"] as? JsonPrimitive)?.intOrNull,
                    stationHash = play.string("stationHash"),
                    streamingKind = play.string("streamingKind"),
                )
            },
        )
    }
}
