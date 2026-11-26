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
    /**
     * Concord: Apple's motion artwork for this card — an HLS `.m3u8` on `mvod.itunes.apple.com` — or
     * null. Only some cards carry one: every mood and every "Playlists Made for You" card has it,
     * while most album and station cards do not.
     */
    val motionUrl: String?,
    /** the page Apple sends for an editorial card — Replay, Concerts — or null */
    val url: String?,
    /**
     * The artist behind this card, when the card is an artist's.
     *
     * An `artists` card carries its own id here. Apple's "Favourite Artists" shelf, though, sends
     * `stations` with `attributes.kind = "artistOnly"` and the id `ra.a-<artistId>` — a card drawn
     * with the artist's face and name, whose favourite and page are the artist's, not the station's.
     * Null for every other card.
     */
    val artistId: String? = null,
) {
    enum class Kind { Album, Playlist, Station, Artist, Editorial }
}

data class AppleHomeGroup(
    /** Apple's id for this shelf. It is minted per feed read, so it only answers until the feed is read again. */
    val id: String,
    val title: String,
    /** Apple's shelf kind, e.g. `MusicNotesHeroShelf` — the shelves Apple draws with large cards. */
    val display: String?,
    val hasSeeAll: Boolean,
    val items: List<AppleHomeItem>,
    /**
     * A "<Artist> Fans Like" shelf names one artist in its title's `contentIds`. The row's picture is
     * that artist's, not the first card's: a collab album's cover is not a face.
     */
    val artistId: String? = null,
    /** the artist's artwork, read once by the view model (the page cache keeps it for a day) */
    val artistArtwork: String? = null,
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
            groupOf(id, group, resources)
        }
    }

    /**
     * One shelf read on its own (`view/see-all`): Apple answers with that group alone and its whole
     * card list under `contents`, in the same map shape the feed uses.
     */
    fun shelf(page: JsonObject): AppleHomeGroup? {
        val resources = page["resources"].obj()
        val id = page["data"].array().firstOrNull()?.obj()?.idString("id") ?: return null
        val group = resources?.get("personal-recommendation").obj()?.get(id).obj() ?: return null
        return groupOf(id, group, resources)
    }

    private fun groupOf(id: String, group: JsonObject, resources: JsonObject?): AppleHomeGroup? {
        val attributes = group["attributes"].obj()
        val title = attributes?.get("title").obj()?.string("stringForDisplay")
            ?: return null
        val display = attributes?.get("display").obj()?.string("kind")
        // Apple sends this one for an account with no concerts; it has no cards
        if (display == "MusicConcertsEmptyShelf") return null
        val contents = group["relationships"].obj()
            ?.get("contents").obj()
            ?.get("data").array()
            .orEmpty()
        val items = contents.mapNotNull { card(it.obj(), resources) }
        if (items.isEmpty()) return null
        // "Alan Walker Fans Like" carries the artist's own id here; other shelves carry none
        val artistId = attributes?.get("title").obj()?.get("contentIds").array()
            ?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
        return AppleHomeGroup(
            id = id,
            title = title,
            display = display,
            hasSeeAll = attributes?.get("hasSeeAll")?.let { (it as? JsonPrimitive)?.contentOrNull } == "true",
            items = items,
            artistId = artistId,
        )
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
        // an editorial card carries no name: its words are in plainEditorialNotes
        val notes = attributes?.get("plainEditorialNotes").obj()
        val title = attributes?.string("name") ?: notes?.string("name") ?: return null
        val kind = when (type) {
            "albums" -> AppleHomeItem.Kind.Album
            "playlists", "library-playlists" -> AppleHomeItem.Kind.Playlist
            "stations" -> AppleHomeItem.Kind.Station
            // Cider routes an artist card to /am/artist/{id}; ours is apple_artist/{id}
            "artists" -> AppleHomeItem.Kind.Artist
            // Replay, Concerts, moods: a picture, a name and the page Apple serves for it
            "editorial-items" -> AppleHomeItem.Kind.Editorial
            else -> return null
        }
        val subtitle = when (kind) {
            AppleHomeItem.Kind.Album -> attributes?.string("artistName")
            AppleHomeItem.Kind.Playlist -> attributes?.string("curatorName")
            AppleHomeItem.Kind.Editorial -> notes?.string("short") ?: notes?.string("tagline")
            AppleHomeItem.Kind.Artist, AppleHomeItem.Kind.Station -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
        // the square loop is what a square card wants; Apple ships four crops of the same video
        val motion = attributes?.get("editorialVideo").obj()
            ?.get("motionSquareVideo1x1").obj()?.string("video")
            ?: attributes?.get("editorialVideo").obj()?.get("motionDetailSquare").obj()?.string("video")
        // an editorial card's picture is a set of crops, not one url; a square card takes the square one
        val editorialArt = attributes?.get("editorialArtwork").obj()
        val artwork = attributes?.get("artwork").obj()?.string("url")
            ?: listOf("brandLogo", "subscriptionCover", "superHeroWide", "superHeroTall")
                .firstNotNullOfOrNull { editorialArt?.get(it).obj()?.string("url") }
        // an artist's own card is an `artists` row; Apple's artist stations are `stations` whose
        // `kind` says `artistOnly` and whose id carries the artist's (`ra.a-<artistId>`)
        val artistId = when {
            kind == AppleHomeItem.Kind.Artist -> id
            kind == AppleHomeItem.Kind.Station && attributes?.string("kind") == ArtistOnlyStation ->
                id.removePrefix(ArtistStationPrefix)
                    .takeIf { it != id && it.isNotBlank() }
            else -> null
        }
        return AppleHomeItem(
            id = id,
            kind = kind,
            title = title,
            subtitle = subtitle,
            artworkUrl = artwork,
            motionUrl = motion,
            url = attributes?.string("url"),
            artistId = artistId,
        )
    }

    /** Apple's marker on a station that exists to be one artist's radio (and to draw as their card). */
    private const val ArtistOnlyStation = "artistOnly"
    private const val ArtistStationPrefix = "ra.a-"
}
