// Concord: new — the two switches behind Apple's play report.
package com.mochyy.mochymusic.applesync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.mochyy.mochymusic.utils.dataStore
import com.mochyy.mochymusic.utils.get

/**
 * Whether a play is told to Apple at all, and whether it is told privately.
 *
 * Reporting starts on, as Apple's own clients ship it; private listening starts off, because it is the
 * exception and because it only means something while reporting is on.
 */
object ApplePlayPrefs {
    val ReportingKey = booleanPreferencesKey("applePlayReporting")
    val PrivateKey = booleanPreferencesKey("applePrivateListening")

    suspend fun reporting(context: Context): Boolean = context.dataStore.get(ReportingKey) ?: true

    suspend fun privateListening(context: Context): Boolean = context.dataStore.get(PrivateKey) ?: false

    suspend fun setReporting(context: Context, on: Boolean) {
        context.dataStore.edit { it[ReportingKey] = on }
    }

    suspend fun setPrivateListening(context: Context, on: Boolean) {
        context.dataStore.edit { it[PrivateKey] = on }
    }
}
