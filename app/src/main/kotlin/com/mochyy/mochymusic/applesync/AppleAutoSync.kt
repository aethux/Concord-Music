// Concord: the app used to sync only when someone tapped "Read Apple Music library". A library that
// drifts for days is not what two-way means, so each app open asks whether the last sync is old and,
// when it is, starts the same service that page starts. No schedule and no background wake-up: the
// person opening the app is the trigger, so nothing runs while they are not using it.
package com.mochyy.mochymusic.applesync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.mochyy.mochymusic.utils.dataStore
import com.mochyy.mochymusic.utils.get
import com.mochyy.mochymusic.applesync.di.ApplePlaylistEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AppleAutoSync {

    /** Six hours: often enough to feel live, rare enough that opening the app is rarely delayed. */
    private const val INTERVAL_MS = 6L * 60 * 60 * 1000

    /** On by default; the switch on the Import & sync page reads the same key. */
    val Key = booleanPreferencesKey("appleSyncOnOpen")

    suspend fun enabled(context: Context): Boolean =
        runCatching { context.dataStore.get(Key) }.getOrNull() ?: true

    suspend fun setEnabled(context: Context, on: Boolean) {
        context.dataStore.edit { it[Key] = on }
    }

    /**
     * Called once per app open. Reads the sidecar for the last sync's timestamp, so a fresh install
     * (no rows) counts as due; a signed-out app does nothing, the same as the manual button.
     */
    fun maybeStart(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val entry = runCatching {
                EntryPointAccessors.fromApplication(app, ApplePlaylistEntryPoint::class.java)
            }.getOrNull() ?: return@launch
            val signedIn = runCatching { entry.tokens().read() }.getOrNull() != null
            if (!signedIn) return@launch
            val enabled = runCatching { app.dataStore.get(Key) }.getOrNull() ?: true
            if (!enabled) return@launch
            val last = runCatching {
                entry.dao().state(AppleSyncFlow.KEY_LAST_SYNC_AT)?.toLongOrNull() ?: 0L
            }.getOrDefault(0L)
            if (System.currentTimeMillis() - last < INTERVAL_MS) return@launch
            runCatching { AppleSyncService.start(app) }
        }
    }
}
