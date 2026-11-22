// Concord: new. The songs-only prefix of Mocha's AppleSyncFlow (part 11): read the apple library into
// the sidecar replica, then import it. Mocha's AppleImport already writes the album rows and queues
// the animated covers itself, so that is the whole read.
//
// The playlist half and the shared flow (AppleSyncFlow) arrive with parts 5 and 11. This exists so
// the first read can be triggered from Settings today, and is replaced by AppleSyncFlow when the
// playlists land — it is not a second flow to keep.
package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.applesync.db.AppleSyncDao
import com.mochyy.mochymusic.db.MusicDatabase

class AppleLibraryRefresh(
    private val database: MusicDatabase,
    private val dao: AppleSyncDao,
    private val tokens: AppleTokens,
    private val animatedCovers: Boolean = true,
) {

    data class Result(val read: Int, val added: Int, val alreadyThere: Int)

    suspend fun run(onStage: (String) -> Unit = {}): Result {
        // Concord: Timber never reaches logcat on this fork, so the stage goes to the caller instead —
        // it is drawn in the settings row, which is the only place a silent stall can be seen.
        onStage("reading")
        val pull = AppleLibraryPull(dao, tokens).run()
        onStage("importing ${pull.remote.size} songs")
        val imported = AppleImport(database, dao, tokens, animatedCovers).run(pull.remote)
        return Result(read = pull.songs, added = imported.added, alreadyThere = imported.skipped)
    }
}
