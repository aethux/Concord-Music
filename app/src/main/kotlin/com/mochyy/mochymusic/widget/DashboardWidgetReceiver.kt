// Concord: new - the dashboard 4x2 widget: artwork, title and artist, playback under them, all on a see-through card
package com.mochyy.mochymusic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.mochyy.mochymusic.playback.MusicService

open class DashboardWidgetReceiver : AppWidgetProvider() {

    // the wide (1x4) shape is the same widget with a different layout, so it only changes this
    protected open val form = DashboardWidgetLook.DashboardForm.Card

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (MusicService.isRunning) {
            askServiceToRedraw(context)
        } else {
            // the app is not playing anything, so the widget draws its own resting card
            appWidgetIds.forEach { DashboardWidgetLook.drawIdle(context, appWidgetManager, it, form) }
        }
    }

    // the card's surface and artwork are made for the size it has, so a resize means a redraw
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        if (MusicService.isRunning) {
            askServiceToRedraw(context)
        } else {
            DashboardWidgetLook.drawIdle(context, appWidgetManager, appWidgetId, form)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { DashboardWidgetLook.forget(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            MusicWidgetReceiver.ACTION_PLAY_PAUSE,
            MusicWidgetReceiver.ACTION_NEXT,
            MusicWidgetReceiver.ACTION_PREVIOUS,
            MusicWidgetReceiver.ACTION_LIKE -> {
                val serviceIntent = Intent(context, MusicService::class.java).apply {
                    action = intent.action
                    putExtras(intent)
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {

                }
            }
        }
    }

    companion object {
        // the player redraws every placed widget when it is told to, this one included
        fun askServiceToRedraw(context: Context) {
            val intent = Intent(context, MusicService::class.java).apply {
                action = MusicWidgetReceiver.ACTION_UPDATE_WIDGET
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {

            }
        }
    }
}

// the same widget in one row: artwork, song and album, then the four buttons on the right
class DashboardWideWidgetReceiver : DashboardWidgetReceiver() {
    override val form = DashboardWidgetLook.DashboardForm.Wide
}
