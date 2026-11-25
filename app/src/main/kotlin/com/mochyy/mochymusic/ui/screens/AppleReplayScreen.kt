// Concord: new — Replay, opened from Home's Replay card.
//
// Mochy's page and Mochy's rows; Apple's Replay is a year, and a year is a playlist, so a row opens
// the playlist page the app already draws (`ApplePlaylistScreen`).
package com.mochyy.mochymusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.AppleReplayYear
import com.mochyy.mochymusic.importer.account.AccountService
import com.mochyy.mochymusic.ui.component.AppleSignInPrompt
import com.mochyy.mochymusic.ui.component.HomeContentInset
import com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState
import com.mochyy.mochymusic.ui.screens.library.LibraryListRow
import com.mochyy.mochymusic.ui.screens.library.showLibraryImportSheet
import com.mochyy.mochymusic.ui.utils.backToMain
import com.mochyy.mochymusic.viewmodels.AppleReplayViewModel
import kotlinx.coroutines.launch

@Composable
fun AppleReplayScreen(
    navController: NavController,
    viewModel: AppleReplayViewModel = hiltViewModel(),
) {
    val years by viewModel.years.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val signedOut by viewModel.signedOut.collectAsState()

    val sheets = LocalZoomOutOverlayState.current
    if (signedOut) {
        AppleSignInPrompt(onSignIn = { sheets.showLibraryImportSheet(AccountService.APPLE_MUSIC) })
        return
    }

    val scope = rememberCoroutineScope()
    val bottomInset = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val open: (AppleReplayYear) -> Unit = { year ->
        scope.launch {
            viewModel.playlistIdOf(year)?.let { id -> navController.navigate("apple_playlist/$id") }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            failed -> Notice(
                text = stringResource(R.string.apple_replay_empty),
                action = stringResource(R.string.home_apple_retry),
                onAction = { viewModel.retry() },
            )
            years == null -> Notice(text = stringResource(R.string.apple_shelf_loading))
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = HomeContentInset,
                    end = HomeContentInset,
                    top = 128.dp,
                    bottom = bottomInset + 24.dp,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "title") {
                    Text(
                        text = stringResource(R.string.apple_replay_title),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                items(years.orEmpty(), key = { it.year }) { year ->
                    LibraryListRow(
                        title = year.year.toString(),
                        subtitle = stringResource(R.string.apple_replay_row),
                        thumbnailUrl = null,
                        onClick = { open(year) },
                        onLongClick = { open(year) },
                    )
                }
            }
        }

        IconButton(
            onClick = { if (!navController.navigateUp()) navController.backToMain() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 48.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.arrow_back),
                contentDescription = stringResource(R.string.action_back),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun Notice(
    text: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (action != null && onAction != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 10.dp))
            Text(
                text = action,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAction),
                textAlign = TextAlign.Center,
            )
        }
    }
}
