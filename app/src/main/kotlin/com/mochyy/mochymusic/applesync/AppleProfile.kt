// Concord: new — an Apple profile, the way Cider reads one.
//
// `apple-api-reference.md` §A names the resource: `/v1/social/{sf}/social-profiles/{id}?art[url]=f`,
// and `relationships["shared-playlists"]` is the only place another person's public playlists are
// listed. Apple's attributes are name, handle, artwork, isPrivate, isVerified and a url that carries
// the handle when the profile has one.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.long
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.importer.string
import kotlinx.serialization.json.JsonObject

/** One of a person's public playlists, as their profile lists it. */
data class ApplePlaylistRef(
    /** `pl.…` — the catalogue id, which the playlist page already accepts. */
    val id: String,
    val name: String,
    /** artwork template with {w}x{h}; null when Apple sent none. */
    val artworkUrl: String?,
    val trackCount: Int?,
    val curator: String?,
)

/**
 * A person on Apple Music, as far as the social API will say. [playlists] is empty for a profile
 * Apple keeps private — that resource answers 404 for the page while its name still hydrates on a
 * playlist, so "no page" is a real state and not a failure.
 */
data class ApplePersonProfile(
    val id: String,
    val name: String,
    val handle: String?,
    /** 500 px template with {w}x{h}{c}.{f}; null when Apple sent no picture. */
    val artworkUrl: String?,
    val isVerified: Boolean,
    val isPrivate: Boolean,
    /** `music.apple.com/profile/<handle>` when the profile has one. */
    val url: String?,
    val playlists: List<ApplePlaylistRef> = emptyList(),
)

object AppleProfileReader {

    fun profile(page: JsonObject): ApplePersonProfile? {
        val row = page["data"].array().firstOrNull()?.obj() ?: return null
        val id = row.idString("id") ?: return null
        val attributes = row["attributes"].obj()
        val shared = row["relationships"].obj()?.get("shared-playlists").obj()
            ?.get("data").array().orEmpty()
        return ApplePersonProfile(
            id = id,
            name = attributes?.string("name") ?: return null,
            handle = attributes?.string("handle")?.trim()?.takeIf { it.isNotEmpty() },
            artworkUrl = attributes?.get("artwork").obj()?.string("url"),
            isVerified = attributes?.string("isVerified") == "true",
            isPrivate = attributes?.string("isPrivate") == "true",
            url = attributes?.string("url"),
            playlists = shared.mapNotNull { item ->
                val playlist = item.obj() ?: return@mapNotNull null
                val playlistId = playlist.idString("id") ?: return@mapNotNull null
                val release = playlist["attributes"].obj()
                ApplePlaylistRef(
                    id = playlistId,
                    name = release?.string("name") ?: return@mapNotNull null,
                    artworkUrl = release.get("artwork").obj()?.string("url"),
                    trackCount = release.long("trackCount")?.toInt(),
                    curator = release.string("curatorName"),
                )
            },
        )
    }
}
