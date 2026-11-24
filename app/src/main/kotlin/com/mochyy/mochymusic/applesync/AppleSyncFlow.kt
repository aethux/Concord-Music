// Concord: copied from Mocha applesync/AppleSyncFlow.kt (apple-sync@c90b8251). Package renamed, plus
// three changes forced by this app (concord/engine-port.md step 9):
//   1. the whole run is pinned to Dispatchers.IO — AppleImport writes through Mochy's blocking DAO
//      methods and Room refuses those on the main thread, whatever called the flow;
//   2. the animated-covers switch is read here, because the service has no ViewModel to read it for it;
//   3. the last-sync sentence is recorded through the dao the flow already holds, not a second lookup.
//
// The whole refresh in one place: read Apple's library, import it, import the playlists, say what
// happened, and remember when.
//
// It lives here, not in the refresh button, because three callers need the same flow: the button,
// the foreground service that keeps it alive once the app is in the background, and anything that
// wants to report on it afterwards. The button used to assemble the phases itself, which meant the
// service would have had to copy them.

package com.mochyy.mochymusic.applesync

import android.content.Context
import com.mochyy.mochymusic.applesync.db.AppleStateEntity
import com.mochyy.mochymusic.applesync.di.ApplePlaylistEntryPoint
import com.mochyy.mochymusic.constants.AppleAnimatedCoversKey
import com.mochyy.mochymusic.utils.dataStore
import com.mochyy.mochymusic.utils.get
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppleSyncFlow {

    /** What one refresh did, in the numbers the finish line and the notification both quote. */
    data class Summary(
        val songs: Int,
        val added: Int,
        val alreadyThere: Int,
        val albums: Int,
        val playlists: Int,
        val playlistSongs: Int,
        val changedPlaylists: Int,
        val unchangedPlaylists: Int,
        val tookMs: Long,
        val message: String,
    )

    /** Keys in the sidecar's `sync_state`: the footer reads them without asking Apple anything. */
    const val KEY_LAST_SYNC_AT = "last_sync_at"
    const val KEY_LAST_SYNC_SUMMARY = "last_sync_summary"

    /** How many songs are waiting for "match it" or "keep it on YouTube". */
    const val KEY_UNMATCHED = "unmatched_count"

    /**
     * Runs a full refresh. The caller decides where it runs: the button starts it in the service,
     * the service keeps the process alive and reports it in the shade.
     */
    suspend fun run(context: Context, onPlaylistProgress: (Int, Int) -> Unit = { _, _ -> }): Summary =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val entry = EntryPointAccessors.fromApplication(
                context.applicationContext,
                ApplePlaylistEntryPoint::class.java,
            )
            val dao = entry.dao()
            val animatedCovers = runCatching { context.dataStore.get(AppleAnimatedCoversKey, true) }
                .getOrDefault(true)

            // the songs, and the album rows and motion covers AppleImport queues itself
            val pulled = AppleLibraryPull(dao, entry.tokens()).run()
            val songs = AppleImport(entry.database(), dao, entry.tokens(), animatedCovers).run(pulled.remote)

            // the playlists come with it, so one refresh leaves nothing behind - and it says so,
            // because a silent third phase looked like a stuck refresh
            AppleSyncStatus.start("Importing playlists…")
            val playlists = ApplePlaylistImport.run(context.applicationContext, onPlaylistProgress)

            // what this library still holds on Apple, but Apple no longer has
            runCatching {
                AppleRemoved.record(
                    dao,
                    entry.database(),
                    pulled.remote.mapNotNull { it.catalogId },
                )
            }

            // Apple wins a duplicate, so the refresh itself folds any it can find instead of leaving
            // that until someone opens the review screen. Capped, and never fatal to the refresh.
            AppleSyncStatus.start("Matching what Apple has…")
            runCatching { AppleReconcile.run(context.applicationContext, limit = 10) }

            val tookMs = System.currentTimeMillis() - startedAt
            val message = describe(songs, playlists, tookMs)
            record(dao, message)
            // the card and the notification finish with the same sentence
            AppleSyncStatus.update(phase = "Done")
            AppleSyncStatus.finish(message)

            Summary(
                songs = pulled.remote.size,
                added = songs.added,
                alreadyThere = songs.skipped,
                albums = songs.albums,
                playlists = playlists.playlists,
                playlistSongs = playlists.songs,
                changedPlaylists = playlists.playlists - playlists.unchanged,
                unchangedPlaylists = playlists.unchanged,
                tookMs = tookMs,
                message = message,
            )
        }

    /**
     * The line a person reads after a refresh. It says what happened rather than what ran: "nothing
     * new" is a better sentence than "1494 songs imported", and the counts that did not change say
     * so in words, which is what makes it possible to tell a no-op refresh from a stuck one.
     */
    private fun describe(songs: AppleImport.Result, playlists: ApplePlaylistImport.Result, tookMs: Long): String {
        val parts = mutableListOf<String>()
        if (songs.added > 0) {
            parts += "${songs.added} new ${if (songs.added == 1) "song" else "songs"}"
            parts += "${songs.skipped} already there"
        } else {
            parts += "Nothing new"
            parts += "${songs.skipped} ${if (songs.skipped == 1) "song" else "songs"} already there"
        }
        if (songs.albums > 0) parts += "${songs.albums} albums"

        val changed = playlists.playlists - playlists.unchanged
        // read and rebuilt the rows is not the same as "the membership changed": a playlist whose
        // stamp is unknown is read every run, and saying "updated" then would be a lie
        val listPart = when {
            playlists.playlists == 0 -> null
            playlists.written == 0 -> "${playlists.playlists} playlists unchanged"
            playlists.written == changed -> "$changed of ${playlists.playlists} playlists updated"
            else -> "$changed of ${playlists.playlists} playlists checked"
        }
        listPart?.let { parts += it }
        if (playlists.songs > 0) parts += "${playlists.songs} playlist songs"

        return parts.joinToString(" · ") + " · took ${took(tookMs)}"
    }

    private fun took(ms: Long): String = when {
        ms < 60_000 -> "${ms / 1000}s"
        else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    }

    private suspend fun record(dao: com.mochyy.mochymusic.applesync.db.AppleSyncDao, message: String) {
        runCatching {
            dao.saveState(AppleStateEntity(KEY_LAST_SYNC_AT, System.currentTimeMillis().toString()))
            dao.saveState(AppleStateEntity(KEY_LAST_SYNC_SUMMARY, message))
        }
        // the home feed is built from the library this refresh has just rewritten, so a stored copy
        // of it is now the one thing that would be wrong
        runCatching { ApplePageCache.invalidateLibrary(dao) }
    }
}
