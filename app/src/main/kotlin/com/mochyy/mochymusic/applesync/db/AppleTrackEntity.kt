// Concord: copied from Mocha applesync/db/AppleTrackEntity.kt (apple-sync@c90b8251). Package renamed only.
package com.mochyy.mochymusic.applesync.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * How one Mocha song maps to Apple: the catalog id for identity, the library id for playlist writes.
 *
 * The library id belongs to the account and changes when a song is removed and added again, so it is
 * a cache and not an identity. When a write is refused for an unknown id, re-read the playlist and
 * refresh it rather than trusting this column.
 */
@Entity(tableName = "track")
data class AppleTrackEntity(
    @PrimaryKey val songId: String,
    val catalogId: String? = null,
    val libraryId: String? = null,
    val isrc: String? = null,
    val state: String = STATE_AUTO,
    val updatedAt: Long = 0L,
    // Concord: the YouTube counterpart for this Apple song — the reference that makes one row
    // addressable on both services. Written by the matching pass, and rewritten when the person picks
    // a different version; null means nothing was matched yet (or none exists).
    val youtubeId: String? = null,
    val youtubeTitle: String? = null,
) {
    companion object {
        const val STATE_AUTO = "AUTO"
        /** a person picked the recording; nothing automatic may overwrite it */
        const val STATE_MANUAL = "MANUAL"
        const val STATE_PICKED = "PICKED"
        const val STATE_NONE = "NONE"
        const val STATE_YOUTUBE_ONLY = "YOUTUBE_ONLY"
    }
}
