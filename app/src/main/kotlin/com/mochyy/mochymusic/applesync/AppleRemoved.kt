// Concord: rewritten from Mocha applesync/AppleRemoved.kt (apple-sync@c90b8251). Mocha kept a list of
// the songs Apple had dropped and a screen that asked about each one (keep it here / remove it here /
// put it back). Concord asks nothing: Apple is the library of record, so a song Apple no longer lists
// leaves this library on the same refresh.
//
// Nothing is deleted. The row, its history, its lyrics, its playlist entries and any downloaded file
// all stay, which is what makes a saved song still play with the network off and a played one still
// count. "Out of the library" is a state the import already understands: it is what
// `appleSongsOutsideTheLibrary` reads, and `AppleImport` gives the row its place back the moment
// Apple lists it again. So this is a flag flip that undoes itself, not a delete.

package com.mochyy.mochymusic.applesync

import com.mochyy.mochymusic.applesync.db.AppleStateEntity
import com.mochyy.mochymusic.applesync.db.AppleSyncDao
import com.mochyy.mochymusic.db.MusicDatabase

object AppleRemoved {

    /** The key Mocha's list lived under. Kept only so the ids it was left holding can be cleared. */
    private const val KEY = "removed_from_apple"

    /**
     * Takes out of this library every Apple song Apple no longer lists, and returns how many left.
     *
     * Only called after a complete pull: Apple's library is read whole or the run fails, so a song's
     * absence means it is gone rather than not read yet. Both id dialects go in — an upload has no
     * catalogue id, and its row here is keyed by the library one (`AM_i.…`).
     */
    suspend fun prune(dao: AppleSyncDao, database: MusicDatabase, remoteIds: Collection<String>): Int {
        val remote = remoteIds.toSet()
        val inLibrary = runCatching { database.appleSongDao.appleSongsInTheLibrary() }.getOrDefault(emptyList())
        val gone = inLibrary.filter { it.id.removePrefix("AM_") !in remote }
        if (gone.isNotEmpty()) {
            database.query {
                // the like goes with the library place, as it did when Mocha asked and the answer was
                // "remove it here"; nothing else on the row changes
                gone.forEach { row -> update(row.copy(inLibrary = null, liked = false, likedDate = null)) }
            }
        }
        // nothing reads the old list any more; one write is enough to clear the ids it held
        if (runCatching { dao.state(KEY) }.getOrNull().orEmpty().isNotEmpty()) {
            dao.saveState(AppleStateEntity(key = KEY, value = ""))
        }
        return gone.size
    }
}
