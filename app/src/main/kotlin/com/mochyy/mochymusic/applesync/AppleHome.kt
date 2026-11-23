// Concord: new — Apple's Listen Now home feed, read the way the web player reads it.
//
// The page asks one shelf list: `GET /v1/me/recommendations?name=listen-now…&format[resources]=map`.
// `data` is then only a list of references; every group's attributes and every card live in
// `resources`, keyed by type and id. That shape is what carries the shelves Apple's own Home shows —
// "Top Picks for You" and "Find Your Mood" among them — which the plain `include=contents` request
// does not return.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One card in a Listen Now row. [id] is Apple's id for the resource; a playlist's is `p.…`/`pl.…`. */
data class AppleHomeItem(
    val id: String,
    val kind: Kind,
    val title: String,
    /** The artist for an album, the curator for a playlist; null when Apple sent neither. */
    val subtitle: String?,
    /** Artwork template with {w}x{h}, {c} and {f} in it; null when the card has none. */
    val artworkUrl: String?,
) {
    enum class Kind { Album, Playlist, Station }
}

data class AppleHomeGroup(
    val title: String,
    /** Apple's shelf kind, e.g. `MusicNotesHeroShelf` — the shelves Apple draws with large cards. */
    val display: String?,
    val hasSeeAll: Boolean,
    val items: List<AppleHomeItem>,
) {
    /** a hero shelf: Apple draws these big */
    val isHero: Boolean get() = display == AppleHomeReader.HeroShelf
}

object AppleHomeReader {

    const val HeroShelf = "MusicNotesHeroShelf"

    fun groups(page: JsonObject): List<AppleHomeGroup> {
        val resources = page["resources"].obj()
        val recommendations = resources?.get("personal-recommendation").obj()
        return page["data"].array().mapNotNull { ref ->
            val id = ref.obj()?.idString("id") ?: return@mapNotNull null
            val group = recommendations?.get(id).obj() ?: return@mapNotNull null
            val attributes = group["attributes"].obj()
            val title = attributes?.get("title").obj()?.string("stringForDisplay")
                ?: return@mapNotNull null
            val display = attributes?.get("display").obj()?.string("kind")
            // Apple sends this one for an account with no concerts; it has no cards
            if (display == "MusicConcertsEmptyShelf") return@mapNotNull null
            val contents = group["relationships"].obj()
                ?.get("contents").obj()
                ?.get("data").array()
                .orEmpty()
            val items = contents.mapNotNull { card(it.obj(), resources) }
            if (items.isEmpty()) return@mapNotNull null
            AppleHomeGroup(
                title = title,
                display = display,
                hasSeeAll = attributes?.get("hasSeeAll")?.let { (it as? JsonPrimitive)?.contentOrNull } == "true",
                items = items,
            )
        }
    }

    /**
     * Apple's order, with the two shelves the person asked to see earlier moved ahead of the
     * stations: "Find Your Mood" and "Playlists Made for You" come before "Stations for You".
     */
    fun ordered(groups: List<AppleHomeGroup>): List<AppleHomeGroup> {
        val moved = groups.filter { it.title.lowercase() in BeforeStations }
        if (moved.isEmpty()) return groups
        val rest = groups.filterNot { it in moved }
        val at = rest.indexOfFirst { it.title.equals(StationsTitle, ignoreCase = true) }
        if (at < 0) return groups
        return rest.take(at) + moved + rest.drop(at)
    }

    private val BeforeStations = setOf("find your mood", "playlists made for you")
    private const val StationsTitle = "Stations for You"

    private fun card(ref: JsonObject?, resources: JsonObject?): AppleHomeItem? {
        ref ?: return null
        val type = ref.string("type") ?: return null
        val id = ref.idString("id") ?: return null
        val resource = resources?.get(type).obj()?.get(id).obj() ?: return null
        val attributes = resource["attributes"].obj()
        val title = attributes?.string("name") ?: return null
        val kind = when (type) {
            "albums" -> AppleHomeItem.Kind.Album
            "playlists", "library-playlists" -> AppleHomeItem.Kind.Playlist
            "stations" -> AppleHomeItem.Kind.Station
            else -> return null
        }
        val subtitle = when (kind) {
            AppleHomeItem.Kind.Album -> attributes?.string("artistName")
            AppleHomeItem.Kind.Playlist -> attributes?.string("curatorName")
            AppleHomeItem.Kind.Station -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
        return AppleHomeItem(
            id = id,
            kind = kind,
            title = title,
            subtitle = subtitle,
            artworkUrl = attributes?.get("artwork").obj()?.string("url"),
        )
    }
}
