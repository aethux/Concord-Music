// Concord: new — the folder sheets, in the same shape as the playlist and collaborator menus.
//
// Mochy's sheets are one Column: a header, headline action cards, then rows on the sheet's own
// surface. Everything here follows that, including the two list sheets, which draw the playlists
// with their covers the way the library does.
package com.mochyy.mochymusic.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.LocalDatabase
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.ApplePlaylistWrites
import com.mochyy.mochymusic.applesync.PlaylistFolders
import com.mochyy.mochymusic.db.entities.Playlist
import com.mochyy.mochymusic.ui.component.DefaultDialog
import com.mochyy.mochymusic.ui.component.LocalMenuState
import com.mochyy.mochymusic.ui.component.PopupSheetState
import com.mochyy.mochymusic.ui.component.SheetActionCard
import com.mochyy.mochymusic.ui.component.SheetActionCardRow
import com.mochyy.mochymusic.ui.component.SheetHeader
import com.mochyy.mochymusic.ui.component.SheetLabel
import com.mochyy.mochymusic.ui.component.SheetPillButton
import com.mochyy.mochymusic.ui.component.SheetTextField
import com.mochyy.mochymusic.ui.component.TextFieldDialog
import com.mochyy.mochymusic.ui.component.TopToaster
import com.mochyy.mochymusic.ui.player.menu.MenuRow
import com.mochyy.mochymusic.ui.utils.resize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Apple's folders. A folder is not a one-tap move any more: tapping it opens the folder's own
 * playlists (covers and all), and the pill down there does the move, so the person sees what
 * they are joining before they join it.
 */
@Composable
fun FolderPickerSheet(playlist: Playlist, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val menuState = LocalMenuState.current
    var naming by remember { mutableStateOf(false) }

    val tree by folderTree(playlist.id)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()),
    ) {
        SheetHeader(
            thumbnailUrl = playlist.thumbnails.firstOrNull(),
            title = playlist.playlist.name,
            subtitle = stringResource(R.string.playlist_add_to_folder),
        )
        Spacer(Modifier.height(16.dp))
        val counts = tree.parentOf.values.groupingBy { it }.eachCount()
        SheetActionCardRow {
            SheetActionCard(
                icon = R.drawable.ic_folder_add,
                title = stringResource(R.string.folder_new),
                onClick = { naming = true },
                modifier = Modifier.weight(1f),
            )
            // one card, half the sheet: a lone card stretched full width reads as a bar
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        tree.folders.forEach { folder ->
            MenuRow(
                icon = R.drawable.ic_folder_small,
                title = folder.name,
                onClick = {
                    // the list replaces this sheet: close it outright, so its top edge cannot peek
                    // above a shorter sheet
                    menuState.dismissAll()
                    menuState.show {
                        FolderContentsSheet(
                            folderId = folder.id,
                            folderName = folder.name,
                            playlist = playlist,
                            onDismiss = menuState::dismiss,
                        )
                    }
                },
                trailing = {
                    Text(
                        text = (counts[folder.id] ?: 0).toString(),
                        style = TextStyle(fontSize = 13.sp),
                        color = Color.White.copy(alpha = 0.55f),
                    )
                },
            )
        }
        Spacer(Modifier.height(12.dp))
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
                    if (folderId != null) withContext(Dispatchers.Main) { onDismiss() }
                }
            },
        )
    }
}

/** One folder's playlists, with the pill that puts this playlist in — or takes it back out. */
@Composable
private fun FolderContentsSheet(
    folderId: String,
    folderName: String,
    playlist: Playlist,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    val tree by folderTree(folderId)
    val playlists by database.playlistsByCreateDateAsc().collectAsState(emptyList())
    val inside = tree.parentOf[playlist.id] == folderId
    val inIt = playlists.filter { tree.parentOf[it.id] == folderId }

    fun move() {
        scope.launch(Dispatchers.IO) {
            val done = ApplePlaylistWrites.movePlaylist(
                context = context,
                localId = playlist.id,
                remoteId = playlist.id.removePrefix("AM_PL_"),
                folderId = if (inside) null else folderId,
            )
            withContext(Dispatchers.Main) {
                onDismiss()
                if (done) {
                    TopToaster.show(
                        context.getString(
                            if (inside) R.string.playlist_remove_from_folder
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
            .padding(bottom = 24.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()),
    ) {
        Column(modifier = Modifier.padding(horizontal = 2.dp)) {
            Text(
                text = folderName,
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = pluralStringResource(R.plurals.folder_playlist_count, inIt.size, inIt.size),
                style = TextStyle(fontSize = 13.sp),
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(12.dp))
        inIt.forEach { item -> PlaylistPickRow(playlist = item, trailing = null, onClick = null) }
        Spacer(Modifier.height(16.dp))
        SheetPillButton(
            text = stringResource(
                if (inside) R.string.playlist_remove_from_folder else R.string.folder_add_this,
            ),
            onClick = { move() },
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** One folder's own sheet: a new playlist in it, its edit screen, or delete. */
@Composable
fun FolderMenuSheet(
    folderId: String,
    folderName: String,
    playlistCount: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val menuState = LocalMenuState.current
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

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
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(16.dp))
        SheetActionCardRow {
            SheetActionCard(
                icon = R.drawable.playlist_add,
                title = stringResource(R.string.folder_add_playlist),
                onClick = { creating = true },
                modifier = Modifier.weight(1f),
            )
            SheetActionCard(
                icon = R.drawable.edit,
                title = stringResource(R.string.folder_edit),
                onClick = {
                    menuState.dismissAll()
                    menuState.showEditFolderSheet(folderId, folderName)
                },
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

    if (deleting) {
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

/**
 * A folder's edit screen, one sheet: the name (saved by the pill), then every playlist with its
 * cover. Tapping a playlist puts it in the folder or takes it out — no separate rename screen, and
 * no list of names with no pictures.
 */
fun PopupSheetState.showEditFolderSheet(folderId: String, currentName: String) {
    val name = mutableStateOf(currentName)
    showWithHandle(
        buttonBar = {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var busy by remember { mutableStateOf(false) }
            SheetPillButton(
                text = stringResource(R.string.done),
                enabled = name.value.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        val failedRename = name.value.trim() != currentName &&
                            !ApplePlaylistWrites.renameFolder(context, folderId, name.value.trim())
                        withContext(Dispatchers.Main) { if (failedRename) busy = false else dismiss() }
                    }
                },
            )
        },
    ) {
        val context = LocalContext.current
        val database = LocalDatabase.current
        val scope = rememberCoroutineScope()
        val tree by folderTree(folderId)
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
                .padding(bottom = 20.dp),
        ) {
            SheetLabel(stringResource(R.string.folder_name))
            Spacer(Modifier.height(8.dp))
            SheetTextField(
                value = name.value,
                onValueChange = { name.value = it.take(60) },
                placeholder = stringResource(R.string.folder_name_hint),
            )
            Spacer(Modifier.height(18.dp))
            SheetLabel(stringResource(R.string.folder_playlists_title))
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.folder_edit_hint),
                style = TextStyle(fontSize = 12.sp),
                color = Color.White.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(6.dp))
            playlists.forEach { playlist ->
                val localId = playlist.id
                val inside = tree.parentOf[localId] == folderId
                PlaylistPickRow(
                    playlist = playlist,
                    trailing = if (inside) R.drawable.check else R.drawable.add_circle,
                    onClick = if (localId.startsWith("AM_PL_")) {
                        { toggle(localId, inside) }
                    } else {
                        null
                    },
                )
            }
            // room under the last cover, clear of the pill
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** A playlist row with its cover, the way the library draws one, plus an optional icon at the end. */
@Composable
private fun PlaylistPickRow(
    playlist: Playlist,
    trailing: Int?,
    onClick: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 7.dp),
    ) {
        AsyncImage(
            model = playlist.thumbnails.firstOrNull()?.resize(240, 240),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = playlist.playlist.name,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Icon(
                painter = painterResource(trailing),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The folder tree, observed: a write made on one of these sheets lands on the page behind it. */
@Composable
internal fun folderTree(key: String): androidx.compose.runtime.State<PlaylistFolders.Tree> {
    val app = LocalContext.current.applicationContext
    return produceState(
        initialValue = PlaylistFolders.Tree(emptyList(), emptyMap()),
        key1 = key,
    ) {
        val dao = runCatching {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            app,
            com.mochyy.mochymusic.applesync.di.ApplePlaylistEntryPoint::class.java,
            ).dao()
        }.getOrNull() ?: return@produceState
        dao.stateFlow().collect { rows ->
            rows.firstOrNull { it.key == PlaylistFolders.KEY }?.value?.let { value = PlaylistFolders.decode(it) }
        }
    }
}
