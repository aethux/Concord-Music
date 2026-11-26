// Concord: new — Apple's favourite on an artist, an album or a playlist, in Cider's shape.
//
// Cider's calls (concord/cider/apple-api-reference.md §C/D, executed on this account):
//   set   → POST   /v1/me/favorites?ids[<type>]=<id>  → 202
//   clear → DELETE /v1/me/favorites?ids[<type>]=<id>  → 204
// `<type>` is the resource's own type — Cider's `setFavoriteState` sends the item's type unchanged:
// `artists`, `albums`, `playlists`, or `library-playlists` for a playlist the account already holds.
// There is no read endpoint (a GET answers 404), so the state is read from the resource itself:
// `AppleClient.favorite`. Nothing local changes — a card's row follows Apple's answer, as a rating does.
//
// The guard, the account's ten-a-minute window and the audit row are `AppleSongWrites`' — one copy of
// each for every write class, because the limit is the account's, not the op's.
package com.mochyy.mochymusic.applesync

import android.content.Context
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.di.AppleSongWritesEntryPoint
import com.mochyy.mochymusic.ui.component.TopToaster
import dagger.hilt.android.EntryPointAccessors

object AppleFavorites {

    /**
     * Set or clear Apple's favourite on one catalogue object. [type] is Apple's resource type, so a
     * playlist in the account goes in as `library-playlists` and a catalogue one as `playlists`.
     *
     * The guard is asked first, as for every write, but with none of the epoch or ownership sets: a
     * favourite removes and replaces nothing, so the promise those sets protect cannot be broken by
     * it — the same reason a rating is allowed on a song that predates the app.
     */
    suspend fun set(context: Context, type: String, id: String, favorite: Boolean): Boolean {
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AppleSongWritesEntryPoint::class.java,
        )
        val dao = entry.dao()
        val write = if (favorite) AppleWrite.SET_FAVORITE else AppleWrite.CLEAR_FAVORITE

        val decision = AppleGuard.decide(
            AppleOp(write = write),
            AppleGuardContext(
                writesEnabled = AppleWritePrefs.enabled(context),
                epoch = emptySet(),
                owned = emptySet(),
                linkedPlaylists = emptySet(),
            ),
        )
        if (decision is AppleDecision.Refused) {
            dao.audit(AppleSongWrites.audit(write.name, id, "REFUSE", reason = decision.reason.name))
            TopToaster.error(context.getString(R.string.apple_write_off))
            return false
        }
        if (!AppleSongWrites.WriteBudget.take()) {
            dao.audit(AppleSongWrites.audit(write.name, id, "REFUSE", reason = "RATE_LIMITED"))
            TopToaster.error(context.getString(R.string.apple_write_too_soon))
            return false
        }

        val tokens = runCatching { entry.tokens().read() }.getOrNull()
        if (tokens == null) {
            dao.audit(AppleSongWrites.audit(write.name, id, "REFUSE", reason = "SIGNED_OUT"))
            TopToaster.error(context.getString(R.string.apple_write_signed_out))
            return false
        }

        return try {
            val answer = AppleSongWrites.request(
                tokens = tokens,
                verb = if (favorite) "POST" else "DELETE",
                path = "/v1/me/favorites?ids[$type]=$id",
            )
            val ok = answer.status in 200..299
            dao.audit(
                AppleSongWrites.audit(
                    op = write.name,
                    target = id,
                    decision = if (ok) "ALLOW" else "ERROR",
                    reason = if (ok) null else "Apple answered ${answer.status}",
                    httpStatus = answer.status,
                ),
            )
            if (!ok) {
                TopToaster.error(
                    context.getString(
                        if (answer.status == 401) R.string.apple_write_signed_out else R.string.apple_favorite_failed,
                    ),
                )
                return false
            }
            // the artist page keeps its own ten-minute memory of the resource, flag included
            if (type == "artists") AppleClient.forgetArtist(id)
            true
        } catch (e: Exception) {
            dao.audit(AppleSongWrites.audit(write.name, id, "ERROR", reason = e.message))
            TopToaster.error(
                context.getString(
                    if (e is AppleSyncException && e.signedOut) R.string.apple_write_signed_out
                    else R.string.apple_favorite_failed,
                ),
            )
            false
        }
    }
}
