// Concord: new. What the YouTube matching run is doing, in one place.
//
// The job runs in a foreground service, so the screen that started it is usually gone by the time it
// finishes. The row on Import & sync and the notification both read this object, so "how far along
// is it" has one answer while the app is open; the notification carries the same numbers when it is
// not. In memory on purpose: a report that survived a restart would describe work that is no longer
// running.
package com.mochyy.mochymusic.applesync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object AppleMatchStatus {

    data class Progress(
        val running: Boolean = false,
        /** songs that now have a twin this run; [total] is the gap it started with */
        val matched: Int = 0,
        val total: Int = 0,
        /** the sentence the row and the notification finish with, already worded */
        val finishedMessage: String? = null,
        val failed: Boolean = false,
    ) {
        /** 0f..1f for a determinate bar; null while there is no gap to close. */
        val fraction: Float?
            get() = if (total <= 0) null else (matched.toFloat() / total).coerceIn(0f, 1f)
    }

    var progress by mutableStateOf(Progress())
        private set

    fun start(total: Int) {
        progress = Progress(running = true, total = total)
    }

    fun advance(matched: Int) {
        if (progress.running) progress = progress.copy(matched = matched)
    }

    fun finish(message: String) {
        progress = progress.copy(running = false, finishedMessage = message, failed = false)
    }

    fun fail(message: String) {
        progress = progress.copy(running = false, finishedMessage = message, failed = true)
    }
}
