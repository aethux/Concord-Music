// Concord: new — Apple's Radio page: the section tree behind `groupings?name=radio`.
//
// The page is not a station list. It is one `groupings` resource whose subscriber tab holds a list
// of editorial elements: a hero row of Apple's live stations (kind 316), then card sections (326,
// 385) whose `contents` are stations, shows, albums and videos. Cider reads the same tree — its
// `ensureRadio` takes `data[0].relationships.tabs.data[0].relationships.children.data`, and its
// `GroupingView` decides which kinds it draws — so the kinds it draws nothing for are skipped here.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One card on the Radio page. [playback] is what a tap on a station does. */
data class AppleRadioItem(
    val id: String,
    val kind: Kind,
    val title: String,
    /** the artist for an album or video, the host for a show, Apple's badge on a live station */
    val subtitle: String?,
    /** artwork template with {w}x{h}, {c} and {f} in it; null when the card has none */
    val artworkUrl: String?,
    val playback: Playback = Playback.Tracks,
    /** Apple's own page for the item, what its Share row sends; null when Apple sent none */
    val url: String? = null,
    /** the artist behind an album, when Apple sent the relationship: what "View artist" opens */
    val artistId: String? = null,
) {
    enum class Kind { Station, Album, Curator, Video, Artist, Playlist }

    enum class Playback {
        /** a queue of songs from `/v1/me/stations/next-tracks` */
        Tracks,

        /** one endless stream from `/v1/play/assets`: progressive when it has no DRM, HLS when it has */
        Live,

        /** a finite stream from `/v1/play/assets`, DRM'ed and licensed like a song */
        Episode,

        /** a stream shape this app cannot play */
        Unsupported,
    }
}

data class AppleRadioSection(
    /** the editorial element's id: the page's only stable name for the section */
    val id: String,
    /** empty for the hero row, which Apple prints no title on */
    val title: String,
    val items: List<AppleRadioItem>,
)

object AppleRadioReader {

    fun sections(page: JsonObject): List<AppleRadioSection> =
        editorialChildren(page).mapNotNull { node -> node.obj()?.let(::section) }

    /**
     * The tab's elements under one `groupings` answer — the walk both editorial pages use, since
     * Apple's Radio and Browse trees are the same `groupings → tabs → children` shape.
     */
    internal fun editorialChildren(page: JsonObject): List<kotlinx.serialization.json.JsonElement> {
        val tab = page["data"].array().firstOrNull()?.obj()
            ?.get("relationships").obj()?.get("tabs").obj()
            ?.get("data").array().firstOrNull()?.obj()
            ?: return emptyList()
        return tab["relationships"].obj()?.get("children").obj()?.get("data").array()
    }

    private fun section(node: JsonObject): AppleRadioSection? {
        val attributes = node["attributes"].obj() ?: return null
        val kind = attributes.string("editorialElementKind")
        if (kind in Skipped) return null
        val id = node.idString("id") ?: return null
        val cards = when (kind) {
            // a hero row of live stations: one card under each child
            HeroKind -> heroCards(node)
            // a brick shelf lists its cards as children too
            ShelfKind -> childCards(node)
            else -> cards(node)
        }
        if (cards.isEmpty()) return null
        val title = attributes.string("name") ?: attributes.string("title") ?: ""
        return AppleRadioSection(id = id, title = title, items = cards)
    }

    /** a hero child is one station plus the badge Apple prints over it ("EXCLUSIVE", "LISTEN NOW") */
    private fun heroItem(child: JsonObject): AppleRadioItem? {
        val badge = child["attributes"].obj()?.string("designBadge")
        val station = child["relationships"].obj()?.get("contents").obj()?.get("data").array()
            .firstOrNull()?.obj() ?: return null
        return item(station, badge)
    }

    /**
     * The cards under one element's `contents`. Shared with Apple's Browse page, whose card
     * sections are the same shape — the parser stays one implementation, on the radio page's model.
     */
    internal fun cards(node: JsonObject): List<AppleRadioItem> =
        node["relationships"].obj()?.get("contents").obj()?.get("data").array()
            .mapNotNull { card -> card.obj()?.let { item(it) } }

    /** The cards under an element's `children`, each a badge over one nested resource. */
    internal fun heroCards(node: JsonObject): List<AppleRadioItem> =
        node["relationships"].obj()?.get("children").obj()?.get("data").array()
            .mapNotNull { child -> child.obj()?.let(::heroItem) }

    /** A brick shelf lists its cards as children, one per card. */
    private fun childCards(node: JsonObject): List<AppleRadioItem> =
        node["relationships"].obj()?.get("children").obj()?.get("data").array()
            .mapNotNull { card -> card.obj()?.let { item(it) } }

    private fun item(card: JsonObject, badge: String? = null): AppleRadioItem? {
        val type = card.string("type") ?: return null
        val id = card.idString("id") ?: return null
        val attributes = card["attributes"].obj()
        val kind = when (type) {
            "stations" -> AppleRadioItem.Kind.Station
            "albums" -> AppleRadioItem.Kind.Album
            "artists" -> AppleRadioItem.Kind.Artist
            "playlists" -> AppleRadioItem.Kind.Playlist
            "apple-curators", "curators" -> AppleRadioItem.Kind.Curator
            "music-videos", "uploaded-videos" -> AppleRadioItem.Kind.Video
            else -> return null
        }
        val title = attributes?.string("name") ?: return null
        val play = attributes?.get("playParams").obj()
        val live = (attributes["isLive"] as? JsonPrimitive)?.booleanOrNull == true
        // a station Apple streams has no next-tracks to walk: a live one plays as an endless window,
        // an episode as a finite stream — DRM or not is the service's business, not the card's
        val playback = if (kind == AppleRadioItem.Kind.Station) {
            when {
                play?.string("format") == TracksFormat -> AppleRadioItem.Playback.Tracks
                play?.string("format") == StreamFormat && live -> AppleRadioItem.Playback.Live
                play?.string("format") == StreamFormat -> AppleRadioItem.Playback.Episode
                else -> AppleRadioItem.Playback.Unsupported
            }
        } else {
            AppleRadioItem.Playback.Tracks
        }
        val subtitle = badge?.takeIf { it.isNotBlank() } ?: when (kind) {
            AppleRadioItem.Kind.Station, AppleRadioItem.Kind.Artist -> null
            AppleRadioItem.Kind.Album, AppleRadioItem.Kind.Video -> attributes?.string("artistName")
            AppleRadioItem.Kind.Playlist ->
                attributes?.string("curatorName") ?: attributes?.string("artistName")
            AppleRadioItem.Kind.Curator -> attributes?.string("showHostName")
        }
        // the read asks for `include[albums]=artists`, so an album knows whose it is without a call
        val artistId = card["relationships"].obj()?.get("artists").obj()?.get("data").array()
            .firstOrNull()?.obj()?.idString("id")
        return AppleRadioItem(
            id = id,
            kind = kind,
            title = title,
            subtitle = subtitle,
            artworkUrl = attributes?.get("artwork").obj()?.string("url"),
            playback = playback,
            url = attributes?.string("url"),
            artistId = artistId,
        )
    }

    private const val HeroKind = "316"
    private const val ShelfKind = "385"
    private const val TracksFormat = "tracks"
    private const val StreamFormat = "stream"

    /**
     * What Cider draws from its own components, a paragraph, or genre link chips: none of them is a
     * row of cards. "On Air Now" (488) is among them — Cider's GroupingView renders it as nothing.
     */
    private val Skipped = setOf("322", "323", "327", "391", "404", "405", "488")
}
