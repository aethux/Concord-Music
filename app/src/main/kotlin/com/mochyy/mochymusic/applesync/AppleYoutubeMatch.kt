// Concord: new. Fills the Apple <-> YouTube reference for songs that have none.
//
// The matching is Mochy's own: TrackMatcher searches YouTube Music, scores the results the way the
// importer does (title first, then artist, album and length), remembers what it picked, and answers
// "nothing good enough" rather than guessing. Four searches at a time, same as ImportSteps.matchAll.
//
// One call is one bounded pass: [limit] songs at most, and the next pass picks up whatever is left,
// because the sidecar remembers what it already matched. AppleMatchService runs the passes back to
// back until one finds nothing new; a single screen can still run one pass on its own.
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
        /** songs this pass looked at */
        val tried: Int,
        /** of those, how many got a reference */
        val matched: Int,
        /** searches that threw: a dead connection looks like this, not like "nothing found" */
        val failed: Int,
        /** still without a reference afterwards */
        val remaining: Int,
    )

    /**
     * Catalogue ids that already have a twin. Keyed by catalogue id rather than songId: a track row's
     * songId is `AM_<catalogId>` for an imported song but a YouTube id for a rematched one, while the
     * catalogue id is the field both kinds share. Looking a library row up by songId never matched,
     * so every song counted as unmatchable and every pass re-asked the same first 200.
     */
    private suspend fun twinCatalogueIds(): Set<String> =
        dao.tracks().filter { it.youtubeId != null }.mapNotNull { it.catalogId }.toSet()

    /** songs with no twin right now, however many passes it takes to give them one */
    suspend fun remaining(): Int {
        val referenced = twinCatalogueIds()
        return dao.librarySongsOnce().count { it.catalogId !in referenced }
    }

    /**
     * [onProgress] reports (done in this pass, matched in this pass) after each song finishes, so a
     * caller can show the bar moving without waiting for the pass to end.
     */
    suspend fun run(limit: Int = 200, onProgress: (done: Int, matched: Int) -> Unit = { _, _ -> }): Result {
        val referenced = twinCatalogueIds()
        val pending = dao.librarySongsOnce()
            .filter { it.catalogId !in referenced }
            .take(limit)
        if (pending.isEmpty()) return Result(0, 0, 0, 0)

        var done = 0
        var matched = 0
        var failed = 0
        val lock = Any()
        val semaphore = Semaphore(Parallelism)

        coroutineScope {
            pending.map { song ->
                async {
                    semaphore.withPermit {
                        var threw = false
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
                            // stop the pass — but it is counted separately, so "matched nothing" and
                            // "could not ask" stay different answers
                            threw = true
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
                        val progress = synchronized(lock) {
                            done += 1
                            if (found != null) matched += 1
                            if (threw) failed += 1
                            done to matched
                        }
                        // outside the lock: the caller may post a notification here
                        onProgress(progress.first, progress.second)
                    }
                }
            }.awaitAll()
        }
        matcher.flush()

        return Result(tried = pending.size, matched = matched, failed = failed, remaining = remaining())
    }

    private companion object {
        const val Parallelism = 4
    }
}
