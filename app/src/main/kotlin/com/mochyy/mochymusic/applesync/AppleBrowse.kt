// Concord: new — Apple's Browse ("New") page: the editorial tree behind `groupings?name=music`.
//
// One read answers the whole page (probed 2026-11-27: 972 KB, 19 elements on this account). The
// element kinds that carry cards are the same ones the Radio page reads, so the card parser is
// `AppleRadioReader`'s and not a second copy; what Browse adds is the **song shelf** (songs, not
// cards) and the **room**, which is what a section's "View all" opens. A room's id is the section's
// own id on every element probed — `/v1/editorial/{sf}/rooms/{id}` answers 200 while
// `/groupings/{id}` is a 404 for the same id, so the room is the see-all and the grouping is not.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One row of Apple's New page. A section is either cards or songs, never both. */
sealed interface AppleBrowseSection {
    val id: String

    /** Apple's title; empty on the hero row, which prints none */
    val title: String

    /** what "View all" opens: Apple's room for this section */
    val roomId: String

    data class Cards(
        override val id: String,
        override val title: String,
        override val roomId: String,
        val items: List<AppleRadioItem>,
        /**
         * Apple's own chart rows (Daily Top 100, City Charts): their cards rank on a chart, which
         * is what sends the row to the Charts page instead of to its room.
         */
        val charts: Boolean = false,
    ) : AppleBrowseSection

    data class Songs(
        override val id: String,
        override val title: String,
        override val roomId: String,
        val items: List<AppleSearchItem>,
    ) : AppleBrowseSection
}

/** A section's whole list (the room), whatever kind it holds. */
data class AppleBrowseRoom(
    val title: String,
    val cards: List<AppleRadioItem> = emptyList(),
    val songs: List<AppleSearchItem> = emptyList(),
)

object AppleBrowseReader {

    private const val HeroKind = "316"
    private const val CardsKind = "326"
    private const val SongsKind = "327"

    fun sections(page: JsonObject): List<AppleBrowseSection> =
        AppleRadioReader.editorialChildren(page).mapNotNull { node -> node.obj()?.let(::section) }

    /** One room resource: its own title, and the list it holds. */
    fun room(page: JsonObject): AppleBrowseRoom? {
        val resource = page["data"].array().firstOrNull()?.obj() ?: return null
        val attributes = resource["attributes"].obj()
        val title = attributes?.string("title") ?: attributes?.string("name") ?: ""
        return AppleBrowseRoom(
            title = title,
            cards = AppleRadioReader.cards(resource),
            songs = songsOf(resource),
        )
    }

    private fun section(node: JsonObject): AppleBrowseSection? {
        val attributes = node["attributes"].obj() ?: return null
        val id = node.idString("id") ?: return null
        val title = attributes.string("name") ?: attributes.string("title") ?: ""
        // the room is the see-all; on every element probed it is also the section's own id
        val roomId = node["relationships"].obj()?.get("room").obj()?.get("data").array()
            .firstOrNull()?.obj()?.idString("id") ?: id
        return when (attributes.string("editorialElementKind")) {
            HeroKind -> AppleBrowseSection.Cards(id, title, roomId, AppleRadioReader.heroCards(node))
                .takeIf { it.items.isNotEmpty() }
            CardsKind -> {
                // a chart row is the one whose cards carry `isChart`; the card itself stays the
                // Radio page's parser, and the flag rides beside the list it describes
                val charts = node["relationships"].obj()?.get("contents").obj()?.get("data").array()
                    .any { element ->
                        val row = element.obj()?.get("attributes").obj()
                        (row?.get("isChart") as? JsonPrimitive)?.booleanOrNull == true
                    }
                AppleBrowseSection.Cards(id, title, roomId, AppleRadioReader.cards(node), charts)
                    .takeIf { it.items.isNotEmpty() }
            }
            SongsKind -> AppleBrowseSection.Songs(id, title, roomId, songsOf(node))
                .takeIf { it.items.isNotEmpty() }
            // the genre link list (322) and "More to Explore" (391): both carry `links` instead of
            // contents, and every link is a music.apple.com WebObjects page, so neither is a row of
            // cards this app can draw — the Charts row is reached through Daily Top 100 instead
            else -> null
        }
    }

    /** a song shelf's rows — the search page's own song parser, so a row looks the same */
    private fun songsOf(node: JsonObject): List<AppleSearchItem> =
        node["relationships"].obj()?.get("contents").obj()?.get("data").array()
            .mapNotNull { element ->
                val row = element.obj() ?: return@mapNotNull null
                // a room of albums would parse as rows of "songs" without this: the search parser
                // only wants a name and an id, which an album has too
                if (row.string("type") != "songs") return@mapNotNull null
                AppleSearchReader.rowOf(row, AppleSearchItem.Kind.Song)
            }
}
