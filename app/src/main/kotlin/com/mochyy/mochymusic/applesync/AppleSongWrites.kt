// Concord: new — a song's Apple write, in Cider's shape rather than Mocha's.
//
// Cider's own call sites (concord/cider/apple-calls.txt):
//   love  → PUT    /v1/me/ratings/songs/{id}   {"attributes":{"value":1}}
//           and, when `appleMusic.addToLibraryOnFavorite` is on, addToLibrary(id, "songs")
//   clear → DELETE /v1/me/ratings/songs/{id}
// Add to library (concord/cider/apple-api-reference.md §D, executed on this account):
//   POST /v1/me/library?ids[songs]={id}&representation=ids   → 200, the row gets a new `i.` id
// The rating body carries no `type`: Cider's call sends `attributes` only. Writes go over REST with
// the token pair the reads use; the guard decides before a request is built; ten writes a minute
// (Cider's ceiling); the read-back confirms but never gates. Nothing here calls YouTube, on any id.
package com.mochyy.mochymusic.applesync

import android.content.Context
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.db.AppleAuditEntity
import com.mochyy.mochymusic.applesync.db.AppleSyncDao
import com.mochyy.mochymusic.applesync.di.AppleSongWritesEntryPoint
import com.mochyy.mochymusic.constants.AppleAddToLibraryOnFavoriteKey
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.importer.account.AccountHttp
import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.long
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.ui.component.TopToaster
import com.mochyy.mochymusic.utils.dataStore
import com.mochyy.mochymusic.utils.get
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDateTime
import kotlinx.coroutines.delay

object AppleSongWrites {

    private const val Host = "https://amp-api.music.apple.com"

    /** Cider's body verbatim: `attributes` only, value 1 is the love. */
    private const val LoveBody = """{"attributes":{"value":1}}"""
    private const val DislikeBody = """{"attributes":{"value":-1}}"""

    /**
     * The heart on one song. [liked] true sets Apple's rating, false clears it.
     *
     * A love also adds the song to the Apple library when that switch is on — Cider does the same
     * (`addToLibraryOnFavorite`) and it is what puts a loved song in the Apple library and in Apple's
     * own Favourite Songs list. Clearing a rating never removes anything.
     *
     * A row Apple has never seen — an upload — flips here and stops: there is no rating to send, and
     * the YouTube call Mochy's `toggleLike()` would make from an `AM_` id names a video that does
     * not exist.
     */
    suspend fun pushLike(context: Context, song: SongEntity, liked: Boolean): Boolean {
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AppleSongWritesEntryPoint::class.java,
        )
        val dao = entry.dao()
        // The sidecar's `track` table only holds rows the import mapped; a song opened from a
        // playlist or a shelf can be missing from it. An `AM_` id is built from the catalogue id
        // (`AppleImport.kt:212`), so the id itself is the fallback — a derivation, not a guess.
        val catalogId = catalogueId(dao, song.id)
        if (catalogId == null) return setLocal(entry, song.id, liked, inLibrary = null)

        val write = if (liked) AppleWrite.SET_RATING else AppleWrite.CLEAR_RATING
        if (refuse(context, dao, write, catalogId)) return false

        if (!WriteBudget.take()) {
            dao.audit(audit(write.name, catalogId, "REFUSE", reason = "RATE_LIMITED"))
            TopToaster.error(context.getString(R.string.apple_write_too_soon))
            return false
        }

        val tokens = runCatching { entry.tokens().read() }.getOrNull()
        if (tokens == null) {
            dao.audit(audit(write.name, catalogId, "REFUSE", reason = "SIGNED_OUT"))
            TopToaster.error(context.getString(R.string.apple_write_signed_out))
            return false
        }

        return try {
            val answer = request(
                tokens = tokens,
                verb = if (liked) "PUT" else "DELETE",
                path = "/v1/me/ratings/songs/$catalogId",
                body = if (liked) LoveBody else null,
            )
            if (answer.status !in 200..299) {
                throw AppleSyncException("Apple answered ${answer.status}", signedOut = answer.status == 401)
            }
            // The read-back confirms the write; it does not gate it. A write Apple accepted has to
            // show in the app even when the confirming read is the thing that fails — only a read
            // that came back and disagreed is evidence against.
            val readBack = runCatching { rated(tokens, catalogId) }
            val contradicted = readBack.isSuccess && readBack.getOrNull() != (if (liked) 1L else null)
            dao.audit(
                audit(
                    op = write.name,
                    target = catalogId,
                    decision = "ALLOW",
                    httpStatus = answer.status,
                    verified = readBack.isSuccess && !contradicted,
                ),
            )
            if (contradicted) {
                TopToaster.error(context.getString(R.string.apple_write_unconfirmed, song.title))
                return false
            }
            // Cider's setRating: value 1 also adds to the library, when the switch allows it
            val added = if (liked) addToLibrary(context, tokens, dao, catalogId) else false
            setLocal(entry, song.id, liked, inLibrary = if (added) true else null)
            // the player's own caption does the talking (LikedLabel); a pill here said it twice
            true
        } catch (e: Exception) {
            dao.audit(audit(write.name, catalogId, "ERROR", reason = e.message))
            val signedOut = e is AppleSyncException && e.signedOut
            TopToaster.error(
                context.getString(
                    if (signedOut) R.string.apple_write_signed_out else R.string.apple_like_failed,
                ),
            )
            false
        }
    }

    // ---- add to / remove from the library --------------------------------------------------------

    /**
     * Apple's "Suggest Less" on one song, and its undo: the same `/v1/me/ratings/songs/{id}` the heart
     * uses, with value −1 instead of 1 (Cider's `setSuggestLess`).
     *
     * No local row changes: there is no `disliked` column and none is added — the song menu reads the
     * rating back when it opens, so the row it draws and the account cannot disagree. A row Apple has
     * never seen (an upload) has no rating to send, and says so rather than pretending.
     */
    suspend fun pushDislike(context: Context, song: SongEntity, disliked: Boolean): Boolean {
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AppleSongWritesEntryPoint::class.java,
        )
        val dao = entry.dao()
        val catalogId = catalogueId(dao, song.id) ?: run {
            TopToaster.error(context.getString(R.string.apple_dislike_no_id))
            return false
        }

        val write = if (disliked) AppleWrite.SET_RATING else AppleWrite.CLEAR_RATING
        if (refuse(context, dao, write, catalogId)) return false

        if (!WriteBudget.take()) {
            dao.audit(audit(write.name, catalogId, "REFUSE", reason = "RATE_LIMITED"))
            TopToaster.error(context.getString(R.string.apple_write_too_soon))
            return false
        }

        val tokens = runCatching { entry.tokens().read() }.getOrNull()
        if (tokens == null) {
            dao.audit(audit(write.name, catalogId, "REFUSE", reason = "SIGNED_OUT"))
            TopToaster.error(context.getString(R.string.apple_write_signed_out))
            return false
        }

        return try {
            val answer = request(
                tokens = tokens,
                verb = if (disliked) "PUT" else "DELETE",
                path = "/v1/me/ratings/songs/$catalogId",
                body = if (disliked) DislikeBody else null,
            )
            if (answer.status !in 200..299) {
                throw AppleSyncException("Apple answered ${answer.status}", signedOut = answer.status == 401)
            }
            dao.audit(audit(write.name, catalogId, "ALLOW", httpStatus = answer.status))
            true
        } catch (e: Exception) {
            dao.audit(audit(write.name, catalogId, "ERROR", reason = e.message))
            val signedOut = e is AppleSyncException && e.signedOut
            TopToaster.error(
                context.getString(
                    if (signedOut) R.string.apple_write_signed_out else R.string.apple_dislike_failed,
                ),
            )
            false
        }
    }

    /**
     * Put a song in the Apple library, or take it out: the player's library button, the song menu's
     * row and the swipe.
     *
     * Cider's calls (`apple-api-reference.md` §D, both executed on this account):
     *   add    → `POST   /v1/me/library?ids[songs]={catalogueId}&representation=ids`  → 200
     *   remove → `DELETE /v1/me/library/songs/{libraryId}`                            → 204
     *
     * The two directions take **different ids**: an add is given the catalogue id, while a remove
     * only works with the `i.` library id Apple gave the row — with the catalogue id it answers 500
     * "Unable to delete from library", and `DELETE /v1/me/library?ids[songs]=…` is a 405.
     *
     * A removal is a person's own tap on "Remove from library", so it goes in as confirmed: the
     * guard refuses a deletion it has no yes for, and this is that yes.
     */
    suspend fun setLibrary(context: Context, song: SongEntity, add: Boolean): Boolean {
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AppleSongWritesEntryPoint::class.java,
        )
        val dao = entry.dao()
        val catalogId = catalogueId(dao, song.id)
        if (catalogId == null) {
            TopToaster.error(context.getString(R.string.apple_library_not_synced))
            return false
        }

        val write = if (add) AppleWrite.ADD_TO_LIBRARY else AppleWrite.REMOVE_FROM_LIBRARY
        val libraryId = dao.track(song.id)?.libraryId
        if (!add && libraryId == null) {
            // Apple's library id arrives with the library read; without it the delete cannot be named
            TopToaster.error(context.getString(R.string.apple_library_not_synced))
            return false
        }
        if (refuse(context, dao, write, if (add) catalogId else libraryId!!, confirmed = !add)) return false
        if (!WriteBudget.take()) {
            dao.audit(audit(write.name, catalogId, "REFUSE", reason = "RATE_LIMITED"))
            TopToaster.error(context.getString(R.string.apple_write_too_soon))
            return false
        }
        val tokens = runCatching { entry.tokens().read() }.getOrNull()
        if (tokens == null) {
            dao.audit(audit(write.name, catalogId, "REFUSE", reason = "SIGNED_OUT"))
            TopToaster.error(context.getString(R.string.apple_write_signed_out))
            return false
        }

        return try {
            val answer =
                if (add) {
                    request(tokens, "POST", "/v1/me/library?ids[songs]=$catalogId&representation=ids")
                } else {
                    request(tokens, "DELETE", "/v1/me/library/songs/$libraryId")
                }
            val ok = answer.status in 200..299
            dao.audit(
                audit(
                    op = write.name,
                    target = catalogId,
                    decision = if (ok) "ALLOW" else "ERROR",
                    reason = if (ok) null else "Apple answered ${answer.status}",
                    httpStatus = answer.status,
                    verified = if (ok) true else null,
                ),
            )
            if (!ok) {
                TopToaster.error(
                    context.getString(
                        if (answer.status == 401) R.string.apple_write_signed_out else R.string.apple_library_failed,
                    ),
                )
                return false
            }
            setLocal(entry, song.id, song.liked, inLibrary = add)
            // Mochy's own library toast, the same one the swipe shows
            TopToaster.show(
                text = context.getString(if (add) R.string.added_to_library else R.string.removed_from_library),
                thumbnail = song.thumbnailUrl,
                iconRes = if (add) R.drawable.library_add_check else R.drawable.library_add,
            )
            true
        } catch (e: Exception) {
            dao.audit(audit(write.name, catalogId, "ERROR", reason = e.message))
            TopToaster.error(
                context.getString(
                    if (e is AppleSyncException && e.signedOut) R.string.apple_write_signed_out
                    else R.string.apple_library_failed,
                ),
            )
            false
        }
    }

    // ---- library add, on a favourite ------------------------------------------------------------

    /**
     * Cider's `addToLibrary`, which its `setRating` calls for value 1 when
     * `appleMusic.addToLibraryOnFavorite` is on: `POST /v1/me/library?ids[songs]={id}`.
     *
     * It answers 200 and the row gets a **new `i.` id**; the catalogue id is not the library id, so
     * this does not try to be clever with it — the next library read brings the row back.
     */
    private suspend fun addToLibrary(
        context: Context,
        tokens: AppleTokens.Pair,
        dao: AppleSyncDao,
        catalogId: String,
    ): Boolean {
        if (!(context.dataStore.get(AppleAddToLibraryOnFavoriteKey) ?: true)) return false
        if (refuse(context, dao, AppleWrite.ADD_TO_LIBRARY, catalogId, quiet = true)) return false
        return try {
            val answer = request(tokens, "POST", "/v1/me/library?ids[songs]=$catalogId&representation=ids")
            val ok = answer.status in 200..299
            dao.audit(
                audit(
                    op = AppleWrite.ADD_TO_LIBRARY.name,
                    target = catalogId,
                    decision = if (ok) "ALLOW" else "ERROR",
                    reason = if (ok) null else "Apple answered ${answer.status}",
                    httpStatus = answer.status,
                ),
            )
            ok
        } catch (e: Exception) {
            dao.audit(audit(AppleWrite.ADD_TO_LIBRARY.name, catalogId, "ERROR", reason = e.message))
            false
        }
    }

    // ---- the guard ------------------------------------------------------------------------------

    /** True when the guard said no; the refusal is audited, and the person is told once. */
    private suspend fun refuse(
        context: Context,
        dao: AppleSyncDao,
        write: AppleWrite,
        targetId: String,
        quiet: Boolean = false,
        confirmed: Boolean = false,
    ): Boolean {
        val decision = AppleGuard.decide(
            AppleOp(
                write = write,
                targets = listOf(AppleRef(AppleKind.CATALOG_SONG, targetId)),
                confirmed = confirmed,
            ),
            guardContext(context, dao),
        )
        if (decision !is AppleDecision.Refused) return false
        dao.audit(audit(write.name, targetId, "REFUSE", reason = decision.reason.name))
        if (!quiet) TopToaster.error(context.getString(R.string.apple_write_off))
        return true
    }

    /**
     * The catalogue id for a row: the sidecar's mapping first, then the id itself.
     *
     * An `AM_` id is built from the catalogue id (`AppleImport.kt:212`), so the id is a derivation,
     * not a guess — and it is the only thing left for a song the import never mapped (one opened
     * from a playlist or a shelf). An upload's id is a library id (`i.…`), which no rating or
     * library-add can name.
     */
    private suspend fun catalogueId(dao: AppleSyncDao, songId: String): String? =
        dao.track(songId)?.catalogId
            ?: songId.removePrefix("AM_").takeIf { it.isNotBlank() && !it.startsWith("i.") }

    /** The epoch never changes after it is taken, so reading it once per process is enough. */
    @Volatile
    private var epoch: Set<AppleRef>? = null

    private suspend fun guardContext(context: Context, dao: AppleSyncDao): AppleGuardContext {
        val known = epoch ?: dao.epoch()
            .mapNotNull { appleRefFromKey("${it.kind}:${it.remoteId}") }
            .toSet()
            .also { epoch = it }
        return AppleGuardContext(
            writesEnabled = AppleWritePrefs.enabled(context),
            epoch = known,
            owned = dao.owned(AppleKind.LIBRARY_SONG.name)
                .map { AppleRef(AppleKind.LIBRARY_SONG, it.remoteId) }
                .toSet(),
            linkedPlaylists = emptySet(),
        )
    }

    // ---- the local row --------------------------------------------------------------------------

    /**
     * The row follows Apple, never leads it.
     *
     * Built with `copy`, not `toggleLike()`: that one launches a `YouTube.likeVideo` with the row's
     * id, and for an `AM_` row that is an Apple id YouTube has never had.
     */
    private suspend fun setLocal(
        entry: AppleSongWritesEntryPoint,
        songId: String,
        liked: Boolean,
        inLibrary: Boolean?,
    ): Boolean {
        val database = entry.database()
        val row = database.getSongById(songId)?.song ?: return false
        val flipped = row.copy(
            liked = liked,
            likedDate = when {
                !liked -> null
                row.likedDate != null -> row.likedDate
                else -> LocalDateTime.now()
            },
            inLibrary = when (inLibrary) {
                true -> row.inLibrary ?: LocalDateTime.now()
                false -> null
                null -> row.inLibrary
            },
        )
        if (flipped != row) database.query { update(flipped) }
        return true
    }

    // ---- the budget and the wire ----------------------------------------------------------------

    /**
     * Cider's ceiling: ten writes a minute against the account.
     *
     * A heart tap cannot reach it. When something else does, refusing and saying so is the honest
     * answer — queueing would make the app look stuck instead.
     */
    private object WriteBudget {
        private const val Limit = 10
        private const val WindowMs = 60_000L
        private val stamps = ArrayDeque<Long>()

        fun take(): Boolean = synchronized(stamps) {
            val now = System.currentTimeMillis()
            while (stamps.isNotEmpty() && now - stamps.first() > WindowMs) stamps.removeFirst()
            if (stamps.size >= Limit) return false
            stamps.addLast(now)
            true
        }
    }

    private suspend fun request(
        tokens: AppleTokens.Pair,
        verb: String,
        path: String,
        body: String? = null,
    ): AccountHttp.Response {
        val url = Host + path
        val first = AccountHttp.send(verb, url, tokens.headers, body, "Apple Music")
        // rate limited: one wait for the length Apple asked for, then its answer stands
        if (first.status != 429) return first
        delay((first.retryAfterSeconds ?: 2L).coerceIn(1, 30) * 1000)
        return AccountHttp.send(verb, url, tokens.headers, body, "Apple Music")
    }

    /**
     * Apple's rating for one song: 1 loved, -1 disliked, null none.
     *
     * A 404 is the normal answer for "no rating". Any other non-2xx throws, so a failed read can
     * never pass as "cleared" and report a verification it did not have.
     */
    private suspend fun rated(tokens: AppleTokens.Pair, catalogId: String): Long? {
        val answer = request(tokens, "GET", "/v1/me/ratings/songs/$catalogId")
        if (answer.status == 404) return null
        if (answer.status !in 200..299) {
            throw AppleSyncException("Apple answered ${answer.status}", signedOut = answer.status == 401)
        }
        val data = AccountHttp.parse(answer.body)?.get("data")
        // Apple answers a single rating as either a one-row list or an object; both are the same row
        val row = data.array().firstOrNull()?.obj() ?: data.obj() ?: return null
        return row["attributes"].obj()?.long("value")
    }

    private fun audit(
        op: String,
        target: String?,
        decision: String,
        reason: String? = null,
        httpStatus: Int? = null,
        verified: Boolean? = null,
    ) = AppleAuditEntity(
        at = System.currentTimeMillis(),
        op = op,
        target = target,
        decision = decision,
        reason = reason,
        httpStatus = httpStatus,
        verified = verified,
    )
}
