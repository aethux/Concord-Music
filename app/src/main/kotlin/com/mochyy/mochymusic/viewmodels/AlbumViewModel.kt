// AlbumViewModel.kt

package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.mochyy.mochymusic.applesync.AppleAlbums
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.AppleTokens
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.entities.AlbumEntity
import com.mochyy.mochymusic.db.entities.AlbumWithSongs
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.db.entities.SongSource
import com.mochyy.mochymusic.utils.reportException
import com.mochyy.mochymusic.utils.ArtistImageResolver
import com.mochyy.mochymusic.utils.ArtistPageCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.mochyy.mochymusic.utils.Wikipedia
import com.mochyy.mochymusic.utils.AppleMusicAboutAlbum
import javax.inject.Inject

@HiltViewModel
class AlbumViewModel
@Inject
constructor(
    private val database: MusicDatabase,
    private val tokens: AppleTokens,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val albumId = savedStateHandle.get<String>("albumId")!!
    val playlistId = MutableStateFlow("")

    /**
     * The Apple id when this page is Apple's: an imported Apple album is `AM_ALB_<apple id>`, a
     * recommendation card carries the bare id. That inner id is often a **library** album id
     * (`l.…`), which the read resolves to its catalogue twin. Null means the page stays Mochy's.
     */
    private val appleRemoteId = AppleAlbums.remoteIdOf(albumId)

    /** true when this album is Apple's, so the page reads the catalogue and never asks YouTube. */
    val isAppleAlbum: Boolean get() = appleRemoteId != null

    private val appleAlbum = MutableStateFlow<AlbumWithSongs?>(null)

    val albumWithSongs: StateFlow<AlbumWithSongs?> =
        if (appleRemoteId == null) {
            database
                .albumWithSongs(albumId)
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)
        } else {
            appleAlbum
        }
    var otherVersions = MutableStateFlow<List<AlbumItem>>(emptyList())
    var releasesForYou = MutableStateFlow<List<AlbumItem>>(emptyList())
    var description = MutableStateFlow<String?>(null)
    var descriptionRuns = MutableStateFlow<List<com.music.innertube.models.Run>?>(null)

    /** the main artist's other albums and singles, newest first, without this one */
    val moreByArtist = MutableStateFlow<List<AlbumItem>>(emptyList())

    /** YouTube couldn't be reached and nothing was saved to show instead: the page offers a retry */
    val loadFailed = MutableStateFlow(false)

    init {
        if (appleRemoteId == null) load() else loadApple()
    }

    fun retry() {
        loadFailed.value = false
        if (appleRemoteId == null) load() else loadApple()
    }

    /**
     * Apple's album page, read the way Cider reads `/am/album/:id`: the catalogue album plus its own
     * tracklist, drawn from memory, nothing written to the library.
     *
     * No YouTube call is made on this path. `YouTube.album("AM_ALB_…")` cannot answer, and its
     * NOT_FOUND branch deletes the album row — which is exactly what hiding YouTube has to stop.
     */
    private fun loadApple() {
        val remoteId = appleRemoteId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val pair = tokens.read()
            val page = pair?.let { runCatching { AppleClient(it).catalogAlbum(remoteId) }.getOrNull() }
            if (page == null) {
                loadFailed.value = true
                return@launch
            }
            val localAlbumId = albumId
            val artistName = page.artistName.ifBlank { "Unknown artist" }
            // the artist row the page draws its creator line from; untappable unless the artist is
            // already in the library, because there is no Apple artist page yet
            val artist = ArtistEntity(
                id = database.artistByName(artistName)?.id ?: ArtistEntity.generateArtistId(),
                name = artistName,
            )
            val album = AlbumEntity(
                id = localAlbumId,
                title = page.title,
                year = page.year,
                thumbnailUrl = AppleCovers.sized(page.artworkUrl),
                songCount = page.songs.size,
                duration = page.songs.sumOf { ((it.durationMs ?: 0L) / 1000L).toInt() },
                explicit = page.explicit,
            )
            // Apple's own track order; a track with no number keeps the order Apple sent it in
            val songs = page.songs
                .sortedBy { it.trackNumber ?: Int.MAX_VALUE }
                .mapNotNull { remote ->
                    // a library song with no catalogue id is an upload: its library id is the only
                    // stable key it has, and playback resolves it by title and artist
                    val songKey = remote.catalogId ?: remote.libraryId.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    Song(
                        song = SongEntity(
                            id = "AM_$songKey",
                            title = remote.title,
                            duration = ((remote.durationMs ?: 0L) / 1000L).toInt(),
                            thumbnailUrl = AppleCovers.sized(remote.artworkUrl ?: page.artworkUrl),
                            albumId = localAlbumId,
                            albumName = page.title,
                            year = page.year,
                            source = SongSource.Apple,
                        ),
                        artists = listOf(ArtistEntity(id = artist.id, name = remote.artist.ifBlank { artistName })),
                        album = album,
                    )
                }
            description.value = page.description
            loadFailed.value = false
            appleAlbum.value = AlbumWithSongs(album = album, artists = listOf(artist), songs = songs)
        }
    }

    private fun load() {
        viewModelScope.launch {
            val album = database.album(albumId).first()
            // a local album only lives on this phone, youtube would answer not found and the album would get deleted below
            if (album?.album?.isLocal == true) return@launch
            if (album?.description != null) {
                description.value = album.description
            }
            // a saved album can start its automix before YouTube has answered
            album?.album?.playlistId?.let { if (playlistId.value.isEmpty()) playlistId.value = it }
            album?.artists?.firstOrNull()?.id?.let(::loadMoreByArtist)
            YouTube
                .album(albumId)
                .onSuccess {
                    loadFailed.value = false
                    playlistId.value = it.album.playlistId
                    if (moreByArtist.value.isEmpty()) {
                        it.album.artists?.firstOrNull()?.id?.let(::loadMoreByArtist)
                    }
                    otherVersions.value = it.otherVersions
                    releasesForYou.value = it.releasesForYou
                    if (it.description != null) {
                        description.value = it.description
                    }
                    descriptionRuns.value = it.descriptionRuns
                    database.transaction {
                        if (album == null) {
                            insert(it)
                        } else {
                            update(album.album, it, album.artists)
                        }
                    }

                    val albumArtists = it.album.artists
                    if (albumArtists?.size == 1) {
                        albumArtists.firstOrNull()?.id?.let { artistId ->
                            viewModelScope.launch(Dispatchers.IO) {
                                val artistEntity = database.getArtistById(artistId)
                                if (artistEntity != null && artistEntity.thumbnailUrl == null) {
                                    val preferredThumbnailUrl = ArtistImageResolver.resolveThumbnail(artistEntity)
                                    YouTube.artist(artistId).onSuccess { artistPage ->
                                        database.query {
                                            getArtistById(artistId)?.let { currentArtist ->
                                                update(currentArtist, artistPage, preferredThumbnailUrl)
                                            }
                                        }
                                    }.onFailure {
                                        if (preferredThumbnailUrl != null) {
                                            database.query {
                                                getArtistById(artistId)?.let { currentArtist ->
                                                    update(currentArtist.copy(thumbnailUrl = preferredThumbnailUrl))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (description.value == null && descriptionRuns.value == null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val artistName = album?.artists?.firstOrNull()?.name
                                ?: database.albumWithSongs(albumId).first()?.artists?.firstOrNull()?.name
                            val wikiDescription = Wikipedia.fetchAlbumInfo(it.album.title, artistName)
                            if (wikiDescription != null) {
                                description.value = wikiDescription
                                val currentAlbum = database.album(albumId).first()
                                if (currentAlbum != null) {
                                    database.query {
                                        update(currentAlbum.album.copy(description = wikiDescription))
                                    }
                                }
                            } else {
                                val appleDescription = AppleMusicAboutAlbum.fetchAlbumDescription(it.album.title, artistName)
                                if (appleDescription != null) {
                                    description.value = appleDescription
                                    val currentAlbum = database.album(albumId).first()
                                    if (currentAlbum != null) {
                                        database.query {
                                            update(currentAlbum.album.copy(description = appleDescription))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.onFailure {
                    reportException(it)
                    if (album == null || database.albumWithSongs(albumId).first()?.songs.isNullOrEmpty()) {
                        loadFailed.value = true
                    }
                    if (it.message?.contains("NOT_FOUND") == true) {
                        val albumToDelete = album?.album
                        if (albumToDelete != null) {
                            database.query {
                                delete(albumToDelete)
                            }
                        }
                    }
                }
        }
    }

    private var moreByArtistFor: String? = null

    private fun loadMoreByArtist(artistId: String) {
        if (moreByArtistFor == artistId) return
        moreByArtistFor = artistId
        viewModelScope.launch(Dispatchers.IO) {
            val page = ArtistPageCache.page(artistId)
                ?: YouTube.artist(artistId).getOrNull()?.also { ArtistPageCache.putPage(artistId, it) }
                ?: return@launch
            moreByArtist.value = page.sections
                .filter { it.items.firstOrNull() is AlbumItem }
                .flatMap { section -> section.items.filterIsInstance<AlbumItem>() }
                .filter { it.browseId != albumId }
                .distinctBy { it.browseId }
                .sortedByDescending { it.year ?: 0 }
                .take(16)
        }
    }
}
