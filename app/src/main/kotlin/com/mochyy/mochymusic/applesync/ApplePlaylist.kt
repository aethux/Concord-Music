// Concord: new — one Apple playlist as its page needs it, read the way Cider's /am/playlist/:id does.
//
// A library playlist is `p.…` and a catalogue one is `pl.…`; each has its own resource, and the
// library copy is the only one that carries canEdit/canDelete. Collaborators are the other way
// round: the library twin has no views, so they come from the catalogue representation
// (`views.collaborators`, social-profiles with name, handle and a 500px avatar).
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.long
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import kotlinx.serialization.json.JsonObject

/** A person on a shared playlist: Apple's `social-profiles` resource. */
data class AppleCollaborator(
    val id: String?,
    val name: String,
    val handle: String?,
    /** 500px template with {w}x{h}{c}.{f}; null when Apple sent no picture. */
    val avatarUrl: String?,
    val isVerified: Boolean,
    val url: String?,
)

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
    /** the playlist's own page on music.apple.com */
    val url: String? = null,
    /** the owner's profile, when Apple exposes one (a shared playlist has one) */
    val owner: AppleCollaborator? = null,
    /** everyone on the playlist, in Apple's order */
    val collaborators: List<AppleCollaborator> = emptyList(),
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
            url = attributes?.string("url"),
            owner = owner(row),
            collaborators = collaborators(row),
        )
    }

    /** `relationships.curator` — the owner, with attributes only when the call asked for them. */
    fun owner(row: JsonObject): AppleCollaborator? {
        val curator = row["relationships"].obj()?.get("curator").obj()
            ?.get("data").array()?.firstOrNull()?.obj() ?: return null
        return collaborator(curator)
    }

    /** `views.collaborators.data[]`, asked for with `views=collaborators`. */
    fun collaborators(row: JsonObject): List<AppleCollaborator> =
        row["views"].obj()?.get("collaborators").obj()?.get("data").array()
            .orEmpty()
            .mapNotNull { it.obj()?.let(::collaborator) }
            .distinctBy { it.handle ?: it.name }

    /** `relationships.collaborators.data[]`, for the library resource's own include. */
    fun relationshipsCollaborators(row: JsonObject): List<AppleCollaborator> =
        row["relationships"].obj()?.get("collaborators").obj()?.get("data").array()
            .orEmpty()
            .mapNotNull { it.obj()?.let(::collaborator) }
            .distinctBy { it.handle ?: it.name }

    private fun collaborator(row: JsonObject): AppleCollaborator? {
        val attributes = row["attributes"].obj() ?: return null
        val name = attributes.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return AppleCollaborator(
            id = row.idString("id"),
            name = name,
            handle = attributes.string("handle")?.takeIf { it.isNotBlank() },
            avatarUrl = attributes.get("artwork").obj()?.string("url"),
            isVerified = attributes.string("isVerified") == "true",
            url = attributes.string("url"),
        )
    }

    /**
     * The catalogue id behind a library playlist. Apple's library resource carries it as
     * `playParams.globalId`, and the catalogue twin is where collaborators are read from.
     */
    fun catalogueTwin(page: JsonObject): String? {
        val row = page["data"].array().firstOrNull()?.obj() ?: return null
        val globalId = row["attributes"].obj()?.get("playParams").obj()?.string("globalId")
        if (!globalId.isNullOrBlank()) return globalId
        return row["relationships"].obj()?.get("catalog").obj()
            ?.get("data").array()?.firstOrNull()?.obj()?.idString("id")
    }
}
