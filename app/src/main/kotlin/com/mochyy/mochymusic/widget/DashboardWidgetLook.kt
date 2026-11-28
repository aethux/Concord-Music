// Concord: new - the dashboard widget's stored look: how see-through the card is, how large its content is, and the drawing both the player and the config screen use
package com.mochyy.mochymusic.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.playback.MusicService

/**
 * The dashboard is an artwork, two lines of text and four buttons on a rounded, see-through surface,
 * in two shapes: the 4x2 card and the 1x4 row. Android widgets cannot be styled at runtime, so the two
 * things that change - the surface's opacity and the size of the content - are written into the
 * widget when it is drawn: the surface as a small bitmap with the alpha baked in, the text through
 * the widget's own text sizes.
 */
object DashboardWidgetLook {

    const val MinOpacity = 25
    const val MaxOpacity = 100
    const val DefaultOpacity = 88
    const val MinScale = 80
    const val MaxScale = 120
    const val DefaultScale = 100

    // the card's colour, and the corner radius of the card
    private const val SurfaceRed = 0x1E
    private const val SurfaceGreen = 0x23
    private const val SurfaceBlue = 0x2D
    private const val SurfaceCornerDp = 24f

    private const val BaseArtRatio = 0.98f
    private const val FallbackWidthDp = 320f

    private const val PrefsName = "dashboard_widget"

    // the same surface is redrawn on every song change, so it is made once per size and opacity
    private val surfaces = object : LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 6
    }

    /** The two shapes share every view id, so one drawing writes both. */
    enum class DashboardForm(
        val layoutRes: Int,
        val titleSp: Float,
        val subtitleSp: Float,
        val paddingDp: Float,
        val fallbackHeightDp: Int,
    ) {
        Card(R.layout.widget_dashboard, 19f, 13f, 28f, 110),
        Wide(R.layout.widget_dashboard_wide, 15f, 12f, 16f, 56),
    }

    fun opacity(context: Context, widgetId: Int): Int =
        prefs(context).getInt("opacity_$widgetId", DefaultOpacity).coerceIn(MinOpacity, MaxOpacity)

    fun scale(context: Context, widgetId: Int): Int =
        prefs(context).getInt("scale_$widgetId", DefaultScale).coerceIn(MinScale, MaxScale)

    fun save(context: Context, widgetId: Int, opacity: Int, scale: Int) {
        prefs(context).edit()
            .putInt("opacity_$widgetId", opacity.coerceIn(MinOpacity, MaxOpacity))
            .putInt("scale_$widgetId", scale.coerceIn(MinScale, MaxScale))
            .apply()
    }

    // the launcher drops a widget's settings with the widget, so nothing is left behind
    fun forget(context: Context, widgetId: Int) {
        prefs(context).edit().remove("opacity_$widgetId").remove("scale_$widgetId").apply()
    }

    // how much of the card's inner height the artwork takes; the size slider moves it, never past the card
    fun artRatio(scale: Int): Float =
        (BaseArtRatio + (scale / 100f - 1f) * 0.8f).coerceIn(0.5f, 1f)

    // which shape a placed widget is, asked of the launcher rather than remembered
    fun formOf(context: Context, widgetId: Int): DashboardForm {
        val provider = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)?.provider?.className
        return if (provider == DashboardWideWidgetReceiver::class.java.name) DashboardForm.Wide else DashboardForm.Card
    }

    /** Writes the stored opacity and size into a card that is already filled with its song. */
    fun bind(context: Context, views: RemoteViews, widgetId: Int, options: Bundle, form: DashboardForm) {
        val scale = scale(context, widgetId) / 100f
        val density = context.resources.displayMetrics.density

        views.setTextViewTextSize(
            R.id.widget_dashboard_title,
            TypedValue.COMPLEX_UNIT_SP,
            form.titleSp * scale,
        )
        views.setTextViewTextSize(
            R.id.widget_dashboard_subtitle,
            TypedValue.COMPLEX_UNIT_SP,
            form.subtitleSp * scale,
        )

        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: FallbackWidthDp.toInt()
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0).takeIf { it > 0 } ?: form.fallbackHeightDp
        val surface = surface(context, widgetId, (widthDp * density).toInt(), (heightDp * density).toInt())
        views.setImageViewBitmap(R.id.widget_dashboard_surface, surface)

        // the artwork is a square; it never takes more than a share of the width, so the song and the
        // buttons always have room beside it
        val artDp = minOf(
            (heightDp - form.paddingDp) * artRatio(scale(context, widgetId)),
            widthDp * 0.42f,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setViewLayoutWidth(R.id.widget_dashboard_art, artDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(R.id.widget_dashboard_art, artDp, TypedValue.COMPLEX_UNIT_DIP)
        }
    }

    /** The card with nothing playing, drawn by the widget itself when the app is not running. */
    fun drawIdle(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int, form: DashboardForm) {
        val views = RemoteViews(context.packageName, form.layoutRes)
        views.setTextViewText(R.id.widget_dashboard_title, context.getString(R.string.not_playing))
        views.setTextViewText(R.id.widget_dashboard_subtitle, context.getString(R.string.tap_to_open))
        views.setImageViewResource(R.id.widget_dashboard_like, R.drawable.ic_widget_heart_outline_nav)
        bind(context, views, widgetId, appWidgetManager.getAppWidgetOptions(widgetId), form)
        bindIntents(context, views)
        appWidgetManager.updateAppWidget(widgetId, views)
    }

    /** After the config screen saves, the card is redrawn now rather than at the next song change. */
    fun refresh(context: Context, widgetId: Int, form: DashboardForm) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        if (MusicService.isRunning) {
            DashboardWidgetReceiver.askServiceToRedraw(context)
        } else {
            drawIdle(context, appWidgetManager, widgetId, form)
        }
    }

    /** The buttons and the card's own tap, shared by the idle card and the drawn one. */
    fun bindIntents(context: Context, views: RemoteViews) {
        val receiver = DashboardWidgetReceiver::class.java
        views.setOnClickPendingIntent(android.R.id.background, NowPlayingWidgetIntents.openApp(context))
        views.setOnClickPendingIntent(R.id.widget_dashboard_art, NowPlayingWidgetIntents.openApp(context))
        views.setOnClickPendingIntent(R.id.widget_dashboard_prev, NowPlayingWidgetIntents.broadcast(context, receiver, MusicWidgetReceiver.ACTION_PREVIOUS, 0))
        views.setOnClickPendingIntent(R.id.widget_dashboard_play, NowPlayingWidgetIntents.broadcast(context, receiver, MusicWidgetReceiver.ACTION_PLAY_PAUSE, 1))
        views.setOnClickPendingIntent(R.id.widget_dashboard_next, NowPlayingWidgetIntents.broadcast(context, receiver, MusicWidgetReceiver.ACTION_NEXT, 2))
        views.setOnClickPendingIntent(R.id.widget_dashboard_like, NowPlayingWidgetIntents.broadcast(context, receiver, MusicWidgetReceiver.ACTION_LIKE, 3))
    }

    private fun surface(context: Context, widgetId: Int, widthPx: Int, heightPx: Int): Bitmap {
        val alpha = (opacity(context, widgetId) * 255 / 100)
        val key = "${widthPx}x$heightPx:$alpha"
        surfaces[key]?.let { return it }

        val density = context.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alpha, SurfaceRed, SurfaceGreen, SurfaceBlue)
        }
        val radius = SurfaceCornerDp * density
        canvas.drawRoundRect(RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), radius, radius, paint)
        surfaces[key] = bitmap
        return bitmap
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
}
