// Concord: new — Apple's artist page: one read of the artist and its five views.
package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.applesync.AppleArtist
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.ApplePageCache
import com.mochyy.mochymusic.applesync.AppleTokens
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.db.entities.SongSource
import com.mochyy.mochymusic.canvas.AppleArtistEditorial
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.playback.queues.AppleStationQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.mochyy.mochymusic.applesync.PageKind

@HiltViewModel
class AppleArtistViewModel @Inject constructor(
    private val tokens: AppleTokens,
    private val database: MusicDatabase,
    private val pageCache: ApplePageCache,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** the catalogue artist id from the route */
    val artistId: String = savedStateHandle.get<String>("artistId")!!

    /** which list the songs page was opened for: the artist's top songs, or the ones in the library */
    val source: String? = savedStateHandle.get<String>("source")

    data class Page(
        val artist: AppleArtist,
        /** the top songs, as playable rows */
        val songs: List<Song>,
    )

    private val _page = MutableStateFlow<Page?>(null)
    val page: StateFlow<Page?> = _page.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** no Apple session at all: the page draws the sign-in prompt instead of an error */
    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    /** the artist's own songs that are in the library, read from the database by artist name */
    private val _librarySongs = MutableStateFlow<List<Song>>(emptyList())
    val librarySongs: StateFlow<List<Song>> = _librarySongs.asStateFlow()

    /** the library albums holding this artist's songs */
    private val _libraryAlbums = MutableStateFlow<List<com.mochyy.mochymusic.db.entities.AlbumEntity>>(emptyList())
    val libraryAlbums: StateFlow<List<com.mochyy.mochymusic.db.entities.AlbumEntity>> = _libraryAlbums.asStateFlow()

    /** Mochy's heart on an artist: a bookmark on the artist row, kept on this phone */
    private val _liked = MutableStateFlow(false)
    val liked: StateFlow<Boolean> = _liked.asStateFlow()

    /** Apple's editorial for this artist: the motion loops, the still, the wordmark and the bio */
    private val _editorial = MutableStateFlow<AppleArtistEditorial?>(null)
    val editorial: StateFlow<AppleArtistEditorial?> = _editorial.asStateFlow()

    private var observer: Job? = null

    init {
        load()
    }

    fun retry() {
        _failed.value = false
        load()
    }

    /** True while a pull is re-reading this artist: the page's own ring, not the library's. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * Re-reads this artist and nothing else, the way a pull on a page works in Cider: the page cache
     * row and the ten-minute memory are dropped first, then the same loader runs again.
     */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_refreshing.value) return@launch
            _refreshing.value = true
            try {
                runCatching { pageCache.invalidate(PageKind.ARTIST, listOf(artistId)) }
                AppleClient.forgetArtist(artistId)
                _failed.value = false
                load().join()
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** the artist's radio: a station seeded from the artist itself */
    suspend fun stationQueue(): AppleStationQueue? =
        AppleStationQueue.seeded(tokens, "ra.$artistId")

    private fun observeLibrary(name: String) {
        observer?.cancel()
        observer = viewModelScope.launch {
            launch { database.songsByArtistName(name).collect { _librarySongs.value = it } }
            launch { database.artistRowByName(name).collect { row -> _liked.value = row?.bookmarkedAt != null } }
            launch { database.albumsByArtistName(name).collect { _libraryAlbums.value = it } }
        }
    }

    fun toggleLike() {
        val artist = page.value?.artist ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val existing = database.artistByName(artist.name)
            if (existing == null) {
                database.insert(
                    com.mochyy.mochymusic.db.entities.ArtistEntity(
                        id = artist.name,
                        name = artist.name,
                        thumbnailUrl = artist.artworkUrl,
                        bookmarkedAt = java.time.LocalDateTime.now(),
                    )
                )
            } else {
                database.update(
                    existing.copy(
                        bookmarkedAt = if (existing.bookmarkedAt == null) java.time.LocalDateTime.now() else null,
                    )
                )
            }
        }
    }

    private fun load(): Job = viewModelScope.launch(Dispatchers.IO) {
            val pair = tokens.read()
            _signedOut.value = pair == null
            val client = pair?.let { AppleClient(it, pageCache) }
            // the Library's artist rows carry Apple's *library* artist id (r.…); the page reads
            // are keyed by the catalogue id, which the library artist's own catalog twin answers
            val id = if (artistId.startsWith("r.")) {
                // Apple often answers an empty `catalog` relationship for a library artist (it did
                // for Adele), so the name is the fallback: the artist's own row still knows it
                val twin = client?.let { runCatching { it.catalogIdForLibraryArtist(artistId) }.getOrNull() }
                if (twin != null) {
                    twin
                } else {
                    val name = runCatching { database.artist(artistId).first()?.artist?.name }.getOrNull()
                    val found = name?.let { n -> client?.let { c -> runCatching { c.artistIdByName(n) }.getOrNull() } }
                    found ?: artistId
                }
            } else {
                artistId
            }
            val artist = client?.let { runCatching { it.artistPage(id) }.getOrNull() }
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
            observeLibrary(artist.name)
            // the banner's hero video, logo and bio came with this same payload
            _editorial.value = artist.editorial
    }
}
