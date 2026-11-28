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
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    /**
     * Cider's rename: `PATCH /v1/me/library/playlists/{id}` with `{attributes:{name, description}}`.
     * The person's Done in the edit sheet is the confirmation the guard asks for; a playlist Apple
     * says cannot be edited is still refused before the request is built.
     */
    suspend fun rename(context: Context, playlistId: String, name: String, description: String?): Boolean {
        if (!playlistId.startsWith("AM_PL_")) return false
        val entry = entry(context) ?: return false
        val remoteId = playlistId.removePrefix("AM_PL_")
        val current = runCatching { entry.database().playlist(playlistId).first() }
            .getOrNull()?.playlist ?: return false
        val tokens = tokens(context, entry) ?: return false
        if (!AppleWritePrefs.enabled(context)) {
            refused(context, entry, "RENAME_PLAYLIST", "WRITES_DISABLED")
            return false
        }
        val decision = AppleGuard.decide(
            AppleOp(
                write = AppleWrite.RENAME_PLAYLIST,
                playlistId = playlistId,
                playlistName = current.name,
                playlistEditable = current.isEditable,
                targets = listOf(AppleRef(AppleKind.PLAYLIST, remoteId)),
                confirmed = true,
            ),
            guardContext(context, entry),
        )
        if (decision is AppleDecision.Refused) {
            entry.dao().audit(audit("RENAME_PLAYLIST", remoteId, "REFUSE", reason = decision.reason.name))
            TopToaster.error(
                context.getString(
                    if (decision.reason == AppleRefusal.PLAYLIST_NOT_EDITABLE) R.string.apple_playlist_not_editable
                    else R.string.apple_write_off,
                ),
            )
            return false
        }
        if (!WriteBudget.take()) {
            TopToaster.error(context.getString(R.string.apple_write_busy))
            return false
        }

        val body = buildJsonObject {
            putJsonObject("attributes") {
                put("name", name)
                put("description", description.orEmpty())
            }
        }.toString()
        val answer = request(tokens, "PATCH", "/v1/me/library/playlists/$remoteId", body)
        val ok = answer.status in 200..299
        entry.dao().audit(
            audit(
                op = "RENAME_PLAYLIST",
                target = remoteId,
                decision = if (ok) "ALLOW" else "ERROR",
                reason = if (ok) null else "Apple answered ${answer.status}",
                httpStatus = answer.status,
            ),
        )
        if (!ok) {
            TopToaster.error(context.getString(R.string.apple_playlist_failed))
            return false
        }
        entry.database().update(
            current.copy(name = name, description = description?.ifBlank { null }, lastUpdateTime = LocalDateTime.now()),
        )
        return true
    }

    /**
     * Cider's delete: `DELETE /v1/me/library/playlists/{id}`. Only when Apple's own `canDelete` says
     * the account owns it, and only on the person's confirming tap (the sheet's dialog). The local
     * rows are the caller's business — this only changes Apple.
     */
    suspend fun delete(context: Context, playlistId: String): Boolean {
        if (!playlistId.startsWith("AM_PL_")) return false
        val entry = entry(context) ?: return false
        val remoteId = playlistId.removePrefix("AM_PL_")
        val meta = entry.dao().playlistMeta(playlistId)
        val tokens = tokens(context, entry) ?: return false
        if (meta?.canDelete != true) {
            // a local copy left over from an older sync can be deleted from this app even though
            // Apple no longer owns it — but only when Apple confirms it has no such playlist. A
            // playlist the account follows still answers 200, and is refused as before.
            val probe = request(tokens, "GET", "/v1/me/library/playlists/$remoteId?fields%5Blibrary-playlists%5D=name", null)
            if (probe.status == 404) {
                entry.dao().audit(audit("DELETE_PLAYLIST", remoteId, "ALLOW", reason = "already gone from Apple", httpStatus = 404))
                return true
            }
            entry.dao().audit(audit("DELETE_PLAYLIST", remoteId, "REFUSE", reason = "NOT_OWNED"))
            TopToaster.error(context.getString(R.string.apple_playlist_not_editable))
            return false
        }
        if (!AppleWritePrefs.enabled(context)) {
            refused(context, entry, "DELETE_PLAYLIST", "WRITES_DISABLED")
            return false
        }
        val current = runCatching { entry.database().playlist(playlistId).first() }.getOrNull()?.playlist
        val decision = AppleGuard.decide(
            AppleOp(
                write = AppleWrite.DELETE_PLAYLIST,
                playlistId = playlistId,
                playlistName = current?.name,
                playlistEditable = current?.isEditable ?: true,
                targets = listOf(AppleRef(AppleKind.PLAYLIST, remoteId)),
                confirmed = true,
            ),
            guardContext(context, entry),
        )
        if (decision is AppleDecision.Refused) {
            entry.dao().audit(audit("DELETE_PLAYLIST", remoteId, "REFUSE", reason = decision.reason.name))
            TopToaster.error(context.getString(R.string.apple_write_off))
            return false
        }
        if (!WriteBudget.take()) {
            TopToaster.error(context.getString(R.string.apple_write_busy))
            return false
        }
        val answer = request(tokens, "DELETE", "/v1/me/library/playlists/$remoteId", null)
        // 404 means Apple does not have it any more (a local copy left over from an older sync);
        // the deletion has nothing left to do, so the local row may go too. A 5xx is checked the
        // same way: Apple answered 500 for a playlist its API had already deleted
        val gone = answer.status == 404 ||
            (answer.status >= 500 &&
                request(tokens, "GET", "/v1/me/library/playlists/$remoteId?fields%5Blibrary-playlists%5D=name", null).status == 404)
        val ok = answer.status in 200..299 || gone
        entry.dao().audit(
            audit(
                op = "DELETE_PLAYLIST",
                target = remoteId,
                decision = if (ok) "ALLOW" else "ERROR",
                reason = when {
                    gone -> "already gone from Apple"
                    ok -> null
                    else -> "Apple answered ${answer.status}"
                },
                httpStatus = answer.status,
            ),
        )
        if (!ok) TopToaster.error(context.getString(R.string.apple_playlist_failed))
        return ok
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

    // ---- tracks: Cider's whole-list replace -----------------------------------------------------

    private data class TrackRow(val id: String, val type: String, val catalogId: String?)

    /**
     * Sends [playlistId]'s songs, in its order, to Apple — Cider's shape (`apple-api-reference.md`
     * §D): `POST …/tracks` for the songs new here, then one `PUT …/tracks` with the whole list.
     * The PUT is both the reorder and the removal: a song taken out here is simply not in the list.
     *
     * Only songs Apple already holds in this playlist, plus the ones added here since the last
     * import, are sent. A song Apple no longer has is not put back, and a row with no Apple copy
     * (a local file) is left out. If anything cannot be named on Apple, nothing is sent — a short
     * PUT would quietly delete the rest.
     */
    suspend fun pushTracks(context: Context, playlistId: String, quiet: Boolean = false): Boolean {
        if (!playlistId.startsWith("AM_PL_")) return false
        val entry = entry(context) ?: return false
        val remoteId = playlistId.removePrefix("AM_PL_")
        val link = entry.dao().link(playlistId) ?: return false
        val playlist = runCatching { entry.database().playlist(playlistId).first() }.getOrNull()?.playlist
        if (playlist == null) return false
        if (!AppleWritePrefs.enabled(context)) {
            refused(context, entry, "REPLACE_TRACKS", "WRITES_DISABLED")
            return false
        }
        if (!playlist.isEditable) {
            entry.dao().audit(audit("REPLACE_TRACKS", remoteId, "REFUSE", reason = "PLAYLIST_NOT_EDITABLE"))
            if (!quiet) TopToaster.error(context.getString(R.string.apple_playlist_not_editable))
            return false
        }
        val decision = AppleGuard.decide(
            AppleOp(
                write = AppleWrite.REPLACE_TRACKS,
                playlistId = playlistId,
                playlistName = playlist.name,
                playlistEditable = playlist.isEditable,
                targets = listOf(AppleRef(AppleKind.PLAYLIST, remoteId)),
            ),
            guardContext(context, entry),
        )
        if (decision is AppleDecision.Refused) {
            entry.dao().audit(audit("REPLACE_TRACKS", remoteId, "REFUSE", reason = decision.reason.name))
            if (!quiet) TopToaster.error(context.getString(R.string.apple_write_off))
            return false
        }
        if (!WriteBudget.take()) {
            entry.dao().audit(audit("REPLACE_TRACKS", remoteId, "REFUSE", reason = "RATE_LIMITED"))
            if (!quiet) TopToaster.error(context.getString(R.string.apple_write_busy))
            return false
        }
        val tokens = tokens(context, entry) ?: return false

        val current = appleTracks(tokens, remoteId)
        if (current == null) {
            if (!quiet) TopToaster.error(context.getString(R.string.apple_playlist_unavailable))
            return false
        }
        val byCatalog = current.mapNotNull { row -> row.catalogId?.let { it to row } }.toMap()
        val byId = current.associateBy { it.id }

        val maps = entry.database().playlistSongMaps(playlistId, 0).sortedBy { it.position }
        val previous = link.lastSignature.orEmpty().split(",").filter { it.isNotBlank() }.toSet()
        val addedHere = maps.map { it.songId }.filterNot { it in previous }

        // what Apple already knows, in this order
        val order = mutableListOf<TrackRow>()
        // songs added here that Apple does not list yet: catalogue ids, and uploads by their
        // library id (an upload has no catalogue id, and Apple accepts `i.` here just the same)
        val unresolved = mutableListOf<TrackRow>()
        for (map in maps) {
            val id = map.songId
            when {
                id.startsWith("AM_i.") -> {
                    val appleId = id.removePrefix("AM_")
                    val row = byId[appleId]
                    if (row != null) order += row else if (id in addedHere) {
                        unresolved += TrackRow(appleId, "library-songs", null)
                    }
                }
                id.startsWith("AM_") -> {
                    val catalog = id.removePrefix("AM_")
                    val row = byCatalog[catalog]
                    if (row != null) {
                        order += row
                    } else if (id in addedHere) {
                        unresolved += TrackRow(catalog, "songs", catalog)
                    }
                    // else: Apple no longer has it in this playlist and it was not added here
                }
                // a local file or a YouTube row: never part of an Apple playlist
            }
        }

        if (unresolved.isNotEmpty()) {
            val added = addTracks(tokens, remoteId, unresolved.distinctBy { it.id })
            entry.dao().audit(
                audit(
                    op = "ADD_TRACKS",
                    target = remoteId,
                    decision = if (added) "ALLOW" else "ERROR",
                    reason = if (added) null else "Apple didn't take the new songs",
                ),
            )
            if (!added) {
                entry.dao().audit(audit("REPLACE_TRACKS", remoteId, "SKIP", reason = "ADD_FAILED"))
                if (!quiet) TopToaster.error(context.getString(R.string.apple_playlist_failed))
                return false
            }
            // read again: a new entry's Apple id is only known from the list
            val after = appleTracks(tokens, remoteId)
            if (after == null) {
                if (!quiet) TopToaster.error(context.getString(R.string.apple_playlist_unavailable))
                return false
            }
            val afterCatalog = after.mapNotNull { row -> row.catalogId?.let { it to row } }.toMap()
            val afterIds = after.mapTo(HashSet()) { it.id }
            val still = unresolved.filterNot { row ->
                // a song sent by its catalogue id comes back as a `library-songs` row carrying that
                // id in `playParams.catalogId`, so its own id never matches: both keys count
                row.id in afterIds || row.catalogId?.let { it in afterCatalog } == true
            }
            if (still.isNotEmpty()) {
                entry.dao().audit(audit("REPLACE_TRACKS", remoteId, "SKIP", reason = "UNRESOLVED ${still.size}"))
                if (!quiet) TopToaster.error(context.getString(R.string.apple_playlist_failed))
                return false
            }
            // rebuild the order with the ids Apple just gave the new rows
            val rebuilt = mutableListOf<TrackRow>()
            for (map in maps) {
                val id = map.songId
                when {
                    id.startsWith("AM_i.") -> after.firstOrNull { it.id == id.removePrefix("AM_") }?.let { rebuilt += it }
                    id.startsWith("AM_") -> afterCatalog[id.removePrefix("AM_")]?.let { rebuilt += it }
                }
            }
            order.clear()
            order += rebuilt
        }

        if (order.isEmpty()) {
            entry.dao().audit(audit("REPLACE_TRACKS", remoteId, "REFUSE", reason = "EMPTY_REPLACEMENT"))
            if (!quiet) TopToaster.error(context.getString(R.string.apple_playlist_failed))
            return false
        }

        val body = buildJsonObject {
            putJsonArray("data") {
                order.forEach { row ->
                    addJsonObject {
                        put("id", row.id)
                        put("type", row.type)
                    }
                }
            }
        }.toString()
        val answer = request(tokens, "PUT", "/v1/me/library/playlists/$remoteId/tracks", body)
        val ok = answer.status in 200..299
        entry.dao().audit(
            audit(
                op = "REPLACE_TRACKS",
                target = remoteId,
                decision = if (ok) "ALLOW" else "ERROR",
                reason = if (ok) null else "Apple answered ${answer.status}",
                httpStatus = answer.status,
            ),
        )
        if (!ok) {
            if (!quiet) TopToaster.error(context.getString(R.string.apple_playlist_failed))
            return false
        }
        entry.dao().saveLink(link.copy(lastSignature = maps.joinToString(",") { it.songId }))
        return true
    }

    /** Apple's own list for this playlist: the ids it will accept back, and their catalogue ids. */
    private suspend fun appleTracks(tokens: AppleTokens.Pair, remoteId: String): List<TrackRow>? {
        val rows = mutableListOf<TrackRow>()
        var offset = 0
        while (true) {
            val answer = request(
                tokens,
                "GET",
                "/v1/me/library/playlists/$remoteId/tracks?limit=100&offset=$offset",
                null,
            )
            // An EMPTY playlist has no `tracks` relationship at all, and Apple says so with 404 "No
            // related resources" rather than an empty `data` (probed 2026-11-28). Without this a new
            // playlist could never receive its first song: this read answered 404, the push stopped
            // before building anything, and no audit row recorded why. If the playlist itself is
            // gone the POST/PUT that follows reports it, so 404 here is an empty list, not an error.
            if (answer.status == 404) return rows
            if (answer.status !in 200..299) return null
            val data = AccountHttp.parse(answer.body)?.get("data")?.array() ?: return rows
            if (data.isEmpty()) return rows
            for (element in data) {
                val row = element.obj() ?: continue
                val id = (row["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
                val type = (row["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "library-songs"
                val catalog = row["attributes"]?.obj()
                    ?.get("playParams")?.obj()
                    ?.get("catalogId")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                rows += TrackRow(id, type, catalog)
            }
            if (data.size < 100) return rows
            offset += data.size
        }
    }

    /** `POST …/tracks` — Cider's add, which appends in the order sent. */
    private suspend fun addTracks(tokens: AppleTokens.Pair, remoteId: String, rows: List<TrackRow>): Boolean {
        val body = buildJsonObject {
            putJsonArray("data") {
                rows.forEach { row ->
                    addJsonObject {
                        put("id", row.id)
                        put("type", row.type)
                    }
                }
            }
        }.toString()
        val answer = request(tokens, "POST", "/v1/me/library/playlists/$remoteId/tracks", body)
        return answer.status in 200..299
    }

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
