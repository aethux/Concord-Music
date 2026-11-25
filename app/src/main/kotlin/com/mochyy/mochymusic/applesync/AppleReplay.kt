// Concord: new — Apple's Replay, read the way the web app reads it.
//
// Cider's audit left Replay "unresolved" (`music-summaries/search` 400s). It answers with the
// period named: `?period=year&fields[music-summaries]=period,year` returns one row per year plus
// `meta.latestYear`, and `/v1/me/music-summaries/{id}?include=playlist` carries the year's Replay
// playlist as a catalogue `pl.` resource, so a tap lands on the playlist page the app already has.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.obj
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One year Apple keeps a Replay for; [summaryId] is what `/v1/me/music-summaries/{id}` answers. */
data class AppleReplayYear(val year: Int, val summaryId: String)

/**
 * What a screen has to answer for the Replay card: the years Apple keeps, and the playlist one year
 * is. Home and a shelf's page both implement it, so the card opens the same way from either.
 */
interface AppleReplaySource {
    suspend fun replayYears(): List<AppleReplayYear>
    suspend fun replayPlaylistId(year: AppleReplayYear): String?
}

object AppleReplayReader {

    /** the years `search` lists together with `meta.latestYear`, newest first */
    fun years(page: JsonObject): List<AppleReplayYear> {
        val found = page["data"].array().mapNotNull { ref ->
            val row = ref.obj() ?: return@mapNotNull null
            val id = row.idString("id") ?: return@mapNotNull null
            val year = row["attributes"].obj()?.get("year")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }?.toIntOrNull() ?: return@mapNotNull null
            AppleReplayYear(year, id)
        }
        // a year that has started but not finished is only announced here
        val latest = page["meta"].obj()?.get("latestYear")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }?.toIntOrNull()
        val all = found + listOfNotNull(latest?.let { AppleReplayYear(it, "year-$it") })
        return all.distinctBy { it.year }.sortedByDescending { it.year }
    }

    /** the `pl.` playlist one year's summary carries */
    fun playlistId(page: JsonObject): String? =
        page["data"].array().firstOrNull()?.obj()
            ?.get("relationships").obj()?.get("playlist").obj()
            ?.get("data").array()?.firstOrNull()?.obj()?.idString("id")
}
