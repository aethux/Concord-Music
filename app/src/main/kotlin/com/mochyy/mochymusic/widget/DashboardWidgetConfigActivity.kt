// Concord: new - the dashboard widget's settings: a live preview with the opacity and size sliders
package com.mochyy.mochymusic.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.screens.audiolab.LabPanel
import com.mochyy.mochymusic.ui.screens.audiolab.LabSlider
import com.mochyy.mochymusic.ui.theme.MochyTheme
import kotlin.math.roundToInt

// opened by the launcher when the dashboard is placed, and by the widget's own reconfigure, so it must be exported
class DashboardWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the launcher keeps whatever result is set when the screen closes with the back button
        setResult(RESULT_CANCELED)

        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val form = DashboardWidgetLook.formOf(this, widgetId)
        setContent {
            MochyTheme(darkTheme = true) {
                val context = LocalContext.current
                var opacity by remember { mutableIntStateOf(DashboardWidgetLook.opacity(context, widgetId)) }
                var scale by remember { mutableIntStateOf(DashboardWidgetLook.scale(context, widgetId)) }

                DashboardWidgetConfigScreen(
                    form = form,
                    opacity = opacity,
                    scale = scale,
                    onOpacity = { opacity = it },
                    onScale = { scale = it },
                    onCancel = { finish() },
                    onSave = {
                        DashboardWidgetLook.save(context, widgetId, opacity, scale)
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                        )
                        DashboardWidgetLook.refresh(context, widgetId, form)
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun DashboardWidgetConfigScreen(
    form: DashboardWidgetLook.DashboardForm,
    opacity: Int,
    scale: Int,
    onOpacity: (Int) -> Unit,
    onScale: (Int) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF8C9AB0), Color(0xFF3B4959))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Text(
                text = stringResource(
                    if (form == DashboardWidgetLook.DashboardForm.Wide) R.string.widget_dashboard_wide else R.string.widget_dashboard
                ),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(20.dp))
            DashboardPreview(form = form, opacity = opacity, scale = scale)
            Spacer(Modifier.height(24.dp))
            LabPanel {
                LabSlider(
                    label = stringResource(R.string.widget_dashboard_opacity),
                    valueText = "$opacity%",
                    value = opacity.toFloat(),
                    onValueChange = { onOpacity(it.roundToInt()) },
                    valueRange = DashboardWidgetLook.MinOpacity.toFloat()..DashboardWidgetLook.MaxOpacity.toFloat(),
                )
                Spacer(Modifier.height(8.dp))
                LabSlider(
                    label = stringResource(R.string.widget_dashboard_size),
                    valueText = "$scale%",
                    value = scale.toFloat(),
                    onValueChange = { onScale(it.roundToInt()) },
                    valueRange = DashboardWidgetLook.MinScale.toFloat()..DashboardWidgetLook.MaxScale.toFloat(),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel), color = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSave) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

// the same shape the widget draws, on a wallpaper-like backdrop so the opacity reads as it will at home
@Composable
private fun DashboardPreview(form: DashboardWidgetLook.DashboardForm, opacity: Int, scale: Int) {
    val scaleFactor = scale / 100f
    val card = Color(0xFF1E232D).copy(alpha = opacity / 100f)

    if (form == DashboardWidgetLook.DashboardForm.Wide) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(card)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewArt(size = (72.dp - 16.dp) * DashboardWidgetLook.artRatio(scale), corner = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                PreviewTitle(scaleFactor = scaleFactor, titleSp = 15f)
                PreviewSubtitle(scaleFactor = scaleFactor, subtitleSp = 12f)
            }
            PreviewButtons(iconSize = 15.dp, playSize = 20.dp, spread = false)
        }
    } else {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.9f)
                .clip(RoundedCornerShape(24.dp))
                .background(card)
        ) {
            val artSize = minOf(
                (maxHeight - 28.dp) * DashboardWidgetLook.artRatio(scale),
                maxWidth * 0.42f,
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PreviewArt(size = artSize, corner = 18.dp)
                Spacer(Modifier.width(14.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    PreviewTitle(scaleFactor = scaleFactor, titleSp = 19f)
                    PreviewSubtitle(scaleFactor = scaleFactor, subtitleSp = 13f)
                    Spacer(Modifier.weight(1f))
                    PreviewButtons(iconSize = 18.dp, playSize = 26.dp, spread = true)
                }
            }
        }
    }
}

@Composable
private fun PreviewArt(size: Dp, corner: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(Color(0xFF0A0A10), Color(0xFF2C2F4F))))
    )
}

@Composable
private fun PreviewTitle(scaleFactor: Float, titleSp: Float) {
    Text(
        text = stringResource(R.string.widget_np_preview_title),
        color = Color.White,
        fontSize = (titleSp * scaleFactor).sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PreviewSubtitle(scaleFactor: Float, subtitleSp: Float) {
    Text(
        text = stringResource(R.string.widget_dashboard_preview_subtitle),
        color = Color.White.copy(alpha = 0.7f),
        fontSize = (subtitleSp * scaleFactor).sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// heart, previous, play, next: the order the card draws them. Spread shares the column like the widget's cells.
@Composable
private fun PreviewButtons(iconSize: Dp, playSize: Dp, spread: Boolean) {
    Row(
        modifier = if (spread) Modifier.fillMaxWidth() else Modifier,
        horizontalArrangement = if (spread) Arrangement.SpaceEvenly else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_widget_heart_outline_nav),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
        if (!spread) Spacer(Modifier.width(6.dp))
        Icon(
            painter = painterResource(R.drawable.ic_np_prev),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
        if (!spread) Spacer(Modifier.width(6.dp))
        Icon(
            painter = painterResource(R.drawable.ic_np_play),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(playSize),
        )
        if (!spread) Spacer(Modifier.width(6.dp))
        Icon(
            painter = painterResource(R.drawable.ic_np_next),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}
