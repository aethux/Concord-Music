// Concord: copied from Mocha applesync/PlaylistCatalogue.kt (apple-sync@c90b8251). Package renamed only; behaviour unchanged.
package com.mochyy.mochymusic.applesync

/**
 * What a playlist's catalogue counterpart says about it: the name of the person or curator Apple
 * credits, and the catalogue id the full track list can be asked for.
 */
data class PlaylistCatalogue(
    val ownerName: String?,
    val catalogId: String?,
)
