// Concord: new. The YouTube counterpart of an Apple song, for the player's "see video" action.
//
// An Apple row has no video stream of its own — Apple serves audio through HLS/Widevine — so the only
// video available for it is the one the matching pass recorded. This is the one read of that
// reference; the write side is AppleYoutubeMatch, and the version switch will rewrite the same column.
package com.mochyy.mochymusic.applesync

import android.content.Context
import com.mochyy.mochymusic.applesync.di.AppleSongWritesEntryPoint
import dagger.hilt.android.EntryPointAccessors

object AppleVideoReference {

    private const val ApplePrefix = "AM_"

    fun isAppleSong(mediaId: String): Boolean = mediaId.startsWith(ApplePrefix)

    /** The YouTube video id recorded for this Apple song, or null when nothing matched it yet. */
    suspend fun youtubeIdFor(context: Context, mediaId: String): String? {
        if (!isAppleSong(mediaId)) return null
        val dao = EntryPointAccessors
            .fromApplication(context.applicationContext, AppleSongWritesEntryPoint::class.java)
            .dao()
        return runCatching { dao.track(mediaId)?.youtubeId }.getOrNull()
    }
}
