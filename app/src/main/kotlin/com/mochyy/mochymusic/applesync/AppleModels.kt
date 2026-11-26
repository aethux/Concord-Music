// Concord: copied from Mocha applesync/AppleModels.kt (apple-sync@c90b8251). Package renamed only; behaviour unchanged.
package com.mochyy.mochymusic.applesync

/**
 * What the sync knows about the account, in the app's own words rather than Apple's JSON.
 *
 * [libraryId] is the per-account id a playlist write needs. [catalogId] is the global one, and is
 * null for anything the user uploaded or iTunes matched, which is exactly the set that cannot be
 * pushed into a playlist.
 */
data class RemoteSong(
    val libraryId: String,
    val catalogId: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val isrc: String?,
    /** Apple's artwork template, with {w}x{h} placeholders; null when the song has no art. */
    val artworkUrl: String? = null,
    /**
     * The album this song belongs to on Apple, as the row's `relationships.albums` said. It is the
     * album's catalogue id when Apple gives one, its own id otherwise - either is stable per album,
     * which is all the album row needs.
     */
    val albumId: String? = null,
    val albumArtworkUrl: String? = null,
    val albumTrackCount: Int? = null,
    val albumYear: Int? = null,
    /** Where the song sits on its album, so an album page lists it in Apple's order. */
    val trackNumber: Int? = null,
    /**
     * The individual artists Apple names for this song. Its library row's own relationship is one
     * library artist (`r.…`) and is sometimes a combined "A & B" entity; the catalogue twin's
     * `relationships.artists` is the split list (numeric catalogue ids) and is preferred when the
     * read carried it. This is the grouping Apple's and Cider's artist lists show.
     */
    val artists: List<RemoteArtist> = emptyList(),
)

/** One artist a song credits: the id its page is opened by, and the name a row shows. */
data class RemoteArtist(
    val id: String,
    val name: String,
)

/** A song on Apple's catalogue. The id here is the one a rating or a library add is keyed by. */
data class CatalogSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val isrc: String?,
    /** Apple's template with {w}x{h} in it; null when the song has no art. */
    val artworkUrl: String? = null,
)

data class RemotePlaylist(
    val id: String,
    val name: String,
    val canEdit: Boolean,
    /** Apple's own playlist artwork, with {w}x{h} in it; null when it has none. */
    val artworkUrl: String? = null,
    /** Who Apple says made it: an editorial curator, or another person on a shared playlist. */
    val curator: String? = null,
    /**
     * The catalogue id (`pl.…`) the list's `relationships.catalog` carries. Null for a playlist
     * Apple has no catalogue twin for, and for an upload.
     */
    val catalogId: String? = null,
    /** True when the playlist is open to edits from more than its owner. */
    val collaborative: Boolean = false,
    /** Apple's lastModifiedDate: a refresh reads the tracks only when this moved. */
    val lastModified: String? = null,
    /** canDelete: false means the playlist belongs to someone else and is in the library by follow. */
    val canDelete: Boolean = false,
    /**
     * The folder this playlist sits in, from the list's `include=parent` relationship. The root is
     * `p.playlistsroot`, which is treated as "not in a folder" by the library page.
     */
    val folderId: String? = null,
)

/** One row of `/v1/me/library/playlist-folders`: Apple's own folder, with a name and an id. */
data class RemotePlaylistFolder(
    val id: String,
    val name: String,
)

/**
 * The signed-in account as Apple's own profile describes it: the only place either its name or its
 * picture is available. A playlist's owner is not in any Apple JSON — `include=social-profiles`
 * returns no relationships at all, and the relationship endpoints 404 — so this is where an owner
 * line gets its data, and the web page is where collaborators come from.
 */
data class AppleProfile(
    val name: String?,
    val handle: String?,
    /** Artwork template, with {w}x{h}; null when the account has no picture. */
    val avatarUrl: String?,
    /** The account's page on the web player, when Apple sends one. */
    val profileUrl: String?,
)

/**
 * The result of one read-only audit: what the account holds, and whether anything that existed when
 * the epoch was taken is missing now.
 */
data class AppleAuditReport(
    val storefront: String,
    val playlists: Int,
    val songs: Int,
    val epochSize: Int,
    val addedSinceEpoch: List<String>,
    val removedSinceEpoch: List<String>,
    val trusted: Boolean,
)

/** A failure worth showing to the user; [signedOut] means the fix is to sign in again. */
class AppleSyncException(message: String, val signedOut: Boolean = false) : Exception(message)

/**
 * One row of `/v1/me/library/artists`, Cider's own list. [id] is Apple's per-account library id
 * (`r.…`) and is what an artist row is keyed by here; [catalogId] is the catalogue twin when the
 * list's `include=catalog` carries one, which Apple does not always have.
 */
data class RemoteLibraryArtist(
    val id: String,
    val name: String,
    val catalogId: String? = null,
    /** The catalogue twin's picture; the library row itself has no artwork at all. */
    val artworkUrl: String? = null,
)

/** The epoch and ownership tables key on "kind:id", because both halves together identify an object. */
internal fun AppleRef.key(): String = "${kind.name}:$id"

internal fun appleRefFromKey(key: String): AppleRef? {
    val kind = AppleKind.entries.firstOrNull { key.startsWith("${it.name}:") } ?: return null
    val id = key.removePrefix("${kind.name}:")
    return if (id.isBlank()) null else AppleRef(kind, id)
}
