// Concord: new — Apple's Charts page: the catalogue charts for one genre.
//
// Cider's `/am/charts/:id` reads two things: `/v1/catalog/{sf}/genres` for the genre list, then
// `/v1/catalog/{sf}/charts` for the charts of the chosen genre. The charts answer is not the search
// page's `{results: {<type>: {data}}}` shape — every type holds a **list of chart groups**, each
// `{chart, name, orderId, data}`, and `meta.results.order` names the groups in Apple's own draw
// order (`most-played:songs`, `city-top:cityCharts`, …). Both were probed 2026-11-27 on storefront
// `in`: the root genre returned six groups, Pop three, and no `genre` at all fell back to the root.
//
// The rows themselves are the readers this app already has: a song chart is the search page's song
// row, every other chart is a Radio-page card, so nothing here re-parses an album or a playlist.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One row of `/v1/catalog/{sf}/genres`. The first row Apple sends is the root ("Music"). */
data class AppleGenre(val id: String, val name: String)

/** One chart: Apple's own name for it, and the rows it ranks. A group is songs or cards, never both. */
data class AppleChartGroup(
    /** Apple's `orderId` (`most-played:songs`), the page's stable key for the group */
    val id: String,
    val title: String,
    val songs: List<AppleSearchItem> = emptyList(),
    val cards: List<AppleRadioItem> = emptyList(),
)

/** One genre's charts, in Apple's order. */
data class AppleChartsPage(
    val genre: String,
    val groups: List<AppleChartGroup>,
)

object AppleChartsReader {

    fun genres(page: JsonObject): List<AppleGenre> =
        page["data"].array().mapNotNull { element ->
            val row = element.obj() ?: return@mapNotNull null
            val attributes = row["attributes"].obj() ?: return@mapNotNull null
            val id = row.idString("id") ?: return@mapNotNull null
            val name = attributes.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AppleGenre(id, name)
        }

    /**
     * The page, in `meta.results.order`. A group the order does not name is appended, so a chart
     * Apple starts sending tomorrow still draws instead of being dropped.
     */
    fun charts(genre: String, page: JsonObject): AppleChartsPage? {
        val results = page["results"].obj() ?: return null
        val order = page["meta"].obj()
            ?.get("results").obj()
            ?.get("order").array()
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val keys = if (order.isEmpty()) results.keys.toList() else order
        val groups = keys.mapNotNull { orderId ->
            // `most-played:songs` and `city-top:cityCharts`: the part after the colon is the key
            // in `results`, the part before it is the chart kind
            val type = orderId.substringAfter(':').ifBlank { orderId }
            val charts = results[type].array().mapNotNull { it.obj() }
            val node = charts.firstOrNull { it.string("orderId") == orderId } ?: charts.firstOrNull()
            // the group's id is Apple's own `orderId` whether or not `meta` named it, so a row's
            // key does not change when the order list is missing
            node?.let { chart(it, it.string("orderId") ?: orderId) }
        }
        return AppleChartsPage(genre, groups)
    }

    private fun chart(node: JsonObject, orderId: String): AppleChartGroup? {
        val rows = node["data"].array().mapNotNull { it.obj() }
        if (rows.isEmpty()) return null
        val title = node.string("name")?.takeIf { it.isNotBlank() }
            ?: node.string("shortName")?.takeIf { it.isNotBlank() }
            ?: return null
        // a row's own type decides how it is read: a "Top Playlists" chart ranks playlists even
        // though the group is named after the chart, not the type
        val songs = rows.filter { it.string("type") == "songs" }
            .mapNotNull { AppleSearchReader.rowOf(it, AppleSearchItem.Kind.Song) }
        val cards = rows.filter { it.string("type") != "songs" }
            .mapNotNull { AppleRadioReader.item(it) }
        if (songs.isEmpty() && cards.isEmpty()) return null
        return AppleChartGroup(id = orderId, title = title, songs = songs, cards = cards)
    }
}
