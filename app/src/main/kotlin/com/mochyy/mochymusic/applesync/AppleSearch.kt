// Concord: new — Apple's catalogue search as the web player draws it.
//
// One answer carries every kind of result (`songs`, `albums`, `artists`, `playlists`,
// `music-videos`), and `meta.results.order` is the order Apple puts those shelves in — so the page
// never invents a grouping of its own. The empty field's landing is the same file: `apple-curators`
// grouped as "Browse Categories", which is what this account's search landing actually is.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.long
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One row of Apple's answer. [id] is the catalogue id for every kind but a library row. */
data class AppleSearchItem(
    val kind: Kind,
    val id: String,
    val title: String,
    /** the artist, the curator, or null when Apple sent neither */
    val subtitle: String?,
    /** artwork template, `{w}x{h}` and all; null when the row has none */
    val artworkUrl: String?,
    val durationMs: Long? = null,
) {
    enum class Kind { Song, Album, Artist, Playlist, Video }
}

/** One shelf of the answer. Apple decides which kinds appear and in what order. */
data class AppleSearchSection(
    val kind: AppleSearchItem.Kind,
    val items: List<AppleSearchItem>,
)

/** A tile on the empty field: a "Browse Categories" genre curator. */
data class AppleSearchCategory(
    val id: String,
    val title: String,
    val artworkUrl: String?,
    /** the artwork's own `bgColor`, as the ARGB long the search tab's category tiles tint from */
    val tint: Long,
)

object AppleSearchReader {

    /** Apple's type keys, in the order the page falls back to when `results.order` is missing. */
    private val Kinds = linkedMapOf(
        "songs" to AppleSearchItem.Kind.Song,
        "albums" to AppleSearchItem.Kind.Album,
        "artists" to AppleSearchItem.Kind.Artist,
        "playlists" to AppleSearchItem.Kind.Playlist,
        "music-videos" to AppleSearchItem.Kind.Video,
    )

    /**
     * The shelves, in Apple's own order.
     *
     * `results.order` lists the kinds that came back with rows; anything it does not name is
     * appended in [Kinds] order, so a shelf Apple starts sending tomorrow is still drawn instead of
     * dropped.
     */
    fun sections(page: JsonObject): List<AppleSearchSection> {
        val results = page["results"].obj() ?: return emptyList()
        val order = page["meta"].obj()
            ?.get("results").obj()
            ?.get("order").array()
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val keys = order.filter { it in Kinds } + Kinds.keys.filter { it !in order }
        return keys.mapNotNull { key ->
            val kind = Kinds[key] ?: return@mapNotNull null
            val items = results[key].obj()?.get("data").array().orEmpty()
                .mapNotNull { element -> rowOf(element.obj(), kind) }
            if (items.isEmpty()) null else AppleSearchSection(kind = kind, items = items)
        }
    }

    /**
     * The terms under the typed text.
     *
     * Only `terms` are kept: a `topResults` entry is a resource of some kind, and its shape was
     * never captured, so the page shows the words Apple suggests and not a half-guessed row.
     */
    fun suggestions(page: JsonObject): List<String> =
        page["results"].obj()
            ?.get("suggestions").array().orEmpty()
            .mapNotNull { entry ->
                val suggestion = entry.obj() ?: return@mapNotNull null
                suggestion.string("searchTerm") ?: suggestion.string("displayTerm")
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(8)

    /**
     * "Browse Categories": every `apple-curators` resource in the landing's groups, in Apple's
     * order. A curator without a name is dropped rather than drawn as an empty tile.
     */
    fun categories(page: JsonObject): List<AppleSearchCategory> =
        page["data"].array().orEmpty()
            .flatMap { group ->
                group.obj()
                    ?.get("relationships").obj()
                    ?.get("contents").obj()
                    ?.get("data").array().orEmpty()
            }
            .mapNotNull { ref ->
                val row = ref.obj() ?: return@mapNotNull null
                if (row.string("type") != "apple-curators") return@mapNotNull null
                val attributes = row["attributes"].obj() ?: return@mapNotNull null
                val title = attributes.string("shortName")?.takeIf { it.isNotBlank() }
                    ?: attributes.string("name")
                    ?: return@mapNotNull null
                val artwork = attributes["artwork"].obj()
                AppleSearchCategory(
                    id = row.idString("id") ?: return@mapNotNull null,
                    title = title,
                    artworkUrl = artwork?.string("url"),
                    tint = tintOf(artwork?.string("bgColor")),
                )
            }
            .distinctBy { it.id }

    private fun rowOf(row: JsonObject?, kind: AppleSearchItem.Kind): AppleSearchItem? {
        row ?: return null
        val attributes = row["attributes"].obj() ?: return null
        val id = row.idString("id") ?: return null
        val title = attributes.string("name") ?: return null
        val subtitle = when (kind) {
            AppleSearchItem.Kind.Song, AppleSearchItem.Kind.Album, AppleSearchItem.Kind.Video ->
                attributes.string("artistName")
            AppleSearchItem.Kind.Playlist -> attributes.string("curatorName")
            AppleSearchItem.Kind.Artist -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
        return AppleSearchItem(
            kind = kind,
            id = id,
            title = title,
            subtitle = subtitle,
            // Apple hands out a {w}x{h} template; left as a template the picture stays blank,
            // because `resize()` only knows YouTube's URLs. A video's still is widescreen, and its
            // card asks for 16:9 rather than a square.
            artworkUrl = if (kind == AppleSearchItem.Kind.Video) {
                AppleCovers.sized(attributes["artwork"].obj()?.string("url"), 640, 360)
            } else {
                AppleCovers.sized(attributes["artwork"].obj()?.string("url"))
            },
            durationMs = attributes.long("durationInMillis"),
        )
    }

    /** Apple's six-digit hex, as a long; a missing colour tints nothing. */
    private fun tintOf(hex: String?): Long =
        hex?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }
            ?.toLongOrNull(16) ?: 0L
}
