// Concord: new — a list position that survives leaving its page and coming back.
package com.mochyy.mochymusic.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * [rememberLazyListState] with the position kept when the page is left and returned to.
 *
 * Compose does save a list's position, and then throws it away the first time the list is measured
 * with anything in it: `LazyListScrollPosition.updateFromMeasureResult` overwrites the restored
 * index as soon as one item exists. Every page here paints a header, bones or an empty state before
 * its rows arrive - or while its layout is built off the main thread - so coming back from an album
 * landed at the top.
 *
 * [hasContent] says the real rows are on screen. The position is written down while the person
 * scrolls, and put back the first time the rows are there again.
 */
@Composable
fun rememberRestoredListState(hasContent: Boolean): LazyListState {
    val state = rememberLazyListState()
    var savedIndex by rememberSaveable { mutableIntStateOf(0) }
    var savedOffset by rememberSaveable { mutableIntStateOf(0) }

    // Only a scroll says where the person is: a page that has just painted its placeholder must not
    // record the top of the list over the position waiting to be restored.
    LaunchedEffect(state) {
        snapshotFlow {
            Triple(
                state.isScrollInProgress,
                state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset,
            )
        }.collect { (scrolling, index, offset) ->
            if (scrolling) {
                savedIndex = index
                savedOffset = offset
            }
        }
    }

    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(hasContent) {
        if (!hasContent || restored) return@LaunchedEffect
        restored = true
        if (savedIndex > 0 && state.firstVisibleItemIndex == 0) {
            state.scrollToItem(savedIndex, savedOffset)
        }
    }
    return state
}
