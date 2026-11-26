// Concord: new — Apple's playlist folders as one stored tree, the way Cider keeps them.
package com.mochyy.mochymusic.applesync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Library → Playlists shows folders that expand in place, so the app needs two things the music
 * database does not hold: Apple's folder names, and which playlist sits in which folder.
 *
 * Both are read from Apple's own list — `/v1/me/library/playlists?include=parent` gives each row its
 * parent (the root is `p.playlistsroot`), and `/v1/me/library/playlist-folders` gives the names —
 * and kept in the sidecar's key/value state as one JSON document. No schema change to either
 * database: the tree is replaced wholesale on every full refresh, exactly as Apple sends it.
 */
object PlaylistFolders {

    const val KEY = "playlist_folders"
    private const val ROOT = "p.playlistsroot"

    data class Folder(val id: String, val name: String)

    /** [parentOf] is keyed by the local playlist id, so the library page can look a row up directly. */
    data class Tree(val folders: List<Folder>, val parentOf: Map<String, String>) {
        val isEmpty: Boolean get() = folders.isEmpty() && parentOf.isEmpty()
    }

    fun encode(playlists: List<RemotePlaylist>, folders: List<RemotePlaylistFolder>): String {
        val json = buildJsonObject {
            putJsonArray("folders") {
                folders.forEach { folder ->
                    addJsonObject {
                        put("id", folder.id)
                        put("name", folder.name)
                    }
                }
            }
            putJsonObject("parents") {
                playlists.forEach { playlist ->
                    val parent = playlist.folderId?.takeIf { it.isNotBlank() && it != ROOT } ?: return@forEach
                    put(ApplePlaylistImport.localId(playlist.id), parent)
                }
            }
        }
        return json.toString()
    }

    fun decode(raw: String?): Tree {
        if (raw.isNullOrBlank()) return Tree(emptyList(), emptyMap())
        return runCatching {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val folders = obj["folders"]?.jsonArray.orEmpty().mapNotNull { element ->
                val row = element.jsonObject
                val id = row["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = row["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                Folder(id = id, name = name)
            }
            val parents = (obj["parents"] as? JsonObject).orEmpty().mapNotNull { (playlistId, folderId) ->
                val parent = folderId.jsonPrimitive.content.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                playlistId to parent
            }.toMap()
            Tree(folders = folders, parentOf = parents)
        }.getOrDefault(Tree(emptyList(), emptyMap()))
    }
}
