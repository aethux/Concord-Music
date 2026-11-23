// ImportSyncViewModel.kt

package com.mochyy.mochymusic.viewmodels

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.App
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.importer.ImportOptions
import com.mochyy.mochymusic.importer.ImportProgress
import com.mochyy.mochymusic.importer.LinkImportException
import com.mochyy.mochymusic.importer.LinkImporter
import androidx.datastore.preferences.core.edit
import com.mochyy.mochymusic.importer.MusicImportService
import com.mochyy.mochymusic.utils.dataStore
import com.mochyy.mochymusic.importer.MochyCsvExporter
import com.mochyy.mochymusic.importer.ParsedImport
import com.mochyy.mochymusic.importer.YouTubeSyncManager
import com.mochyy.mochymusic.importer.YtSyncState
import com.mochyy.mochymusic.importer.parseImportCsv
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed interface PendingImport {
    data object Idle : PendingImport
    data class Loading(val label: String) : PendingImport
    data class Ready(val parsed: ParsedImport, val sourceLabel: String) : PendingImport
    data class Failed(val message: String) : PendingImport
}

/** Concord: the state of one Apple library read. */
sealed interface AppleReadState {
    data object Idle : AppleReadState
    data class Running(val stage: String = "") : AppleReadState
    data class Done(val read: Int, val added: Int) : AppleReadState
    data class Failed(val message: String) : AppleReadState
}

@HiltViewModel
class ImportSyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val importService: MusicImportService,
    private val youTubeSync: YouTubeSyncManager,
    // Concord: the Apple engine's sidecar DAO and token pair, both provided by AppleSyncModule
    private val appleDao: com.mochyy.mochymusic.applesync.db.AppleSyncDao,
    private val appleTokens: com.mochyy.mochymusic.applesync.AppleTokens,
) : ViewModel() {

    val importProgress: StateFlow<ImportProgress> = importService.progress
    val youTubeState: StateFlow<YtSyncState> = youTubeSync.state

    // Concord: one read of the Apple library, started from Settings → Import and sync
    private val _appleRead = MutableStateFlow<AppleReadState>(AppleReadState.Idle)
    val appleRead: StateFlow<AppleReadState> = _appleRead.asStateFlow()

    // Concord: whether Apple's motion artwork is used at all (default on)
    val appleAnimatedCovers: StateFlow<Boolean> = context.dataStore.data
        .map { it[com.mochyy.mochymusic.constants.AppleAnimatedCoversKey] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // Concord: fills the Apple <-> YouTube reference in a foreground service, so a run that is a few
    // thousand searches long survives leaving this screen; AppleMatchStatus is what the row reads
    fun matchAppleOnYouTube() {
        if (com.mochyy.mochymusic.applesync.AppleMatchStatus.progress.running) return
        com.mochyy.mochymusic.applesync.AppleMatchService.start(context)
    }

    fun setAppleAnimatedCovers(on: Boolean) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            context.dataStore.edit { it[com.mochyy.mochymusic.constants.AppleAnimatedCoversKey] = on }
        }
    }

    fun readAppleLibrary() {
        if (_appleRead.value is AppleReadState.Running) return
        _appleRead.value = AppleReadState.Running("start")
        // Concord: on IO, not Main. AppleImport's inserts go through Mochy's blocking DAO methods, and
        // Room refuses those on the main thread ("Cannot access database on the main thread").
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _appleRead.value = runCatching {
                val refresh = com.mochyy.mochymusic.applesync.AppleLibraryRefresh(
                    database, appleDao, appleTokens, appleAnimatedCovers.value,
                )
                val result = refresh.run { stage -> _appleRead.value = AppleReadState.Running(stage) }
                AppleReadState.Done(result.read, result.added)
            }.getOrElse {
                // the row only shows the message; the stack goes here so a failure is diagnosable
                timber.log.Timber.tag("AppleRead").e(it, "Apple library read failed")
                AppleReadState.Failed(it.message ?: "Couldn't read Apple Music")
            }
        }
    }

    private val _pending = MutableStateFlow<PendingImport>(PendingImport.Idle)
    val pending: StateFlow<PendingImport> = _pending.asStateFlow()

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    private var loadJob: Job? = null

    fun loadLink(link: String) {
        val source = LinkImporter.detect(link)?.label ?: "link"
        loadJob?.cancel()
        _pending.value = PendingImport.Loading("Reading the $source playlist…")
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _pending.value = try {
                PendingImport.Ready(LinkImporter.fetch(link), source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkImportException) {
                PendingImport.Failed(e.message ?: "Couldn't read that link.")
            } catch (e: Exception) {
                Timber.e(e, "Link import failed")
                PendingImport.Failed("Couldn't read that link.")
            }
        }
    }

    /** [sourceLabel] is only for the review dialog ("Concord backup", "TuneMyMusic"). */
    fun loadFile(uri: Uri, sourceLabel: String) {
        loadJob?.cancel()
        _pending.value = PendingImport.Loading("Reading the file…")
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _pending.value = try {
                val size = queryColumn(uri, OpenableColumns.SIZE)?.toLongOrNull()
                if (size != null && size > MAX_CSV_BYTES) {
                    PendingImport.Failed("That file is too big to be a playlist export.")
                } else {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw IllegalStateException("Couldn't open that file")
                    val fileName = queryColumn(uri, OpenableColumns.DISPLAY_NAME)
                        ?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Imported playlist"
                    val parsed = parseImportCsv(text, fallbackPlaylistName = fileName)
                    if (parsed.totalSongs == 0 && parsed.totalPlaylists == 0) {
                        PendingImport.Failed("No songs in that file. It needs a header row with at least a \"Track name\" column.")
                    } else {
                        PendingImport.Ready(parsed, sourceLabel)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "File import failed")
                PendingImport.Failed("Couldn't read that file. Make sure it's a .csv export.")
            }
        }
    }

    /** Something already read elsewhere (an account import) goes straight to the review dialog. */
    fun offer(parsed: ParsedImport, sourceLabel: String) {
        loadJob?.cancel()
        _pending.value = PendingImport.Ready(parsed, sourceLabel)
    }

    fun dismissPending() {
        loadJob?.cancel()
        _pending.value = PendingImport.Idle
    }

    /** False when another import is still running. */
    fun startImport(parsed: ParsedImport, mirrorToYouTube: Boolean): Boolean {
        val started = importService.startImport(parsed, ImportOptions(mirrorToYouTube = mirrorToYouTube))
        if (started) _pending.value = PendingImport.Idle
        return started
    }

    fun cancelImport() = importService.cancel()

    fun clearFinishedImport() = importService.clearFinished()

    fun export(uri: Uri) {
        _exportMessage.value = "Exporting…"
        viewModelScope.launch(Dispatchers.IO) {
            _exportMessage.value = try {
                val (csv, summary) = MochyCsvExporter.build(database)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    // the BOM makes Excel read names in any script correctly; our reader skips it
                    out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    out.write(csv.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Couldn't write the file")
                "Exported ${summary.likedSongs} liked songs and ${summary.playlists} playlists (${summary.playlistSongs} songs)."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Export failed")
                "Export failed: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun clearExportMessage() {
        _exportMessage.value = null
    }

    fun syncNow() = youTubeSync.syncNow()
    fun pullHistoryNow() = youTubeSync.pullHistoryNow()
    fun uploadLocalPlaylists() = youTubeSync.uploadLocalPlaylists()
    fun undoLastSync(withYouTube: Boolean) = youTubeSync.undoLastSync(withYouTube)
    suspend fun lostLikesCount() = youTubeSync.lostLikesCount()
    fun bringBackLikes() = youTubeSync.bringBackLikes()
    fun clearYouTubeMessage() = youTubeSync.clearMessage()

    /** Signs out but keeps everything already synced in the library. */
    fun signOut() {
        viewModelScope.launch(Dispatchers.IO) { App.forgetAccount(context) }
    }

    private fun queryColumn(uri: Uri, column: String): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    private companion object {
        const val MAX_CSV_BYTES = 40L * 1024 * 1024
    }
}
