// Concord: copied from Mocha applesync/AppleRefreshSchedule.kt (apple-sync@c90b8251). Package and the
// data key kept; the row that drives it lives on Import & sync. A background run only reads Apple and
// imports the answer — AppleSyncFlow never pushes to Apple — so the writes switch does not gate it.
package com.mochyy.mochymusic.applesync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mochyy.mochymusic.applesync.di.ApplePlaylistEntryPoint
import com.mochyy.mochymusic.utils.dataStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class AppleRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // A no-op run is quiet and a failure just waits for the next tick: signed out or offline,
        // there is nothing to say to anyone who is not looking.
        return runCatching { AppleSyncFlow.run(applicationContext) }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.success() })
    }
}

/** Turns Import & sync's background-refresh row into a WorkManager schedule. */
object AppleRefreshSchedule {
    private const val WorkName = "apple_sync_refresh"
    private val MinutesKey = intPreferencesKey("appleSyncAutoRefreshMinutes")

    // 0 is off; the rest are the honest choices. WorkManager's floor is 15 minutes and Doze runs it
    // late, so anything shorter than 15 would be a lie on the row.
    val choices = listOf(0, 15, 60, 6 * 60, 24 * 60)

    suspend fun readMinutes(context: Context): Int =
        context.dataStore.data.first()[MinutesKey] ?: 0

    suspend fun saveMinutes(
        context: Context,
        minutes: Int,
    ) {
        context.dataStore.edit { it[MinutesKey] = minutes }
    }

    /**
     * When the last finished sync ran, ms since 1970, or 0 when there has never been one. The page
     * reads it so an interval change can be told apart from an interval that never fired: a
     * background run posts no notification, so this is the only thing that shows it happened.
     */
    suspend fun lastReadAt(context: Context): Long =
        runCatching {
            val entry =
                EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    ApplePlaylistEntryPoint::class.java,
                )
            entry.dao().state(AppleSyncFlow.KEY_LAST_SYNC_AT)?.toLongOrNull() ?: 0L
        }.getOrDefault(0L)

    /** 0 stops the schedule; anything else moves it to the new interval. */
    fun apply(
        context: Context,
        minutes: Int,
    ) {
        val work = WorkManager.getInstance(context)
        if (minutes <= 0) {
            work.cancelUniqueWork(WorkName)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<AppleRefreshWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
        work.enqueueUniquePeriodicWork(WorkName, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
