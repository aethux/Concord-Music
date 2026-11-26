// Concord: new — the two folder sheets, in the same shape as the playlist and collaborator menus.
//
// Mochy's sheets are one Column: the header row, a row of headline cards, then MenuRows on the
// sheet's own surface. Both of these follow that, so nothing here looks like a dialog dropped into a
// sheet-shaped app.
package com.mochyy.mochymusic.ui.menu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.ApplePlaylistWrites
import com.mochyy.mochymusic.applesync.PlaylistFolders
import com.mochyy.mochymusic.db.entities.Playlist
import com.mochyy.mochymusic.ui.component.DefaultDialog
import com.mochyy.mochymusic.ui.component.SheetActionCard
import com.mochyy.mochymusic.ui.component.DefaultDialog
import com.mochyy.mochymusic.ui.component.SheetActionCardRow
import com.mochyy.mochymusic.ui.component.SheetHeader
import com.mochyy.mochymusic.ui.component.PopupSheetHandle
import com.mochyy.mochymusic.ui.component.PopupSheetState
import com.mochyy.mochymusic.ui.component.SheetLabel
import com.mochyy.mochymusic.ui.component.SheetPillButton
import com.mochyy.mochymusic.ui.component.SheetTextField
import com.mochyy.mochymusic.ui.component.TextFieldDialog
import com.mochyy.mochymusic.ui.component.TopToaster
import com.mochyy.mochymusic.ui.player.menu.MenuRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Apple's folders, and the two moves worth offering: into one, or back out to the library. */
@Composable
fun FolderPickerSheet(playlist: Playlist, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }

    val tree by produceState(PlaylistFolders.Tree(emptyList(), emptyMap()), playlist.id) {
        val dao = runCatching {
            dagger.hilt.android.EntryPointAccessors.fromApplication(
                context.applicationContext,
                com.mochyy.mochymusic.applesync.di.ApplePlaylistEntryPoint::class.java,
            ).dao()
        }.getOrNull() ?: return@produceState
        dao.stateFlow().collect { rows ->
            rows.firstOrNull { it.key == PlaylistFolders.KEY }?.value?.let { value = PlaylistFolders.decode(it) }
        }
    }

    fun move(folderId: String?) {
        scope.launch(Dispatchers.IO) {
            val done = ApplePlaylistWrites.movePlaylist(
                context = context,
                localId = playlist.id,
                remoteId = playlist.id.removePrefix("AM_PL_"),
                folderId = folderId,
            )
            if (done) {
                withContext(Dispatchers.Main) {
                    onDismiss()
                    TopToaster.show(
                        context.getString(
                            if (folderId == null) R.string.playlist_remove_from_folder
                            else R.string.playlist_add_to_folder,
                        ),
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()),
    ) {
        SheetHeader(
            thumbnailUrl = playlist.thumbnails.firstOrNull(),
            title = playlist.playlist.name,
            subtitle = stringResource(R.string.playlist_add_to_folder),
        )
        Spacer(Modifier.height(16.dp))
        val inFolder = tree.parentOf.containsKey(playlist.id)
        SheetActionCardRow {
            SheetActionCard(
                icon = R.drawable.ic_folder_add,
                title = stringResource(R.string.folder_new),
                onClick = { naming = true },
                modifier = Modifier.weight(1f),
            )
            if (inFolder) {
                // the way back out sits beside it rather than under the folder list
                SheetActionCard(
                    icon = R.drawable.ic_folder_small,
                    title = stringResource(R.string.playlist_remove_from_folder),
                    onClick = { move(null) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                // one card, half the sheet: a lone card stretched full width reads as a bar
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
        val counts = tree.parentOf.values.groupingBy { it }.eachCount()
        tree.folders.forEach { folder ->
            MenuRow(
                icon = R.drawable.ic_folder_small,
                title = folder.name,
                onClick = { move(folder.id) },
                trailing = {
                    Text(
                        text = (counts[folder.id] ?: 0).toString(),
                        style = TextStyle(fontSize = 13.sp),
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f),
                    )
                },
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    if (naming) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.folder_new)) },
            placeholder = { Text(stringResource(R.string.folder_name_hint)) },
            onDismiss = { naming = false },
            onDone = { name ->
                naming = false
                scope.launch(Dispatchers.IO) {
                    val folderId = ApplePlaylistWrites.createFolder(context, name.trim())
                    if (folderId != null) move(folderId)
                }
            },
        )
    }
}

/** One folder's own sheet: rename it, put a new playlist in it, or delete it. */
@Composable
fun FolderMenuSheet(
    folderId: String,
    folderName: String,
    playlistCount: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val menuState = com.mochyy.mochymusic.ui.component.LocalMenuState.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 28.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()),
    ) {
        Column(modifier = Modifier.padding(horizontal = 2.dp)) {
            Text(
                text = folderName,
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = pluralStringResource(R.plurals.folder_playlist_count, playlistCount, playlistCount),
                style = TextStyle(fontSize = 13.sp),
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(16.dp))
        SheetActionCardRow {
            val sheetState = com.mochyy.mochymusic.ui.component.LocalMenuState.current
            SheetActionCard(
                icon = R.drawable.edit,
                title = stringResource(R.string.folder_rename),
                onClick = {
                    // from its own height, not the folder sheet's: a pushed sheet would match the
                    // taller menu behind it and open with half a screen of empty space
                    onDismiss()
                    sheetState.showRenameFolderSheet(folderId, folderName)
                },
                modifier = Modifier.weight(1f),
            )
            SheetActionCard(
                icon = R.drawable.playlist_add,
                title = stringResource(R.string.folder_add_playlist),
                onClick = { creating = true },
                modifier = Modifier.weight(1f),
            )
            SheetActionCard(
                icon = R.drawable.add_circle,
                title = stringResource(R.string.folder_edit_lists),
                onClick = { editing = true },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        MenuRow(
            icon = R.drawable.delete,
            title = stringResource(R.string.folder_delete),
            onClick = { deleting = true },
        )
        Spacer(Modifier.height(16.dp))
    }

    if (creating) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.folder_add_playlist)) },
            placeholder = { Text(stringResource(R.string.playlist_name_hint)) },
            onDismiss = { creating = false },
            onDone = { name ->
                creating = false
                scope.launch(Dispatchers.IO) {
                    if (ApplePlaylistWrites.createPlaylist(context, name.trim(), folderId) != null) onDismiss()
                }
            },
        )
    }

    if (editing) {
        editing = false
        menuState.show {
            FolderEditSheet(folderId = folderId, folderName = folderName, onDismiss = menuState::dismiss)
        }
    }

    if (creating) {
        DefaultDialog(
            onDismiss = { deleting = false },
            title = { Text(stringResource(R.string.folder_delete)) },
            buttons = {
                TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) }
                TextButton(
                    onClick = {
                        deleting = false
                        scope.launch(Dispatchers.IO) {
                            if (ApplePlaylistWrites.deleteFolder(context, folderId)) onDismiss()
                        }
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
        ) {
            Text(stringResource(R.string.folder_delete_confirm, folderName))
        }
    }
}

/** The folder's own playlist list: tap one to put it in the folder, tap again to take it out. */
@Composable
fun FolderEditSheet(folderId: String, folderName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val database = com.mochyy.mochymusic.LocalDatabase.current
    val scope = rememberCoroutineScope()

    val tree by produceState(PlaylistFolders.Tree(emptyList(), emptyMap()), folderId) {
        val dao = runCatching {
            dagger.hilt.android.EntryPointAccessors.fromApplication(
                context.applicationContext,
                com.mochyy.mochymusic.applesync.di.ApplePlaylistEntryPoint::class.java,
            ).dao()
        }.getOrNull() ?: return@produceState
        dao.stateFlow().collect { rows ->
            rows.firstOrNull { it.key == PlaylistFolders.KEY }?.value?.let { value = PlaylistFolders.decode(it) }
        }
    }
    val playlists by database.playlistsByCreateDateAsc().collectAsState(emptyList())

    fun toggle(localId: String, inside: Boolean) {
        if (!localId.startsWith("AM_PL_")) return
        scope.launch(Dispatchers.IO) {
            ApplePlaylistWrites.movePlaylist(
                context = context,
                localId = localId,
                remoteId = localId.removePrefix("AM_PL_"),
                folderId = if (inside) null else folderId,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()),
    ) {
        Column(modifier = Modifier.padding(horizontal = 2.dp)) {
            Text(
                text = folderName,
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.folder_edit_hint),
                style = TextStyle(fontSize = 13.sp),
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(14.dp))
        playlists.forEach { playlist ->
            val localId = playlist.id
            val inside = tree.parentOf[localId] == folderId
            MenuRow(
                icon = R.drawable.queue_music,
                title = playlist.playlist.name,
                enabled = localId.startsWith("AM_PL_"),
                onClick = { toggle(localId, inside) },
                trailing = {
                    androidx.compose.material3.Icon(
                        painter = androidx.compose.ui.res.painterResource(
                            if (inside) R.drawable.check else R.drawable.add_circle,
                        ),
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White.copy(alpha = if (inside) 0.9f else 0.5f),
                        modifier = Modifier.height(20.dp).width(20.dp),
                    )
                },
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Renaming a folder, in the shape of the create-playlist sheet: one field on the sheet, one pill at
 * the bottom, no dialog. The sheet closes itself once Apple has the new name.
 */
fun PopupSheetState.showRenameFolderSheet(folderId: String, currentName: String) {
    val name = mutableStateOf(currentName)
    showWithHandle(
        buttonBar = {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var busy by remember { mutableStateOf(false) }
            SheetPillButton(
                text = stringResource(R.string.save),
                enabled = name.value.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        val ok = ApplePlaylistWrites.renameFolder(context, folderId, name.value.trim())
                        withContext(Dispatchers.Main) { if (ok) dismiss() else busy = false }
                    }
                },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            SheetLabel(stringResource(R.string.folder_rename))
            Spacer(Modifier.height(16.dp))
            SheetTextField(
                value = name.value,
                onValueChange = { name.value = it.take(60) },
                placeholder = stringResource(R.string.folder_name_hint),
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}
