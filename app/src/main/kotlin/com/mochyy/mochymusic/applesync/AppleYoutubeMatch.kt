// Concord: new. Fills the Apple <-> YouTube reference for songs that have none.
//
// The matching is Mochy's own: TrackMatcher searches YouTube Music, scores the results the way the
// importer does (title first, then artist, album and length), remembers what it picked, and answers
// "nothing good enough" rather than guessing. Four searches at a time, same as ImportSteps.matchAll.
//
// It runs as a resumable pass rather than inside the import: 1,434 songs is 6-12 minutes of searching,
// and the read itself must stay fast. [limit] bounds one run; the next run picks up whatever is left,
// because the sidecar remembers what it already matched.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.applesync.db.AppleSyncDao
import com.mochyy.mochymusic.importer.ImportedTrack
import com.mochyy.mochymusic.importer.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class AppleYoutubeMatch(
    private val dao: AppleSyncDao,
    private val matcher: TrackMatcher = TrackMatcher(),
) {

    data class Result(
        /** songs this run looked at */
        val tried: Int,
        /** of those, how many got a reference */
        val matched: Int,
        /** still without one afterwards */
        val remaining: Int,
    )

    /** [onProgress] reports (done, total in this run) after each song finishes. */
    suspend fun run(limit: Int = 200, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val tracks = dao.tracks().associateBy { it.songId }
        val pending = dao.librarySongsOnce()
            .filter { tracks[it.catalogId]?.youtubeId == null }
            .take(limit)
        if (pending.isEmpty()) return Result(0, 0, 0)

        var done = 0
        var matched = 0
        val lock = Any()
        val semaphore = Semaphore(Parallelism)

        coroutineScope {
            pending.map { song ->
                async {
                    semaphore.withPermit {
                        val found = try {
                            matcher.match(
                                ImportedTrack(
                                    title = song.title,
                                    artist = song.artist,
                                    album = song.album,
                                    durationMs = song.durationMs,
                                ),
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Mochy's importer counts this as unmatched too, and one bad search must not
                            // stop the pass
                            null
                        }
                        if (found != null) {
                            // the songId the import gave this catalogue id, so the reference hangs off
                            // the same row the player uses
                            dao.setYoutubeReference(
                                songId = "AM_${song.catalogId}",
                                youtubeId = found.id,
                                title = found.title,
                                at = System.currentTimeMillis(),
                            )
                        }
                        synchronized(lock) {
                            done += 1
                            if (found != null) matched += 1
                        }
                        onProgress(done, pending.size)
                        found != null
                    }
                }
            }.awaitAll()
        }
        matcher.flush()

        // one read, not one query per song: whatever the sidecar now holds a reference for
        val referenced = dao.tracks().filter { it.youtubeId != null }.map { it.catalogId }.toSet()
        val left = dao.librarySongsOnce().count { it.catalogId !in referenced }
        return Result(tried = pending.size, matched = matched, remaining = left)
    }

    private companion object {
        const val Parallelism = 4
    }
}
