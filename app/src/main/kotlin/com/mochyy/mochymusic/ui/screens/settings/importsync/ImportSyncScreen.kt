package com.mochyy.mochymusic.ui.screens.settings.importsync

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.AppleMatchStatus
import com.mochyy.mochymusic.viewmodels.AppleReadState
import com.mochyy.mochymusic.ui.component.DefaultDialog
import com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState
import com.mochyy.mochymusic.ui.component.AppSwitch
import com.mochyy.mochymusic.ui.component.SettingsGroup
import com.mochyy.mochymusic.ui.component.SettingsGroupStyle
import com.mochyy.mochymusic.ui.component.SettingsItem
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.ui.screens.devices.rememberDevicesLink
import com.mochyy.mochymusic.ui.screens.devices.showDeviceIntegration
import com.mochyy.mochymusic.viewmodels.ImportSyncViewModel
import java.time.LocalDate

/** experimental hub: import from other services, sync a youtube account, tunemymusic csv, and backup */
@Composable
fun ImportSyncScreen(
    navController: NavController,
    viewModel: ImportSyncViewModel = hiltViewModel(),
) {
    val progress by viewModel.importProgress.collectAsState()
    val exportMessage by viewModel.exportMessage.collectAsState()
    // Concord: the Apple library read's own state, and the animated-covers switch
    val appleRead by viewModel.appleRead.collectAsState()
    val appleAnimated by viewModel.appleAnimatedCovers.collectAsState()
    // Concord: the matching run lives in a service, so its state is read from the object both it and
    // this row write to, not from the view model that started it
    val appleMatch = AppleMatchStatus.progress
    val signedIn = rememberYouTubeSignedIn()
    val sheets = LocalZoomOutOverlayState.current
    val devices = rememberDevicesLink()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.export(uri)
    }

    SubSettingsScaffold(
        title = stringResource(R.string.import_sync_title),
        onBack = { navController.navigateUp() },
    ) {
        if (progress.isRunning || progress.isDone) {
            val resync = progress.resync
            ImportInfoCard {
                Text(
                    text = when {
                        resync != null && progress.isRunning -> stringResource(R.string.import_sync_progress_resyncing, resync.playlistName)
                        progress.isRunning -> stringResource(
                                    R.string.import_sync_progress_importing_count,
                                    progress.processedTracks,
                                    progress.totalTracks,
                                )
                        progress.cancelled -> stringResource(R.string.import_sync_progress_stopped)
                        resync != null && resync.failed.size < resync.playlists -> {
                            val countText = if (resync.added == 1) {
                                stringResource(R.string.import_sync_progress_new_songs_single)
                            } else {
                                stringResource(R.string.import_sync_progress_new_songs_multiple, resync.added)
                            }
                            if (resync.playlists == 1) {
                                stringResource(R.string.import_sync_progress_resynced_single, resync.playlistName, countText)
                            } else {
                                stringResource(R.string.import_sync_progress_resynced_multiple, resync.playlists, countText)
                            }
                        }
                        progress.error != null -> stringResource(R.string.import_sync_progress_failed)
                        else -> stringResource(R.string.import_sync_progress_finished_count, progress.matchedTracks, progress.totalTracks)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (progress.isRunning && progress.totalTracks > 0) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progress.processedTracks.toFloat() / progress.totalTracks },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingsGroup(
                style = SettingsGroupStyle.Grouped,
                items = listOf(
                    SettingsItem(
                        title = { Text(if (progress.isRunning) "See progress" else "See results") },
                        icon = painterResource(R.drawable.playlist_play),
                        iconShape = CircleShape,
                        onClick = { navController.navigate(ImportProgressRoute) { launchSingleTop = true } },
                    )
                ),
            )
            Spacer(Modifier.height(16.dp))
        }

        // Concord: the matching run's own progress card, so a run started here, or left running while
        // away, is visible the moment the screen is back. Same card as an import's progress.
        if (appleMatch.running) {
            ImportInfoCard {
                Text(
                    text = stringResource(R.string.import_sync_apple_match_notification_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(10.dp))
                val fraction = appleMatch.fraction
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.import_sync_apple_match_progress,
                        appleMatch.matched,
                        appleMatch.total,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        SettingsGroup(
            style = SettingsGroupStyle.Grouped,
            items = listOf(
                SettingsItem(
                    title = { Text(stringResource(R.string.import_sync_import_title)) },
                    highlightKey = "Import",
                    descriptionText = stringResource(R.string.import_sync_import_desc),
                    detail = stringResource(R.string.import_sync_import_detail),
                    icon = painterResource(R.drawable.playlist_add),
                    iconShape = CircleShape,
                    onClick = { navController.navigate(ImportProvidersRoute) },
                ),
                // Concord: read the Apple Music library into the app. Sits beside Import because it
                // is the same question — the sign-in it needs lives on that screen.
                SettingsItem(
                    // the Grouped style drops descriptionText, so the state has to be the title
                    title = {
                        Text(
                            when (val state = appleRead) {
                                is AppleReadState.Running -> stringResource(R.string.import_sync_apple_read_running) + " " + state.stage
                                is AppleReadState.Done -> stringResource(
                                    R.string.import_sync_apple_read_done,
                                    state.read,
                                    state.added,
                                )
                                is AppleReadState.Failed -> stringResource(
                                    R.string.import_sync_apple_read_failed,
                                    state.message,
                                )
                                else -> stringResource(R.string.import_sync_apple_read_title)
                            },
                        )
                    },
                    icon = painterResource(R.drawable.sync),
                    iconShape = CircleShape,
                    onClick = { viewModel.readAppleLibrary() },
                ),
                // Concord: fill the Apple <-> YouTube reference; the run lives in a service, so one tap
                // matches the whole library even after this screen is left
                SettingsItem(
                    title = {
                        Text(
                            when {
                                appleMatch.running -> stringResource(
                                    R.string.import_sync_apple_match_progress,
                                    appleMatch.matched,
                                    appleMatch.total,
                                )
                                appleMatch.finishedMessage != null -> appleMatch.finishedMessage.orEmpty()
                                else -> stringResource(R.string.import_sync_apple_match_title)
                            },
                        )
                    },
                    icon = painterResource(R.drawable.sync),
                    iconShape = CircleShape,
                    onClick = { viewModel.matchAppleOnYouTube() },
                ),
                // Concord: Apple's motion artwork is a fetch and a look; give the person the switch
                SettingsItem(
                    title = { Text(stringResource(R.string.import_sync_apple_animated_title)) },
                    descriptionText = stringResource(R.string.import_sync_apple_animated_desc),
                    icon = painterResource(R.drawable.slow_motion_video),
                    iconShape = CircleShape,
                    onClick = { viewModel.setAppleAnimatedCovers(!appleAnimated) },
                    trailingContent = {
                        AppSwitch(
                            checked = appleAnimated,
                            onCheckedChange = { viewModel.setAppleAnimatedCovers(it) },
                        )
                    },
                ),
                SettingsItem(
                    title = { Text(stringResource(R.string.import_sync_sync_title)) },
                    highlightKey = "Sync",
                    descriptionText = if (signedIn) stringResource(R.string.import_sync_sync_desc_connected)
                    else stringResource(R.string.import_sync_sync_desc_disconnected),
                    icon = painterResource(R.drawable.sync),
                    iconShape = CircleShape,
                    onClick = { navController.navigate(YouTubeSyncRoute) },
                ),
                SettingsItem(
                    title = { Text(stringResource(R.string.devices_title)) },
                    highlightKey = "Device integration",
                    descriptionText = stringResource(R.string.devices_row_detail),
                    icon = painterResource(R.drawable.smartphone),
                    iconShape = CircleShape,
                    onClick = { sheets.showDeviceIntegration(devices) },
                ),
                SettingsItem(
                    title = { Text(stringResource(R.string.import_sync_tunemymusic_title)) },
                    highlightKey = "TuneMyMusic CSV",
                    descriptionText = stringResource(R.string.import_sync_tunemymusic_desc),
                    detail = stringResource(R.string.import_sync_tunemymusic_detail),
                    icon = painterResource(R.drawable.web_link),
                    iconShape = CircleShape,
                    onClick = { navController.navigate(ImportTuneMyMusicRoute) },
                ),
            ),
        )

        Spacer(Modifier.height(16.dp))

        SettingsGroup(
            title = stringResource(R.string.import_sync_backup_title),
            style = SettingsGroupStyle.Grouped,
            items = listOf(
                SettingsItem(
                    title = { Text(stringResource(R.string.import_sync_export_csv_title)) },
                    highlightKey = "Export to Concord CSV",
                    descriptionText = stringResource(R.string.import_sync_export_csv_desc),
                    detail = stringResource(R.string.import_sync_export_csv_detail),
                    icon = painterResource(R.drawable.backup),
                    iconShape = CircleShape,
                    onClick = { exportLauncher.launch("Concord-library-${LocalDate.now()}.csv") },
                ),
            ),
        )
    }

    exportMessage?.let { message ->
        val working = message == "Exporting…"
        DefaultDialog(
            onDismiss = { if (!working) viewModel.clearExportMessage() },
            buttons = {
                if (!working) TextButton(onClick = viewModel::clearExportMessage) { Text(stringResource(R.string.import_sync_btn_ok)) }
            },
        ) {
            Text(if (working) stringResource(R.string.import_sync_exporting) else message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
