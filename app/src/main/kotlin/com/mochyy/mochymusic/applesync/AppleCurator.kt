// Concord: new — a "Browse Categories" curator's own list: the playlists Apple publishes for a genre.
//
// One read: `GET /v1/catalog/{sf}/apple-curators/{id}/playlists`. The curator itself is an
// `apple-curators` resource whose name the search landing already sent with the tile, so the page is
// titled from the tap and no second request is made for it.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import kotlinx.serialization.json.JsonObject

/** One playlist on a curator's page. [id] is Apple's own playlist id (`pl.…`). */
data class AppleCuratorPlaylist(
    val id: String,
    val title: String,
    val curator: String?,
    /** the cover, `{w}x{h}` already replaced */
    val artworkUrl: String?,
)

object AppleCuratorReader {

    /**
     * The playlists, in Apple's order.
     *
     * `playParams.id` is preferred over the row id: it is the id Apple's own pages navigate with,
     * while a row's `id` in a relationship list can arrive without its `pl.` prefix.
     */
    fun playlists(page: JsonObject): List<AppleCuratorPlaylist> =
        page["data"].array().orEmpty()
            .mapNotNull { row ->
                val resource = row.obj() ?: return@mapNotNull null
                val attributes = resource["attributes"].obj() ?: return@mapNotNull null
                val title = attributes.string("name") ?: return@mapNotNull null
                val id = attributes["playParams"].obj()?.idString("id")
                    ?: resource.idString("id")
                    ?: return@mapNotNull null
                AppleCuratorPlaylist(
                    id = id,
                    title = title,
                    curator = attributes.string("curatorName")?.trim()?.takeIf { it.isNotEmpty() },
                    artworkUrl = AppleCovers.sized(attributes["artwork"].obj()?.string("url")),
                )
            }
}
