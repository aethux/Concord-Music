// a section of the modules page: each module's card on a fixed slot, held to bring out the lines that drag it into order, and swiped left to delete

package com.mochyy.mochymusic.ui.screens.settings.modules

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.modules.ModuleEntry
import com.mochyy.mochymusic.ui.component.SwipeAction
import com.mochyy.mochymusic.ui.component.SwipeActionsBox
import com.mochyy.mochymusic.ui.component.SwipeRemoveColor
import com.mochyy.mochymusic.ui.screens.audiolab.LabColors
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import kotlin.math.roundToInt
import com.mochyy.mochymusic.ui.component.ReorderCards

private val RowGap = 12.dp
private val HandleWidth = 44.dp

@Composable
internal fun ModuleList(
    entries: List<ModuleEntry>,
    onOpen: (ModuleEntry) -> Unit,
    onRemove: (ModuleEntry) -> Unit,
    onReorder: (List<String>) -> Unit,
    // the module being updated right now, and what a tap on a row's update pill does
    updating: String? = null,
    onUpdate: (ModuleEntry) -> Unit = {},
    // the lines only show while sorting; holding a card turns sorting on and picks that card up
    sorting: Boolean = false,
    onStartSorting: () -> Unit = {},
) {
    val byId = entries.associateBy { it.id }
    ReorderCards(
        ids = entries.map { it.id },
        onReorder = onReorder,
        rowHeight = 104.dp,
        gap = RowGap,
        sorting = sorting,
        onStartSorting = onStartSorting,
    ) { id, lifted, hold ->
        val entry = byId[id] ?: return@ReorderCards
        val removeLabel = stringResource(R.string.module_remove_short)
        SwipeActionsBox(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            end = if (entry.removable) {
                { SwipeAction(R.drawable.delete, SwipeRemoveColor, removeLabel) { onRemove(entry) } }
            } else null,
            enabled = !lifted,
            slab = false,
        ) {
            ModuleCard(
                entry, lifted = lifted, updating = updating == id, onUpdate = { onUpdate(entry) }, onClick = { onOpen(entry) },
                hold = hold,
            )
        }
    }
}

/** one module as the mock draws it: its icon, its name, how it's doing, and what it adds with its version in the corner */
@Composable
private fun ModuleCard(entry: ModuleEntry, lifted: Boolean, updating: Boolean, onUpdate: () -> Unit, onClick: () -> Unit, hold: Modifier) {
    val info = entry.module.info
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(RoundedCornerShape(26.dp))
            .background(if (lifted) Color(0xFF222225) else ModuleRowColor)
            .clickable(onClick = onClick)
            // after the click, so a hold that lets go without moving eats the release instead of opening the module
            .then(hold)
            .padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
    ) {
        ModuleLogo(entry.id, info.name, size = 56, art = entry.icon, corner = 0.24f)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        info.name, fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 21.sp,
                        letterSpacing = (-0.6).sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(statusColor(entry), size = 7.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            moduleStatus(entry), fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (entry.update != null) UpdatePill(updating, onUpdate)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    featureSummary(entry.module.adds), fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Box(Modifier.padding(horizontal = 6.dp).size(width = 1.dp, height = 7.dp).background(LabColors.Secondary))
                Text(
                    stringResource(R.string.module_version, info.version), fontFamily = InterFontFamily, fontWeight = FontWeight.Bold,
                    fontSize = 12.sp, color = Color.White, maxLines = 1,
                )
            }
        }
    }
}

// a newer copy is out: one tap brings it in right from the list
@Composable
private fun UpdatePill(updating: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White)
            .clickable(enabled = !updating, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        if (updating) {
            androidx.compose.material3.CircularProgressIndicator(color = Color.Black, strokeWidth = 1.5.dp, modifier = Modifier.size(11.dp))
        } else {
            androidx.compose.material3.Icon(
                androidx.compose.ui.res.painterResource(R.drawable.download), null, tint = Color.Black, modifier = Modifier.size(12.dp),
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(
            stringResource(if (updating) R.string.module_updating else R.string.module_update),
            fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black, maxLines = 1,
        )
    }
}

