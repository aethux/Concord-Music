// Debug only — not in any release build.
//
// `adb shell am broadcast -n com.concord.player.debug/com.mochyy.mochymusic.applesync.SyncProbeReceiver`
// starts a full Apple sync without the UI. Verifying the pull on a phone otherwise means opening the
// Library screen first, and on this library that screen's images alone fill the debug heap before the
// sync has written anything.
package com.mochyy.mochymusic.applesync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SyncProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppleSyncService.start(context, light = intent.getBooleanExtra("light", false))
    }
}
