// Concord: new. One settle for every card row in the app.
//
// A row of cards that snaps should land the same way on Home, an artist's page and Browse. This is
// the one place that decides how: an under-damped spring, so the row carries the fling's own speed
// and overshoots once at the landing. A tween of the same curve was tried on Home and rejected — a
// fixed duration crawls card by card however hard you throw it, which reads as jitter.

package com.mochyy.mochymusic.ui.utils

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private val CardSnapSpec = spring<Float>(
    dampingRatio = 0.8f,
    stiffness = Spring.StiffnessMediumLow,
)

/** The snap a card row is given, wherever it is drawn. Remember it against the provider it snaps to. */
@Composable
internal fun rememberCardSnapFling(provider: SnapLayoutInfoProvider): FlingBehavior =
    remember(provider) {
        snapFlingBehavior(
            snapLayoutInfoProvider = provider,
            decayAnimationSpec = exponentialDecay(),
            snapAnimationSpec = CardSnapSpec,
        )
    }
