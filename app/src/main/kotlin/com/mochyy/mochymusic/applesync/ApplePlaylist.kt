// Concord: new — one Apple playlist as its page needs it, read the way Cider's /am/playlist/:id does.
//
// A library playlist is `p.…` and a catalogue one is `pl.…`; each has its own resource, and the
// library copy is the only one that carries canEdit/canDelete. The catalogue twin (via
// payParams.globalId) is where collaborators and views live — not read here yet.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.long
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import kotlinx.serialization.json.JsonObject

data class ApplePlaylist(
    val id: String,
    val name: String,
    /** Who Apple says made it: a curator, or the person who shared it. */
    val curator: String?,
    /** `description.standard`, falling back to `short`; null when Apple wrote none. */
    val description: String?,
    /** Artwork template with {w}x{h}, {c} and {f} in it; null when the playlist has none. */
    val artworkUrl: String?,
    val trackCount: Int?,
    /** true for a `p.…` playlist — the only kind this app may ever edit. */
    val inLibrary: Boolean,
    /** Apple's own flags; false for a catalogue-only playlist. */
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
    val songs: List<RemoteSong> = emptyList(),
)

object ApplePlaylistReader {

    fun playlist(page: JsonObject, inLibrary: Boolean): ApplePlaylist? {
        val row = page["data"].array().firstOrNull()?.obj() ?: return null
        val id = row.idString("id") ?: return null
        val attributes = row["attributes"].obj()
        return ApplePlaylist(
            id = id,
            name = attributes?.string("name") ?: return null,
            curator = attributes?.string("curatorName")?.trim()?.takeIf { it.isNotEmpty() },
            description = attributes?.get("description").obj()?.let { notes ->
                notes.string("standard") ?: notes.string("short")
            }?.trim()?.takeIf { it.isNotEmpty() },
            artworkUrl = attributes?.get("artwork").obj()?.string("url"),
            trackCount = attributes?.long("trackCount")?.toInt(),
            inLibrary = inLibrary,
            canEdit = attributes?.string("canEdit") == "true",
            canDelete = attributes?.string("canDelete") == "true",
        )
    }
}
