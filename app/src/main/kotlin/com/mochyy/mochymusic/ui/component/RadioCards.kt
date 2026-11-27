// Concord: new — one radio card, drawn with Home's own cover card.
//
// Both the Radio page and Home's station rows draw these, so a station looks the same in either
// place: same cover, same two lines, same press. An album opens its cover from here exactly as Home's
// album cards do.
package com.mochyy.mochymusic.ui.component

import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.AppleRadioItem

@Composable
fun AppleRadioCard(
    item: AppleRadioItem,
    size: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    HomeCoverCard(
        title = item.title,
        subtitle = item.subtitle,
        thumbnailUrl = AppleCovers.sized(item.artworkUrl, (size.value * 2).toInt()),
        size = size,
        sharedElementKey = if (item.kind == AppleRadioItem.Kind.Album) "album-${item.id}" else null,
        isPlaying = { false },
        modifier = Modifier.combinedClickable(
            interactionSource = null,
            indication = HomeCardHighlight,
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    )
}
