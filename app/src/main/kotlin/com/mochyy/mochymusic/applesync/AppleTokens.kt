// Concord: adapted from Mocha applesync/AppleTokens.kt (apple-sync@c90b8251).
//
// Mochy already keeps this exact token pair in the built-in Apple module's own storage:
// ModuleRegistry.hostFor() opens SharedPreferences "module_<id>", and AppleMusicModule writes
// `developer_token` / `user_token` there when its sign-in page runs. So this reads and writes that one
// store instead of adding a second copy. The values stay plain text, exactly as the module's own copy
// already is in this app (ModuleStorage does not seal); sealing both belongs to its own task, recorded
// in concord/engine-port.md, and is not quietly changed here.
package com.mochyy.mochymusic.applesync

import android.content.Context
import androidx.core.content.edit
import com.mochyy.mochymusic.module.applemusic.AppleMusicModule

class AppleTokens(private val context: Context) {

    data class Pair(val developer: String, val user: String) {
        val headers: Map<String, String>
            get() = mapOf(
                "Authorization" to "Bearer $developer",
                "Media-User-Token" to user,
                "Origin" to "https://music.apple.com",
                "Referer" to "https://music.apple.com/",
                "Accept" to "application/json",
            )
    }

    suspend fun read(): Pair? {
        val store = store()
        val developer = store.getString(DeveloperKey, null)?.takeIf(String::isNotBlank) ?: return null
        val user = store.getString(UserKey, null)?.takeIf(String::isNotBlank) ?: return null
        return Pair(developer, user)
    }

    suspend fun save(developer: String, user: String) {
        store().edit {
            putString(DeveloperKey, developer.trim())
            putString(UserKey, user.trim())
        }
        AppleSession.clear()
    }

    suspend fun clear() {
        store().edit { remove(DeveloperKey).remove(UserKey) }
    }

    private fun store() =
        context.getSharedPreferences("module_${AppleMusicModule.Id}", Context.MODE_PRIVATE)

    private companion object {
        // AppleMusicModule's own keys: storage.get("developer_token") / storage.get("user_token")
        const val DeveloperKey = "developer_token"
        const val UserKey = "user_token"
    }
}
