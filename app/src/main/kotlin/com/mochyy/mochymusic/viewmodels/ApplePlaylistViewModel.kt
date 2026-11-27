// Concord: new — Apple's playlist page: one playlist and its tracks, read once, drawn from memory.
package com.mochyy.mochymusic.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleCollaborator
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.AppleLibraryFollow
import com.mochyy.mochymusic.applesync.ApplePageCache
import com.mochyy.mochymusic.applesync.db.AppleSyncDao
import com.mochyy.mochymusic.applesync.db.PlaylistMetaEntity
import com.mochyy.mochymusic.applesync.AppleTokens
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.db.entities.SongSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.mochyy.mochymusic.applesync.PageKind

@HiltViewModel
class ApplePlaylistViewModel @Inject constructor(
    private val tokens: AppleTokens,
    private val pageCache: ApplePageCache,
    private val dao: AppleSyncDao,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** the `p.…` or `pl.…` id from the route */
    val playlistId: String = savedStateHandle.get<String>("playlistId")!!

    data class Page(
        val name: String,
        val curator: String?,
        val description: String?,
        val artworkUrl: String?,
        val songs: List<Song>,
        val totalSeconds: Int,
        /** the playlist's page on music.apple.com */
        val url: String?,
        val owner: AppleCollaborator?,
        val collaborators: List<AppleCollaborator>,
        /** who added each song, keyed by the catalogue song id; empty unless the playlist is shared */
        val addedBy: Map<String, AppleCollaborator> = emptyMap(),
        /** more than one person built the playlist, so each row has someone to credit */
        val shared: Boolean = false,
    )

    private val _page = MutableStateFlow<Page?>(null)
    val page: StateFlow<Page?> = _page.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** no Apple session at all: the page draws the sign-in prompt instead of an error */
    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    /** true when this catalogue playlist is one the account follows (it has a library copy) */
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()
    private var catalogueId: String? = null
    private var libraryId: String? = null

    init {
        load()
        observeFollowing()
    }

    /** Cider's follow state: a catalogue playlist is saved when the library holds it. */
    private fun observeFollowing() = viewModelScope.launch(Dispatchers.IO) {
        val metas = dao.playlistMetas()
        val catalog = if (playlistId.startsWith("pl.")) {
            playlistId
        } else {
            metas.firstOrNull { it.playlistId == "AM_PL_$playlistId" }?.catalogId
        }
        catalogueId = catalog
        val meta = catalog?.let { c -> metas.firstOrNull { it.catalogId == c } }
        libraryId = meta?.playlistId?.removePrefix("AM_PL_")
        _saved.value = meta != null
    }

    fun toggleSaved() = viewModelScope.launch(Dispatchers.IO) {
        val catalog = catalogueId ?: return@launch
        val follow = !_saved.value
        val result = AppleLibraryFollow.change(context, "playlists", catalog, libraryId, follow)
        if (!result.ok) return@launch
        _saved.value = follow
        if (follow) {
            // Apple answers the new library id; remembering it here is what lets a second tap
            // unfollow straight away, without waiting for the next sync to import the playlist
            result.libraryId?.let { lib ->
                libraryId = lib
                runCatching {
                    dao.savePlaylistMeta(
                        PlaylistMetaEntity(playlistId = "AM_PL_$lib", canDelete = true, catalogId = catalog),
                    )
                }
            }
        } else {
            libraryId?.let { lib -> runCatching { dao.deletePlaylistMeta("AM_PL_$lib") } }
            libraryId = null
        }
    }

    fun retry() {
        _failed.value = false
        load()
    }

    /** True while a pull is re-reading this playlist: the page's own ring, not the library's. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Re-reads this playlist and nothing else: its cache row goes, then the loader runs again. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_refreshing.value) return@launch
            _refreshing.value = true
            try {
                runCatching {
                    pageCache.invalidate(PageKind.PLAYLIST, listOf(playlistId, playlistId.removePrefix("l.")))
                }
                _failed.value = false
                load().join()
            } finally {
                _refreshing.value = false
            }
        }
    }

    private fun load(): Job = viewModelScope.launch(Dispatchers.IO) {
            val pair = tokens.read()
            _signedOut.value = pair == null
            val remote = pair?.let { runCatching { AppleClient(it, pageCache).playlistPage(playlistId) }.getOrNull() }
            if (remote == null) {
                _failed.value = true
                return@launch
            }
            // draws exactly what Apple sent, in Apple's order; nothing is written to the library
            val songs = remote.songs.mapNotNull { song ->
                val key = song.catalogId ?: song.libraryId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val artistName = song.artist.ifBlank { "Unknown artist" }
                Song(
                    song = SongEntity(
                        id = "AM_$key",
                        title = song.title,
                        duration = ((song.durationMs ?: 0L) / 1000L).toInt(),
                        thumbnailUrl = AppleCovers.sized(song.artworkUrl ?: remote.artworkUrl),
                        albumName = song.album,
                        source = SongSource.Apple,
                    ),
                    artists = listOf(ArtistEntity(id = artistName, name = artistName)),
                )
            }
            _failed.value = false
            _page.value = Page(
                name = remote.name,
                curator = remote.curator,
                description = remote.description,
                artworkUrl = AppleCovers.sized(remote.artworkUrl),
                songs = songs,
                totalSeconds = songs.sumOf { it.song.duration },
                url = remote.url,
                owner = remote.owner,
                collaborators = remote.collaborators,
                addedBy = remote.addedBy,
                shared = remote.shared,
            )
    }
}
