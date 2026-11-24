// Concord: new. The automatic side of an Apple song's video: what Refetch searches with, and what it
// does to the record.
//
// An Apple row's audio is fixed — Apple's catalogue recording plays through the Apple module, or
// through whichever source is picked in the app. What can be wrong is the *video*, and that is
// Mochy's own per-song choice (`SongEdit.videoId`, set by the "Wrong video?" picker). The matched
// twin is the default that picker overrides; this file keeps the default fresh.
package com.mochyy.mochymusic.applesync

import android.content.Context
import com.mochyy.mochymusic.applesync.di.AppleSongWritesEntryPoint
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.importer.ImportSource
import com.mochyy.mochymusic.importer.TrackMatch
import com.mochyy.mochymusic.importer.TrackMatcher
import com.mochyy.mochymusic.songedit.SongEditSaver
import com.music.innertube.models.SongItem
import dagger.hilt.android.EntryPointAccessors

object AppleVersionSwitch {

    private const val ApplePrefix = "AM_"

    fun isAppleSong(songId: String): Boolean = songId.startsWith(ApplePrefix)

    /**
     * The track Refetch searches with. Mochy reads this from `imported_song`, which an Apple row is
     * not in, so the song describes itself: title, artists and length, the same three the matcher used
     * when the library was read.
     */
    fun trackFor(song: Song): TrackMatch? {
        if (!isAppleSong(song.id)) return null
        val title = song.song.title
        if (title.isBlank()) return null
        return TrackMatch(
            key = "apple|${song.id}",
            title = title,
            artist = song.artists.joinToString(", ") { it.name },
            album = song.song.albumName ?: song.album?.title,
            durationMs = song.song.duration.takeIf { it > 0 }?.times(1000L),
            source = ImportSource.APPLE_MUSIC.key,
            songId = song.id,
        )
    }

    /**
     * Refetch on an Apple song: lets go of a hand-picked video, searches again, and records what it
     * finds. Null means nothing came back — the caller says so and the video stays as it was.
     */
    suspend fun refetch(context: Context, match: TrackMatch): SongItem? {
        val found = TrackMatcher().match(match.track) ?: return null
        val entry = EntryPointAccessors
            .fromApplication(context.applicationContext, AppleSongWritesEntryPoint::class.java)
        // a hand pick wins over the match, so searching again has to let go of it first
        SongEditSaver.setVideo(
            database = entry.database(),
            songId = match.songId,
            videoId = null,
            noVideo = false,
        )
        val dao = entry.dao()
        dao.setYoutubeReference(
            songId = match.songId,
            youtubeId = found.id,
            title = found.title,
            at = System.currentTimeMillis(),
        )
        return found.takeIf { dao.track(match.songId)?.youtubeId == found.id }
    }
}
