// Concord: the background-refresh picker on Import & sync — the same pick-one sheet the video
// quality row uses, so a choice here looks and dismisses like every other choice in settings.
package com.mochyy.mochymusic.ui.screens.settings.importsync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.AppleRefreshSchedule
import com.mochyy.mochymusic.ui.component.PopupSheetHandle
import com.mochyy.mochymusic.ui.component.PopupSheetState
import com.mochyy.mochymusic.ui.component.SheetLabel
import com.mochyy.mochymusic.ui.component.SheetOptionRow
import com.mochyy.mochymusic.ui.screens.settings.ChoiceMark
import com.mochyy.mochymusic.ui.utils.stableSystemBars

/** how an interval reads on the row and in the sheet's list */
@Composable
internal fun appleRefreshLabel(minutes: Int): String =
    when (minutes) {
        15 -> stringResource(R.string.import_sync_apple_refresh_15)
        60 -> stringResource(R.string.import_sync_apple_refresh_60)
        6 * 60 -> stringResource(R.string.import_sync_apple_refresh_6h)
        24 * 60 -> stringResource(R.string.import_sync_apple_refresh_24h)
        else -> stringResource(R.string.import_sync_apple_refresh_off)
    }

fun PopupSheetState.showAppleRefreshSheet(
    selected: Int,
    onPick: (Int) -> Unit,
) {
    var sheet: PopupSheetHandle? = null
    sheet =
        showWithHandle {
            // the list reaches the screen's bottom edge, so its last line clears the gesture bar
            val navBottom = WindowInsets.stableSystemBars.asPaddingValues().calculateBottomPadding()
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 12.dp + navBottom),
            ) {
                SheetLabel(stringResource(R.string.import_sync_apple_refresh_title), Modifier.padding(start = 4.dp, bottom = 4.dp))
                for (minutes in AppleRefreshSchedule.choices) {
                    SheetOptionRow(
                        title = appleRefreshLabel(minutes),
                        subtitle = if (minutes == 0) stringResource(R.string.import_sync_apple_refresh_off_desc) else null,
                        subtitleLines = 2,
                        onClick = {
                            onPick(minutes)
                            sheet?.dismiss()
                        },
                        leading = { ChoiceMark(minutes == selected) },
                    )
                }
                Text(
                    stringResource(R.string.import_sync_apple_refresh_note),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
                )
            }
        }
}
