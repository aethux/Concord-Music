// Concord: new; notification and lifecycle modelled on Mocha applesync/AppleSyncService.kt
// (apple-sync@c90b8251).
//
// A matching run that keeps going when the app goes to the background, and says so.
//
// The pass itself is an HTTP loop over a few thousand searches; leaving Import and sync used to stop
// it because the work belonged to that screen. A foreground service is the only thing Android lets
// run for that long while the app is hidden, and the notification doubles as the progress report —
// the same numbers the settings row shows when the app is open.
package com.mochyy.mochymusic.applesync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.db.AppleSyncDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AppleMatchService : Service() {

    @Inject
    lateinit var dao: AppleSyncDao

    companion object {
        private const val CHANNEL_ID = "apple_match"
        private const val NOTIFICATION_ID = 4212
        private const val ACTION_STOP = "com.concord.player.action.STOP_APPLE_MATCH"

        /** songs per pass; only decides how often the bar is rebuilt, not what gets matched */
        private const val PASS = 200

        /** the counters move per song, a notification per song is a waste */
        private const val POST_EVERY_MS = 2_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AppleMatchService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** songs this run has given a twin, across passes */
    private var matched = 0
    private var lastPost = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channel()
        // always first: a stop action can cold-start the service, and startForeground has a deadline
        startForeground(NOTIFICATION_ID, progressNotification(AppleMatchStatus.progress))
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            return START_NOT_STICKY
        }
        // something is already matching: report on that run rather than start a second
        if (job?.isActive == true) return START_NOT_STICKY

        job = scope.launch {
            try {
                match()
            } catch (e: CancellationException) {
                AppleMatchStatus.finish(getString(R.string.import_sync_apple_match_stopped, matched))
            } catch (e: Exception) {
                AppleMatchStatus.fail(getString(R.string.import_sync_apple_match_failed, e.message ?: ""))
            } finally {
                // one last look at the status, so the finish lands in the notification, then let the
                // finished one stay behind for the person to read
                post(doneNotification(AppleMatchStatus.progress))
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private suspend fun match() {
        val engine = AppleYoutubeMatch(dao)
        val gap = engine.remaining()
        if (gap == 0) {
            AppleMatchStatus.finish(getString(R.string.import_sync_apple_match_all_done))
            return
        }
        matched = 0
        lastPost = 0
        AppleMatchStatus.start(gap)
        post(progressNotification(AppleMatchStatus.progress))

        var lastPassMatched = 0
        var lastPassFailed = 0
        while (currentCoroutineContext().isActive) {
            val alreadyMatched = matched
            val pass = engine.run(PASS) { _, inPass ->
                AppleMatchStatus.advance(alreadyMatched + inPass)
                postProgressAtMostEvery(POST_EVERY_MS)
            }
            matched += pass.matched
            lastPassMatched = pass.matched
            lastPassFailed = pass.failed
            AppleMatchStatus.advance(matched)
            post(progressNotification(AppleMatchStatus.progress))

            // A pass that tried nothing has nothing left. One that matched nothing found only songs
            // YouTube does not seem to have: the songs it failed stay at the head of the next pass,
            // so running again would ask the same questions forever.
            if (pass.tried == 0 || pass.matched == 0) break
        }

        val left = engine.remaining()
        when {
            // every search in the last pass threw: that is a dead connection, not an empty catalogue
            lastPassMatched == 0 && lastPassFailed > 0 ->
                AppleMatchStatus.fail(getString(R.string.import_sync_apple_match_unreachable, matched, left))
            left == 0 -> AppleMatchStatus.finish(getString(R.string.import_sync_apple_match_all_done))
            else -> AppleMatchStatus.finish(getString(R.string.import_sync_apple_match_left, matched, left))
        }
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.import_sync_apple_match_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.import_sync_apple_match_channel_desc)
                setShowBadge(false)
            },
        )
    }

    private fun openApp(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setClassName(packageName, "$packageName.MainActivity")
        return PendingIntent.getActivity(
            this,
            0,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, AppleMatchService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(p: AppleMatchStatus.Progress): Notification {
        val counter = if (p.total > 0) {
            getString(R.string.import_sync_apple_match_progress, p.matched, p.total)
        } else {
            getString(R.string.import_sync_apple_match_title)
        }
        val fraction = p.fraction
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.import_sync_apple_match_notification_title))
            .setContentText(counter)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.import_sync_apple_match_stop), stopIntent())
            .apply {
                if (fraction == null) {
                    setProgress(0, 0, true)
                } else {
                    setProgress(1000, (fraction * 1000).toInt(), false)
                    setSubText("${(fraction * 100).toInt()}%")
                }
            }
            .setContentIntent(openApp())
            .build()
    }

    /** The finish line: the same sentence the settings row shows, kept until it is tapped away. */
    private fun doneNotification(p: AppleMatchStatus.Progress): Notification {
        val message = p.finishedMessage ?: getString(R.string.import_sync_apple_match_title)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.import_sync_apple_match_finished))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
    }

    private fun postProgressAtMostEvery(everyMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastPost < everyMs) return
        lastPost = now
        post(progressNotification(AppleMatchStatus.progress))
    }

    private fun post(notification: Notification) {
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }
}
