// Concord: new — opening Apple's Replay from the card that names it.
package com.mochyy.mochymusic.ui.screens

import androidx.navigation.NavController
import com.mochyy.mochymusic.applesync.AppleReplaySource

/**
 * Replay, in one step on this account and in two only when Apple has more than one year.
 *
 * The card's own words say "month", but the summaries Apple serves are yearly: `period=month` is a
 * 400 ("Period 'month' cannot…"), and only `year` answers. With one year there is nothing to list, so
 * the card opens that year's Replay playlist directly; the year page is the answer only once Apple
 * keeps several.
 */
internal suspend fun openAppleReplay(navController: NavController, source: AppleReplaySource) {
    val years = source.replayYears()
    if (years.size > 1) {
        navController.navigate("apple_replay")
        return
    }
    val year = years.firstOrNull() ?: return
    source.replayPlaylistId(year)?.let { id -> navController.navigate("apple_playlist/$id") }
}
