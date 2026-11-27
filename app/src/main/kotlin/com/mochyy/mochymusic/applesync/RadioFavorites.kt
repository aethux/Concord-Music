// Concord: new — the stations kept on top of the Radio page, on this phone.
//
// Cider has no station favourite: its library surface is `songs, albums, playlists`
// (`LIBRARY_ADDABLE_TYPES`), `setFavoriteState` writes `/v1/me/favorites?ids[<type>]` for artists,
// albums and playlists only, and Apple's own `inFavorites` is never read on a station. That endpoint
// has no read at all (a GET answers 404), so a station favourite cannot be stored in the account and
// read back — the list has to live here.
//
// The card's own words and picture are stored with it, so the row draws before any request, and a
// station Apple later drops from its page still opens. The row is a copy: Apple's page is still the
// source of what a station looks like today, and the same station also stays in its own section.
package com.mochyy.mochymusic.applesync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mochyy.mochymusic.utils.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

object RadioFavorites {

    private val Key = stringPreferencesKey("radio_favorites")

    /** the stations kept on top, newest first; empty when there are none */
    fun rows(context: Context): Flow<List<AppleRadioItem>> =
        context.applicationContext.dataStore.data
            // a settings file that cannot be read is no favourites, not a page that crashes
            .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
            .map { prefs -> decode(prefs[Key]) }

    /** add or remove [item]; true when it is now kept */
    suspend fun toggle(context: Context, item: AppleRadioItem): Boolean {
        var kept = false
        context.applicationContext.dataStore.edit { prefs ->
            val rows = decode(prefs[Key]).toMutableList()
            val at = rows.indexOfFirst { it.id == item.id }
            if (at >= 0) {
                rows.removeAt(at)
            } else {
                rows.add(0, item)
                kept = true
            }
            prefs[Key] = encode(rows)
        }
        return kept
    }

    private fun encode(rows: List<AppleRadioItem>): String {
        val array = JSONArray()
        rows.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("kind", item.kind.name)
                    put("title", item.title)
                    put("subtitle", item.subtitle ?: "")
                    put("artwork", item.artworkUrl ?: "")
                    put("playback", item.playback.name)
                    put("url", item.url ?: "")
                },
            )
        }
        return array.toString()
    }

    private fun decode(raw: String?): List<AppleRadioItem> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val o = runCatching { array.getJSONObject(i) }.getOrNull() ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(
                    AppleRadioItem(
                        id = id,
                        kind = runCatching { AppleRadioItem.Kind.valueOf(o.optString("kind")) }
                            .getOrDefault(AppleRadioItem.Kind.Station),
                        title = o.optString("title"),
                        subtitle = o.optString("subtitle").takeIf { it.isNotBlank() },
                        artworkUrl = o.optString("artwork").takeIf { it.isNotBlank() },
                        playback = runCatching { AppleRadioItem.Playback.valueOf(o.optString("playback")) }
                            .getOrDefault(AppleRadioItem.Playback.Tracks),
                        url = o.optString("url").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }
}
