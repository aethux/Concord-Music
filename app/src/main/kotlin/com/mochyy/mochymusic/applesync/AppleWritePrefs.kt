// Concord: copied from Mocha applesync/AppleWritePrefs.kt (apple-sync@c90b8251).
// One difference, on purpose: Mocha ships this off and turns it on from its own confirmation card;
// Concord has no such card yet and decision 18(d) says writes start on here, so "not set" means on.
package com.mochyy.mochymusic.applesync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.mochyy.mochymusic.utils.dataStore
import com.mochyy.mochymusic.utils.get

/**
 * The switch that lets this app change anything on Apple.
 *
 * It keeps Mocha's key, so a later import of its settings file stays readable, and it is its own key
 * in this app's namespace — not a preference any other screen reads or sets.
 */
object AppleWritePrefs {
    private val Key = booleanPreferencesKey("appleSyncWritesEnabled")

    suspend fun enabled(context: Context): Boolean = context.dataStore.get(Key) ?: true

    suspend fun set(context: Context, on: Boolean) {
        context.dataStore.edit { it[Key] = on }
    }
}
