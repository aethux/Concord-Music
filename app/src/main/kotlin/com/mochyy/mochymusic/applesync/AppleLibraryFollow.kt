// Concord: new — following an Apple artist or saving an Apple playlist to the library, both ways.
//
// Cider's calls (concord/cider/apple-api-reference.md §D, executed on the account):
//   add    → POST   /v1/me/library?ids[<type>]=<catalogue id>&representation=ids  → 200
//   remove → DELETE /v1/me/library/<type>/<library id>                           → 204
//
// Same rules as every other write here: the guard decides first, the budget applies, every request
// is audited, and the local row changes only after Apple answered.
package com.mochyy.mochymusic.applesync

import android.content.Context
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.db.AppleAuditEntity
import com.mochyy.mochymusic.importer.account.AccountHttp
import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.idString
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.applesync.di.AppleSongWritesEntryPoint
import com.mochyy.mochymusic.ui.component.TopToaster
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppleLibraryFollow {

    /** [libraryId] is the `r.`/`p.` id the follow just created, so the caller can save it at once. */
    data class Outcome(val ok: Boolean, val libraryId: String? = null)

    /** [type] is Apple's plural path segment: `albums`, `artists` or `playlists`. */
    private const val Host = "https://amp-api.music.apple.com"

    /**
     * Follows or unfollows a catalogue object. [libraryId] is the `r.`/`p.`/`l.` id Apple gave the
     * row in the library; it is required for a removal, because the catalogue id answers 500 there.
     * [kind] is only the guard's name for the target, so an album is not filed as a playlist.
     */
    suspend fun change(
        context: Context,
        type: String,
        catalogueId: String,
        libraryId: String?,
        follow: Boolean,
        kind: AppleKind = AppleKind.PLAYLIST,
    ): Outcome = withContext(Dispatchers.IO) {
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AppleSongWritesEntryPoint::class.java,
        )
        val dao = entry.dao()
        val write = if (follow) AppleWrite.ADD_TO_LIBRARY else AppleWrite.REMOVE_FROM_LIBRARY
        if (!AppleWritePrefs.enabled(context)) {
            TopToaster.error(context.getString(R.string.apple_write_off))
            return@withContext Outcome(false)
        }
        if (!follow && libraryId == null) {
            TopToaster.error(context.getString(R.string.apple_library_not_synced))
            return@withContext Outcome(false)
        }
        val target = if (follow) catalogueId else libraryId!!
        val decision = AppleGuard.decide(
            AppleOp(
                write = write,
                targets = listOf(AppleRef(kind, target)),
                confirmed = !follow,
            ),
            AppleGuardContext(
                writesEnabled = true,
                epoch = dao.epoch().mapNotNull { appleRefFromKey("${it.kind}:${it.remoteId}") }.toSet(),
                owned = emptySet(),
                linkedPlaylists = emptySet(),
            ),
        )
        if (decision is AppleDecision.Refused) {
            audit(dao, write.name, target, "REFUSE", decision.reason.name)
            TopToaster.error(context.getString(R.string.apple_write_off))
            return@withContext Outcome(false)
        }
        if (!AppleSongWrites.WriteBudget.take()) {
            TopToaster.error(context.getString(R.string.apple_write_busy))
            return@withContext Outcome(false)
        }
        val tokens = runCatching { entry.tokens().read() }.getOrNull()
        if (tokens == null) {
            TopToaster.error(context.getString(R.string.apple_write_signed_out))
            return@withContext Outcome(false)
        }
        val answer = try {
            val path = if (follow) {
                "/v1/me/library?ids[$type]=$catalogueId&representation=ids"
            } else {
                "/v1/me/library/$type/$libraryId"
            }
            AccountHttp.send(if (follow) "POST" else "DELETE", Host + path, tokens.headers, null, "Apple Music")
        } catch (e: Exception) {
            audit(dao, write.name, target, "ERROR", e.message)
            TopToaster.error(context.getString(R.string.apple_library_failed))
            return@withContext Outcome(false)
        }
        val ok = answer.status in 200..299
        audit(
            dao,
            write.name,
            target,
            if (ok) "ALLOW" else "ERROR",
            if (ok) null else "Apple answered ${answer.status}",
            answer.status,
        )
        if (!ok) {
            TopToaster.error(context.getString(R.string.apple_library_failed))
            return@withContext Outcome(false)
        }
        TopToaster.show(
            text = context.getString(if (follow) R.string.added_to_library else R.string.removed_from_library),
            iconRes = if (follow) R.drawable.library_add_check else R.drawable.library_add,
        )
        // a follow answers the new library id; that is what an immediate unfollow needs
        val created = if (follow) {
            AccountHttp.parse(answer.body)?.get("data")?.array()?.firstOrNull()?.obj()?.idString("id")
        } else {
            libraryId
        }
        Outcome(true, created)
    }

    private suspend fun audit(
        dao: com.mochyy.mochymusic.applesync.db.AppleSyncDao,
        op: String,
        target: String?,
        decision: String,
        reason: String?,
        status: Int? = null,
    ) {
        runCatching {
            dao.audit(
                AppleAuditEntity(
                    at = System.currentTimeMillis(),
                    op = op,
                    target = target,
                    decision = decision,
                    reason = reason,
                    httpStatus = status,
                ),
            )
        }
    }
}
