// Concord: new — playlist folders and playlists the app makes, in Cider's shape.
//
// Cider's calls (concord/cider/apple-api-reference.md §E, executed on this account):
//   new playlist → POST  /v1/me/library/playlists            {"attributes":{"name":"…"}} → 201, `p.…`
//   put in folder→ PUT   /v1/me/library/playlists/{id}/parent {"data":[{"id":"p.…","type":"library-playlist-folders"}]}
//   new folder   → POST  /v1/me/library/playlist-folders      {"attributes":{"name":"…"}} → 201, `p.…`
//   rename folder→ PATCH /v1/me/library/playlist-folders/{id} {"attributes":{"name":"…"}} → 204
//   delete folder→ DELETE /v1/me/library/playlist-folders/{id} → 204 (its playlists stay in the library)
//
// Every call is one write through the same guard, budget and audit log as a song's; the local rows
// and the folder tree are changed only after Apple answered, so a refusal leaves no ghost playlist.
package com.mochyy.mochymusic.applesync

import android.content.Context
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.db.AppleAuditEntity
import com.mochyy.mochymusic.applesync.db.AppleLinkEntity
import com.mochyy.mochymusic.applesync.db.AppleStateEntity
import com.mochyy.mochymusic.applesync.db.PlaylistMetaEntity
import com.mochyy.mochymusic.applesync.di.ApplePlaylistEntryPoint
import com.mochyy.mochymusic.db.entities.PlaylistEntity
import com.mochyy.mochymusic.importer.account.AccountHttp
import com.mochyy.mochymusic.importer.array
import com.mochyy.mochymusic.importer.obj
import com.mochyy.mochymusic.ui.component.TopToaster
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object ApplePlaylistWrites {

    private const val Host = "https://amp-api.music.apple.com"
    const val RootFolder = "p.playlistsroot"

    /** A playlist the app made: its local id, and Apple's. */
    data class Made(val localId: String, val remoteId: String)

    /**
     * Apple's own folder pick at creation: `POST /v1/me/library/playlists`, then the parent call.
     * Returns null when the guard, the sign-in or Apple said no — the caller shows nothing else.
     */
    suspend fun createPlaylist(
        context: Context,
        name: String,
        folderId: String? = null,
    ): Made? {
        val entry = entry(context) ?: return null
        val tokens = tokens(context, entry) ?: return null
        if (!AppleWritePrefs.enabled(context)) {
            refused(context, entry, "CREATE_PLAYLIST", "WRITES_DISABLED")
            return null
        }
        val decision = AppleGuard.decide(
            AppleOp(write = AppleWrite.CREATE_PLAYLIST),
            guardContext(context, entry),
        )
        if (decision is AppleDecision.Refused) {
            refused(context, entry, "CREATE_PLAYLIST", decision.reason.name)
            return null
        }
        if (!WriteBudget.take()) {
            TopToaster.error(context.getString(R.string.apple_write_busy))
            return null
        }

        val body = buildJsonObject { putJsonObject("attributes") { put("name", name) } }.toString()
        val answer = request(tokens, "POST", "/v1/me/library/playlists", body)
        entry.dao().audit(audit("CREATE_PLAYLIST", name, "SEND", httpStatus = answer.status))
        val remoteId = answer.takeIf { it.status in 200..299 }?.let { createdId(it) }
        if (remoteId == null) {
            TopToaster.error(context.getString(R.string.apple_write_failed))
            entry.dao().audit(audit("CREATE_PLAYLIST", name, "FAIL", httpStatus = answer.status))
            return null
        }

        val localId = ApplePlaylistImport.localId(remoteId)
        entry.database().query {
            insert(
                PlaylistEntity(
                    id = localId,
                    name = name,
                    browseId = null,
                    bookmarkedAt = LocalDateTime.now(),
                    isEditable = true,
                    thumbnailUrl = null,
                ),
            )
        }
        entry.dao().savePlaylistMeta(PlaylistMetaEntity(playlistId = localId, canDelete = true))
        entry.dao().saveLink(AppleLinkEntity(playlistId = localId, remoteId = remoteId))
        if (folderId != null && !putParent(tokens, remoteId, folderId)) {
            TopToaster.error(context.getString(R.string.folder_move_failed))
        }
        editTree(entry) { tree ->
            tree.copy(
                parentOf = if (folderId != null) tree.parentOf + (localId to folderId) else tree.parentOf,
            )
        }
        return Made(localId, remoteId)
    }

    /** A new folder: Apple first, then the stored tree. */
    suspend fun createFolder(context: Context, name: String): String? {
        val entry = entry(context) ?: return null
        val tokens = tokens(context, entry) ?: return null
        val allowed = allow(context, entry, AppleWrite.CREATE_FOLDER, "CREATE_FOLDER", name) ?: return null
        if (!allowed) return null
        val body = buildJsonObject { putJsonObject("attributes") { put("name", name) } }.toString()
        val answer = request(tokens, "POST", "/v1/me/library/playlist-folders", body)
        val remoteId = answer.takeIf { it.status in 200..299 }?.let { createdId(it) }
        if (remoteId == null) {
            TopToaster.error(context.getString(R.string.apple_write_failed))
            return null
        }
        editTree(entry) { tree -> tree.copy(folders = tree.folders + PlaylistFolders.Folder(remoteId, name)) }
        return remoteId
    }

    suspend fun renameFolder(context: Context, folderId: String, name: String): Boolean {
        val entry = entry(context) ?: return false
        val tokens = tokens(context, entry) ?: return false
        if (allow(context, entry, AppleWrite.RENAME_FOLDER, "RENAME_FOLDER", folderId) != true) return false
        val body = buildJsonObject { putJsonObject("attributes") { put("name", name) } }.toString()
        val answer = settled(tokens, "PATCH", "/v1/me/library/playlist-folders/$folderId", body)
        if (answer.status !in 200..299) {
            TopToaster.error(context.getString(R.string.apple_write_failed))
            return false
        }
        editTree(entry) { tree ->
            tree.copy(folders = tree.folders.map { if (it.id == folderId) it.copy(name = name) else it })
        }
        return true
    }

    /** The folder goes, the playlists inside it stay in the library. Only ever after a person said yes. */
    suspend fun deleteFolder(context: Context, folderId: String): Boolean {
        val entry = entry(context) ?: return false
        val tokens = tokens(context, entry) ?: return false
        if (allow(context, entry, AppleWrite.DELETE_FOLDER, "DELETE_FOLDER", folderId, confirmed = true) != true) {
            return false
        }
        val answer = settled(tokens, "DELETE", "/v1/me/library/playlist-folders/$folderId", null)
        if (answer.status !in 200..299) {
            TopToaster.error(context.getString(R.string.apple_write_failed))
            return false
        }
        editTree(entry) { tree ->
            tree.copy(
                folders = tree.folders.filterNot { it.id == folderId },
                parentOf = tree.parentOf.filterValues { it != folderId },
            )
        }
        return true
    }

    /** Moves a playlist into [folderId], or back to the library's root when it is null. */
    suspend fun movePlaylist(
        context: Context,
        localId: String,
        remoteId: String,
        folderId: String?,
    ): Boolean {
        val entry = entry(context) ?: return false
        val tokens = tokens(context, entry) ?: return false
        val decision = AppleGuard.decide(
            AppleOp(
                write = AppleWrite.MOVE_PLAYLIST,
                playlistId = localId,
                playlistName = null,
            ),
            guardContext(context, entry),
        )
        if (decision is AppleDecision.Refused) {
            refused(context, entry, "MOVE_PLAYLIST", decision.reason.name)
            return false
        }
        if (!putParent(tokens, remoteId, folderId ?: RootFolder)) {
            TopToaster.error(context.getString(R.string.folder_move_failed))
            return false
        }
        editTree(entry) { tree ->
            tree.copy(
                parentOf = if (folderId != null) {
                    tree.parentOf + (localId to folderId)
                } else {
                    tree.parentOf - localId
                },
            )
        }
        return true
    }

    /**
     * Apple answers 500 "Unable to set parent" for a folder it made a moment ago; Cider sees the
     * same, and a second ask lands. Three tries, under a second apart, then the answer stands.
     */
    private suspend fun settled(
        tokens: AppleTokens.Pair,
        verb: String,
        path: String,
        body: String?,
    ): AccountHttp.Response {
        var answer = request(tokens, verb, path, body)
        var tries = 0
        while (answer.status == 500 && tries < 2) {
            delay(900)
            answer = request(tokens, verb, path, body)
            tries++
        }
        return answer
    }

    private suspend fun putParent(tokens: AppleTokens.Pair, playlistRemoteId: String, parent: String): Boolean {
        val body = buildJsonArray {
            add(
                buildJsonObject {
                    put("id", parent)
                    put("type", "library-playlist-folders")
                },
            )
        }.toString()
        val wrapped = buildJsonObject { put("data", kotlinx.serialization.json.Json.parseToJsonElement(body)) }
        val answer = settled(
            tokens,
            "PUT",
            "/v1/me/library/playlists/$playlistRemoteId/parent",
            wrapped.toString(),
        )
        return answer.status in 200..299
    }

    /** The folder to put a fresh folder's playlists in, or null when the tree has no such value. */

    private fun createdId(answer: AccountHttp.Response): String? {
        val data = AccountHttp.parse(answer.body)?.get("data") ?: return null
        return data.array().firstOrNull()?.obj()?.get("id")?.let { element ->
            (element as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
    }

    private suspend fun editTree(
        entry: ApplePlaylistEntryPoint,
        edit: (PlaylistFolders.Tree) -> PlaylistFolders.Tree,
    ) {
        val tree = PlaylistFolders.decode(entry.dao().state(PlaylistFolders.KEY))
        entry.dao().saveState(
            AppleStateEntity(key = PlaylistFolders.KEY, value = PlaylistFolders.encode(edit(tree))),
        )
    }

    private suspend fun allow(
        context: Context,
        entry: ApplePlaylistEntryPoint,
        write: AppleWrite,
        op: String,
        target: String,
        confirmed: Boolean = false,
    ): Boolean? {
        if (!AppleWritePrefs.enabled(context)) {
            refused(context, entry, op, "WRITES_DISABLED")
            return null
        }
        val decision = AppleGuard.decide(
            AppleOp(write = write, confirmed = confirmed),
            guardContext(context, entry),
        )
        if (decision is AppleDecision.Refused) {
            refused(context, entry, op, decision.reason.name)
            return null
        }
        if (!WriteBudget.take()) {
            TopToaster.error(context.getString(R.string.apple_write_busy))
            return null
        }
        return true
    }

    private suspend fun refused(context: Context, entry: ApplePlaylistEntryPoint, op: String, reason: String) {
        runCatching { entry.dao().audit(audit(op, null, "REFUSE", reason = reason)) }
        TopToaster.error(context.getString(R.string.apple_write_off))
    }

    private suspend fun entry(context: Context): ApplePlaylistEntryPoint? = runCatching {
        EntryPointAccessors.fromApplication(context.applicationContext, ApplePlaylistEntryPoint::class.java)
    }.getOrNull()

    private suspend fun tokens(context: Context, entry: ApplePlaylistEntryPoint): AppleTokens.Pair? {
        val pair = runCatching { entry.tokens().read() }.getOrNull()
        if (pair == null) {
            entry.dao().audit(audit("PLAYLIST_WRITE", null, "REFUSE", reason = "SIGNED_OUT"))
            TopToaster.error(context.getString(R.string.apple_write_signed_out))
        }
        return pair
    }

    @Volatile
    private var epoch: Set<AppleRef>? = null

    private suspend fun guardContext(context: Context, entry: ApplePlaylistEntryPoint): AppleGuardContext {
        val known = epoch ?: entry.dao().epoch()
            .mapNotNull { appleRefFromKey("${it.kind}:${it.remoteId}") }
            .toSet()
            .also { epoch = it }
        return AppleGuardContext(
            writesEnabled = AppleWritePrefs.enabled(context),
            epoch = known,
            owned = entry.dao().owned("PLAYLIST").map { AppleRef(AppleKind.PLAYLIST, it.remoteId) }.toSet(),
            // one-shot read of the linked ids; the UI observes the same table itself
            linkedPlaylists = entry.dao().links().first().map { it.playlistId }.toSet(),
        )
    }

    private suspend fun request(
        tokens: AppleTokens.Pair,
        verb: String,
        path: String,
        body: String?,
    ): AccountHttp.Response {
        val url = Host + path
        val first = AccountHttp.send(verb, url, tokens.headers, body, "Apple Music")
        if (first.status != 429) return first
        delay((first.retryAfterSeconds ?: 2L).coerceIn(1, 30) * 1000)
        return AccountHttp.send(verb, url, tokens.headers, body, "Apple Music")
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
}
