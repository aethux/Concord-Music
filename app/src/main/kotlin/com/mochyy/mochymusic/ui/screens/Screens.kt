// Screens.kt

package com.mochyy.mochymusic.ui.screens

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.mochyy.mochymusic.R

@Immutable
sealed class Screens(
    @StringRes val titleId: Int,
    val iconIdInactive: Int,
    val iconIdActive: Int,
    val route: String,
) {
    object Home : Screens(
        titleId = R.string.home,
        iconIdInactive = R.raw.home,
        iconIdActive = R.raw.home_filled,
        route = "home"
    )

    /**
     * Apple's "New", where Apple puts its editorial page and the charts inside it. The lit state is
     * the outline grid's own squares filled, so the tab brightens the way every other tab does.
     */
    object Browse : Screens(
        titleId = R.string.browse_new,
        iconIdInactive = R.drawable.grid_view,
        iconIdActive = R.drawable.grid_view_filled,
        route = "apple_browse"
    )

    object Search : Screens(
        titleId = R.string.search,
        iconIdInactive = R.raw.search,
        iconIdActive = R.raw.search_filled,
        route = "search_input"
    )

    object Library : Screens(
        titleId = R.string.your_library,
        iconIdInactive = R.raw.lib,
        iconIdActive = R.raw.lib_fill,
        route = "library"
    )

    object Settings : Screens(
        titleId = R.string.settings,
        iconIdInactive = R.drawable.nav_mochy_outline,
        iconIdActive = R.drawable.nav_mochy_filled,
        route = "settings"
    )

    companion object {
        val MainScreens = listOf(Home, Browse, Search, Library, Settings)
    }
}

enum class NavigationTab {
    HOME, SEARCH
}

enum class DarkMode {
    AUTO, ON, OFF
}

enum class LyricsPosition {
    LEFT, CENTER, RIGHT
}

enum class OptionStats {
    WEEKS, MONTHS, YEARS, CONTINUOUS
}
