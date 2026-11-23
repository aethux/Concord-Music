// Concord: new — Apple's playlist page: one playlist and its tracks, read once, drawn from memory.
package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.AppleTokens
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.db.entities.SongSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ApplePlaylistViewModel @Inject constructor(
    private val tokens: AppleTokens,
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
    )

    private val _page = MutableStateFlow<Page?>(null)
    val page: StateFlow<Page?> = _page.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    init {
        load()
    }

    fun retry() {
        _failed.value = false
        load()
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val pair = tokens.read()
            val remote = pair?.let { runCatching { AppleClient(it).playlistPage(playlistId) }.getOrNull() }
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
            )
        }
    }
}
