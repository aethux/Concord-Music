// Concord: extracted from Mochy's module list (ui/screens/settings/modules/ModuleList.kt), so every
// ordered list of cards sorts the same way: held to pick a card up, the drag lines appear beside
// every row, released to drop it.
package com.mochyy.mochymusic.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val HandleWidth = 44.dp

/**
 * Cards on fixed slots, in the order [ids] gives: a card held (long press) turns sorting on and is
 * picked up, its neighbours slide out of the way, and letting go reports the new order.
 *
 * While [sorting] the drag lines show beside every row, so a card can be moved by grabbing them
 * rather than holding the card again. [onDragEnd] fires on the drop, for a caller that wants sorting
 * to end with the press rather than stay on.
 *
 * [row] draws one card and is handed the [hold] pointer modifier the pick-up listens on; it should
 * fill the width it is given, since the drag lines take the rest of the row when they show.
 */
@Composable
fun ReorderCards(
    ids: List<String>,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 104.dp,
    gap: Dp = 12.dp,
    sorting: Boolean = false,
    onStartSorting: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    row: @Composable RowScope.(id: String, lifted: Boolean, hold: Modifier) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    // rows sit on fixed slots so they can slide; a big system font makes the slots taller
    val slotHeight = rowHeight + 28.dp * (density.fontScale - 1f).coerceAtLeast(0f)
    val slotPx = with(density) { (slotHeight + gap).toPx() }
    var order by remember { mutableStateOf(ids) }
    var dragging by remember { mutableStateOf<String?>(null) }
    // a saved order, or a card leaving, mustn't yank the list while a row is held
    LaunchedEffect(ids) { if (dragging == null) order = ids }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val currentOrder by rememberUpdatedState(order)
    val currentOnReorder by rememberUpdatedState(onReorder)
    val currentOnStartSorting by rememberUpdatedState(onStartSorting)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    // read in layout and draw only, so the lines sliding in don't recompose every row each frame
    val reveal = animateFloatAsState(if (sorting) 1f else 0f, spring(dampingRatio = 1f, stiffness = 500f), label = "handles")
    val handlesShown by remember { derivedStateOf { reveal.value > 0f } }

    // a card that has left the list is drawn once more where it stood, rising out of the stack while
    // the rest slide up after it — a card being taken out reads as leaving instead of blinking off
    val leaving = remember { mutableStateMapOf<String, Leaving>() }
    val previous = remember { mutableStateOf(ids) }
    LaunchedEffect(ids) {
        val gone = previous.value.withIndex().filter { (_, id) -> id !in ids }
        previous.value = ids
        gone.forEach { (index, id) ->
            val entry = Leaving(Animatable(1f), Animatable(0f), index)
            leaving[id] = entry
            coroutineScope {
                launch { entry.fade.animateTo(0f, tween(170, easing = LinearOutSlowInEasing)) }
                launch { entry.rise.animateTo(-30f, tween(230, easing = FastOutLinearInEasing)) }
            }
            leaving.remove(id)
        }
    }

    fun startDrag(id: String) {
        dragging = id
        dragOffset = 0f
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun dragBy(id: String, amount: Float) {
        dragOffset += amount
        val list = currentOrder
        val from = list.indexOf(id)
        val to = (from + (dragOffset / slotPx).roundToInt()).coerceIn(0, list.size - 1)
        if (to != from) {
            order = list.toMutableList().apply { add(to, removeAt(from)) }
            dragOffset -= (to - from) * slotPx
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun endDrag() {
        dragging = null
        dragOffset = 0f
        currentOnReorder(currentOrder)
        currentOnDragEnd()
    }

    Box(modifier.fillMaxWidth().height(slotHeight * order.size + gap * (order.size - 1).coerceAtLeast(0))) {
        order.forEachIndexed { index, id ->
            key(id) {
                val isDragged = dragging == id
                // one position per row: follows the finger while held, springs to its slot after
                val y = remember { Animatable(index * slotPx) }
                val held = remember { floatArrayOf(index * slotPx) }
                LaunchedEffect(isDragged, index) {
                    if (!isDragged) {
                        y.snapTo(held[0])
                        y.animateTo(index * slotPx, spring(dampingRatio = 0.8f, stiffness = 500f))
                    }
                }
                val lift by animateFloatAsState(if (isDragged) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 600f), label = "lift")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .offset {
                            if (isDragged) held[0] = index * slotPx + dragOffset
                            IntOffset(0, (if (isDragged) held[0] else y.value).roundToInt())
                        }
                        .graphicsLayer {
                            scaleX = 1f + 0.025f * lift
                            scaleY = 1f + 0.025f * lift
                        }
                        .fillMaxWidth()
                        .height(slotHeight),
                ) {
                    row(id, isDragged, Modifier.pointerInput(id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                currentOnStartSorting()
                                startDrag(id)
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragBy(id, amount.y)
                            },
                            onDragEnd = { endDrag() },
                            onDragCancel = { endDrag() },
                        )
                    })
                    if (handlesShown) {
                        DragLines(
                            Modifier
                                .revealWidth(HandleWidth) { reveal.value }
                                .fillMaxHeight()
                                .pointerInput(Unit) { detectTapGestures {} }
                                .pointerInput(id, sorting) {
                                    // while the lines slide away they're only a picture, not something to grab
                                    if (!sorting) return@pointerInput
                                    detectDragGestures(
                                        onDragStart = { startDrag(id) },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragBy(id, amount.y)
                                        },
                                        onDragEnd = { endDrag() },
                                        onDragCancel = { endDrag() },
                                    )
                                },
                            visibility = { reveal.value },
                        )
                    }
                }
            }
        }

        // the cards that have just left, rising out of the gap they vacated
        val risePx = with(LocalDensity.current) { 1.dp.toPx() }
        leaving.forEach { (id, entry) ->
            key("leaving-$id") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .zIndex(2f)
                        .offset { IntOffset(0, (entry.index * slotPx + entry.rise.value * risePx).roundToInt()) }
                        .graphicsLayer {
                            alpha = entry.fade.value
                            transformOrigin = TransformOrigin(0.5f, 0f)
                            scaleY = 0.96f + 0.04f * entry.fade.value
                        }
                        .fillMaxWidth()
                        .height(slotHeight),
                ) {
                    row(id, false, Modifier)
                }
            }
        }
    }
}

// grows from nothing to its full width, measured at full width the whole time so the lines slide in instead of squashing
private fun Modifier.revealWidth(full: Dp, progress: () -> Float) = layout { measurable, constraints ->
    val fullPx = full.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = fullPx, maxWidth = fullPx))
    layout((fullPx * progress()).roundToInt(), placeable.height) { placeable.place(0, 0) }
}

// the mock's three lines beside each card, faded by how far they've come in
@Composable
private fun DragLines(modifier: Modifier, visibility: () -> Float) {
    Canvas(modifier) {
        val w = 22.dp.toPx()
        val x0 = size.width - w - 2.dp.toPx()
        val gap = 7.dp.toPx()
        val c = Color.White.copy(alpha = 0.9f * visibility())
        val stroke = 2.dp.toPx()
        val cy = size.height / 2
        listOf(-1f, 0f, 1f).forEach { line ->
            val yy = cy + line * gap
            drawLine(c, Offset(x0, yy), Offset(x0 + w, yy), stroke, StrokeCap.Round)
        }
    }
}

// where a card that has left the list is going: it keeps its old slot, rises and fades
private class Leaving(
    val fade: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    val rise: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    val index: Int,
)
