// Concord: new — Apple's Radio page, in Mochy's clothes.
//
// The content is the section tree Cider's radio page reads (`AppleRadioReader`); the drawing is
// Home's own — `HomeSectionTitle`, a card row, `HomeCoverCard` — so the page looks like this app and
// not like another client. Tapping a row's title opens that section alone, laid out as a grid.
package com.mochyy.mochymusic.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.AppleRadioItem
import com.mochyy.mochymusic.applesync.AppleRadioSection
import com.mochyy.mochymusic.importer.account.AccountService
import com.mochyy.mochymusic.ui.component.AppleSignInPrompt
import com.mochyy.mochymusic.ui.component.AppleRadioCard
import com.mochyy.mochymusic.ui.component.BonesHost
import com.mochyy.mochymusic.ui.component.HomeCardHighlight
import com.mochyy.mochymusic.ui.component.HomeCardSpacing
import com.mochyy.mochymusic.ui.component.HomeContentInset
import com.mochyy.mochymusic.ui.component.HomeCoverCard
import com.mochyy.mochymusic.ui.component.IconButton as MochyIconButton
import com.mochyy.mochymusic.ui.component.HomeSectionBones
import com.mochyy.mochymusic.ui.component.HomeSectionTitle
import com.mochyy.mochymusic.ui.component.HomeViewAllCard
import com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState
import com.mochyy.mochymusic.ui.component.LocalMenuState
import com.mochyy.mochymusic.ui.component.RevealSeenState
import com.mochyy.mochymusic.ui.component.homeCardSize
import com.mochyy.mochymusic.ui.component.rememberRevealSeenState
import com.mochyy.mochymusic.ui.menu.RadioItemMenu
import com.mochyy.mochymusic.ui.screens.library.showLibraryImportSheet
import com.mochyy.mochymusic.ui.utils.backToMain
import com.mochyy.mochymusic.viewmodels.AppleRadioViewModel
import kotlinx.coroutines.launch

@Composable
fun RadioScreen(
    navController: NavController,
    viewModel: AppleRadioViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val sections by viewModel.sections.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val signedOut by viewModel.signedOut.collectAsState()
    val favorites by viewModel.favorites.collectAsState()

    // signing in is Mochy's own import sheet, opened on Apple's sign-in
    val sheets = LocalZoomOutOverlayState.current
    if (signedOut) {
        AppleSignInPrompt(onSignIn = { sheets.showLibraryImportSheet(AccountService.APPLE_MUSIC) })
        return
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val bottomInset = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val cardSize = homeCardSize()
    val revealSeen = rememberRevealSeenState()
    val open: (AppleRadioItem) -> Unit = { item ->
        scope.launch {
            openAppleRadioItem(
                item = item,
                navController = navController,
                context = context,
                playerConnection = playerConnection,
                stationQueue = { id -> viewModel.stationQueue(id) },
            )
        }
    }
    val longPress = radioItemMenu(
        viewModel = viewModel,
        open = open,
        favorites = favorites,
        onViewArtist = { artistId -> navController.navigate("apple_artist/$artistId") },
        onStartRadio = { artistId ->
            scope.launch { viewModel.artistRadio(artistId)?.let { playerConnection.playQueue(it) } }
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        RadioTopBar(title = stringResource(R.string.radio), navController = navController)
        when {
            failed || sections?.isEmpty() == true -> Box(Modifier.weight(1f)) {
                ShelfNotice(
                    text = stringResource(R.string.apple_shelf_empty),
                    action = stringResource(R.string.home_apple_retry),
                    onAction = { viewModel.retry() },
                )
            }
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 10.dp, bottom = bottomInset + 24.dp),
            ) {
                val loaded = sections
                if (loaded == null) {
                    // Home's own bones, in the same two-row footprint, while Apple's tree arrives
                    item(key = "radio_bones", contentType = "bones") {
                        BonesHost {
                            repeat(2) {
                                HomeSectionBones()
                                Spacer(Modifier.height(34.dp))
                            }
                        }
                    }
                } else {
                    // the stations this phone keeps, above Apple's own order
                    if (favorites.isNotEmpty()) {
                        item(key = "favorites_top", contentType = "gap") {
                            Spacer(Modifier.height(14.dp))
                        }
                        item(key = "favorites_title", contentType = "title") {
                            HomeSectionTitle(title = stringResource(R.string.radio_favourites))
                        }
                        item(key = "favorites_row", contentType = "cardRow") {
                            HomeCardRow(
                                rowKey = "radio_favorites",
                                items = favorites.mapIndexed { index, item -> index to item },
                                revealSeen = revealSeen,
                                key = { (index, item) -> "favorite_${item.id}_$index" },
                            ) { (_, item) ->
                                AppleRadioCard(
                                    item = item,
                                    size = cardSize,
                                    onClick = { open(item) },
                                    onLongClick = { longPress(item) },
                                )
                            }
                        }
                        item(key = "favorites_gap", contentType = "gap") {
                            Spacer(Modifier.height(26.dp))
                        }
                    }
                    loaded.forEach { section ->
                        sectionRows(section, cardSize, revealSeen, navController, open, longPress)
                    }
                }
            }
        }
    }
}

/** one section: its title (the row's tap target opens the section alone) and Home's own card row */
private fun LazyListScope.sectionRows(
    section: AppleRadioSection,
    cardSize: Dp,
    revealSeen: RevealSeenState,
    navController: NavController,
    open: (AppleRadioItem) -> Unit,
    longPress: (AppleRadioItem) -> Unit,
) {
    val titled = section.title.isNotBlank()
    val openSection: () -> Unit = {
        navController.navigate("radio_section/${section.id}?title=${Uri.encode(section.title)}")
    }
    if (titled) {
        item(key = "title_${section.id}", contentType = "title") {
            HomeSectionTitle(title = section.title, onClick = openSection)
        }
    }
    item(key = "row_${section.id}", contentType = "cardRow") {
        // Home's row itself: the same snap, sliver of the previous card and reveal animation
        HomeCardRow(
            rowKey = "radio_${section.id}",
            items = section.items.mapIndexed { index, item -> index to item },
            revealSeen = revealSeen,
            key = { (index, item) -> "${item.kind}_${item.id}_$index" },
            trailing = if (titled) {
                { HomeViewAllCard(size = cardSize, onClick = openSection) }
            } else {
                null
            },
        ) { (_, item) ->
            AppleRadioCard(
                item = item,
                size = cardSize,
                onClick = { open(item) },
                onLongClick = { longPress(item) },
            )
        }
    }
    item(key = "gap_${section.id}", contentType = "gap") {
        Spacer(Modifier.height(26.dp))
    }
}

@Composable
fun RadioSectionScreen(
    navController: NavController,
    sectionId: String,
    title: String,
    viewModel: AppleRadioViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val sections by viewModel.sections.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val signedOut by viewModel.signedOut.collectAsState()
    val favorites by viewModel.favorites.collectAsState()

    val sheets = LocalZoomOutOverlayState.current
    if (signedOut) {
        AppleSignInPrompt(onSignIn = { sheets.showLibraryImportSheet(AccountService.APPLE_MUSIC) })
        return
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val bottomInset = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val cardSize = (screenWidth - HomeContentInset * 2 - HomeCardSpacing) / 2
    val section = remember(sections, sectionId) { sections?.firstOrNull { it.id == sectionId } }
    val open: (AppleRadioItem) -> Unit = { item ->
        scope.launch {
            openAppleRadioItem(
                item = item,
                navController = navController,
                context = context,
                playerConnection = playerConnection,
                stationQueue = { id -> viewModel.stationQueue(id) },
            )
        }
    }
    val longPress = radioItemMenu(
        viewModel = viewModel,
        open = open,
        favorites = favorites,
        onViewArtist = { artistId -> navController.navigate("apple_artist/$artistId") },
        onStartRadio = { artistId ->
            scope.launch { viewModel.artistRadio(artistId)?.let { playerConnection.playQueue(it) } }
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        RadioTopBar(
            // the title the row had first, so the header is right before the read lands
            title = title.ifBlank { section?.title ?: stringResource(R.string.radio) },
            navController = navController,
        )
        val shown = section
        when {
            failed -> Box(Modifier.weight(1f)) {
                ShelfNotice(
                    text = stringResource(R.string.apple_shelf_empty),
                    action = stringResource(R.string.home_apple_retry),
                    onAction = { viewModel.retry() },
                )
            }
            sections == null -> Box(Modifier.weight(1f)) {
                ShelfNotice(text = stringResource(R.string.apple_shelf_loading))
            }
            shown == null -> Box(Modifier.weight(1f)) {
                ShelfNotice(text = stringResource(R.string.apple_shelf_empty))
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = HomeContentInset,
                    end = HomeContentInset,
                    top = 10.dp,
                    bottom = bottomInset + 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(HomeCardSpacing),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(
                    count = shown.items.size,
                    key = { index -> "${shown.items[index].kind}_${shown.items[index].id}_$index" },
                ) { index ->
                    val item = shown.items[index]
                    AppleRadioCard(
                        item = item,
                        size = cardSize,
                        onClick = { open(item) },
                        onLongClick = { longPress(item) },
                    )
                }
            }
        }
    }
}

/**
 * Mochy's pushed-page bar: the back arrow (long press goes Home) and the page's title. The Radio page
 * and a section's page both wear it, so opening either looks like opening any other page in the app.
 */
@Composable
private fun RadioTopBar(title: String, navController: NavController) {
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            MochyIconButton(
                onClick = { if (!navController.navigateUp()) navController.backToMain() },
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back_ios),
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
    )
}

/**
 * Every radio card's long press, whatever it is: the menu follows the card's kind — play or open,
 * then the favourite it can have (a station's here, an album's in the account), then Share.
 */
@Composable
private fun radioItemMenu(
    viewModel: AppleRadioViewModel,
    open: (AppleRadioItem) -> Unit,
    favorites: List<AppleRadioItem>,
    onViewArtist: (String) -> Unit,
    onStartRadio: (String) -> Unit,
): (AppleRadioItem) -> Unit {
    val haptic = LocalHapticFeedback.current
    val menuState = LocalMenuState.current
    val kept = favorites.mapTo(HashSet()) { it.id }
    return { item ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        menuState.show {
            RadioItemMenu(
                item = item,
                favorited = item.id in kept,
                onPrimary = { open(item) },
                onToggleStationFavorite = { viewModel.toggleFavorite(item) },
                onViewArtist = onViewArtist,
                onStartRadio = onStartRadio,
                readFavorite = viewModel::favorite,
                onDismiss = menuState::dismiss,
            )
        }
    }
}
