// Concord: new — Apple's artist page: one read of the artist and its five views.
package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.applesync.AppleArtist
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
class AppleArtistViewModel @Inject constructor(
    private val tokens: AppleTokens,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** the catalogue artist id from the route */
    val artistId: String = savedStateHandle.get<String>("artistId")!!

    data class Page(
        val artist: AppleArtist,
        /** the top songs, as playable rows */
        val songs: List<Song>,
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
            val artist = pair?.let { runCatching { AppleClient(it).artistPage(artistId) }.getOrNull() }
            if (artist == null) {
                _failed.value = true
                return@launch
            }
            val songs = artist.topSongs.mapNotNull { remote ->
                val key = remote.catalogId ?: remote.libraryId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val artistName = remote.artist.ifBlank { artist.name }
                Song(
                    song = SongEntity(
                        id = "AM_$key",
                        title = remote.title,
                        duration = ((remote.durationMs ?: 0L) / 1000L).toInt(),
                        thumbnailUrl = AppleCovers.sized(remote.artworkUrl ?: artist.artworkUrl),
                        albumName = remote.album,
                        source = SongSource.Apple,
                    ),
                    artists = listOf(ArtistEntity(id = artistName, name = artistName)),
                )
            }
            _failed.value = false
            _page.value = Page(artist = artist, songs = songs)
        }
    }
}
