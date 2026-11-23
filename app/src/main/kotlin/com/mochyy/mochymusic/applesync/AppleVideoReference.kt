// Concord: new. The YouTube counterpart of an Apple song, for the player's "see video" action.
//
// An Apple row has no video stream of its own — Apple serves audio through HLS/Widevine — so the only
// video available for it is the one the matching pass recorded, or the one a person picked by hand in
// Mochy's own "Wrong video?" picker (a `song_edit` row). This is the single read both the player and
// the picker's label go through; the write side is AppleYoutubeMatch.
package com.mochyy.mochymusic.applesync

import android.content.Context
import com.mochyy.mochymusic.applesync.di.AppleSongWritesEntryPoint
import com.mochyy.mochymusic.songedit.SongEdits
import dagger.hilt.android.EntryPointAccessors

object AppleVideoReference {

    private const val ApplePrefix = "AM_"

    fun isAppleSong(mediaId: String): Boolean = mediaId.startsWith(ApplePrefix)

    /**
     * The YouTube video to show for this Apple song, or null when there is none.
     *
     * A hand-picked video wins over the matched one — that is what Mochy's own picker writes, and
     * its "no video" answer has to be respected too, or turning the video off would do nothing.
     */
    suspend fun youtubeIdFor(context: Context, mediaId: String): String? {
        if (!isAppleSong(mediaId)) return null
        val edit = SongEdits.of(mediaId)
        if (edit?.noVideo == true) return null
        edit?.videoId?.let { return it }
        val dao = EntryPointAccessors
            .fromApplication(context.applicationContext, AppleSongWritesEntryPoint::class.java)
            .dao()
        return runCatching { dao.track(mediaId)?.youtubeId }.getOrNull()
    }
}
