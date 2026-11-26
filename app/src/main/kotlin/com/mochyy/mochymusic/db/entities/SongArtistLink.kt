// Concord: new — one song→artist link, read in a single pass.
package com.mochyy.mochymusic.db.entities

/** Room projection for `song_artist_map`, so a refresh compares links instead of querying each song. */
data class SongArtistLink(
    val songId: String,
    val artistId: String,
)
