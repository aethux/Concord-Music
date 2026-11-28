// AlbumViewModel.kt

package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.mochyy.mochymusic.applesync.AppleAlbumPage
import com.mochyy.mochymusic.applesync.AppleAlbums
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.AppleKind
import com.mochyy.mochymusic.applesync.AppleLibraryFollow
import com.mochyy.mochymusic.applesync.ApplePageCache
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.mochyy.mochymusic.utils.Wikipedia
import com.mochyy.mochymusic.utils.AppleMusicAboutAlbum
import javax.inject.Inject
import com.mochyy.mochymusic.applesync.PageKind
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class AlbumViewModel
@Inject
constructor(
    private val database: MusicDatabase,
    private val tokens: AppleTokens,
    private val pageCache: ApplePageCache,
    @ApplicationContext private val context: Context,
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

    /** Apple's artist id behind an Apple album, once its page has been read. */
    val appleArtistId: String?
        get() = appleArtistIdFlow.value

    /**
     * The artist page's id for a name, when the album's own resource did not carry it: Apple's album
     * has no artistId attribute, so the relationship or a name search is the only way in.
     */
    suspend fun appleArtistIdFor(name: String): String? {
        appleArtistIdFlow.value?.let { return it }
        val pair = tokens.read() ?: return null
        return runCatching { AppleClient(pair).artistIdByName(name) }.getOrNull()
    }

    private val appleArtistIdFlow = MutableStateFlow<String?>(null)

    private val appleAlbum = MutableStateFlow<AlbumWithSongs?>(null)

    /**
     * The page Apple answered for this album, kept for the save button: its `id` is the catalogue id
     * every library write takes, and its `inLibrary` is the state Apple reported.
     */
    private var applePage: AppleAlbumPage? = null

    /** True when the library holds this album; null until Apple has said either way. */
    private val _saved = MutableStateFlow<Boolean?>(null)
    val saved: StateFlow<Boolean?> = _saved.asStateFlow()

    /**
     * Saves this catalogue album to Apple's library, or takes it back out — Cider's calls, with the
     * guard, the write budget and the audit in front of them (`AppleLibraryFollow`).
     *
     * The button's state moves only after Apple answered, and only to what was asked for. A removal
     * needs the `l.…` id, so an album Apple does not hold ends here: there is nothing to delete and
     * the page simply shows unsaved.
     */
    fun toggleSaved() {
        val page = applePage ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val save = _saved.value != true
            val pair = runCatching { tokens.read() }.getOrNull() ?: return@launch
            val libraryId = when {
                save -> null
                // an upload or unmatched album is in the library under its own id already
                page.libraryOnly -> page.id
                else -> runCatching { AppleClient(pair).albumLibraryId(page.id) }.getOrNull()
                    ?: run {
                        _saved.value = false
                        return@launch
                    }
            }
            val outcome = AppleLibraryFollow.change(
                context,
                "albums",
                page.id,
                libraryId,
                save,
                AppleKind.ALBUM,
            )
            if (outcome.ok) _saved.value = save
        }
    }

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

    /**
     * An Apple album Apple has no catalogue twin for: the list is what the library holds from it,
     * not the release, and the header says "N songs in your library" rather than "N songs".
     */
    val appleLibraryOnly = MutableStateFlow(false)

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
    private fun loadApple(): Job? {
        val remoteId = appleRemoteId ?: return null
        return viewModelScope.launch(Dispatchers.IO) {
            val pair = tokens.read()
            val page = pair?.let { runCatching { AppleClient(it, pageCache).catalogAlbum(remoteId) }.getOrNull() }
            if (page == null) {
                loadFailed.value = true
                return@launch
            }
            val localAlbumId = albumId
            applePage = page
            _saved.value = page.inLibrary
            appleLibraryOnly.value = page.libraryOnly
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
            appleArtistIdFlow.value = page.artistId
            appleAlbum.value = AlbumWithSongs(album = album, artists = listOf(artist), songs = songs)
        }
    }

    /** True while a pull is re-reading this album: the page's own ring, not the library's. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * Re-reads this album and nothing else. An Apple album drops its page cache row first, so the
     * loader cannot answer itself from disk; a local album has nothing upstream and only re-reads.
     */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_refreshing.value) return@launch
            _refreshing.value = true
            try {
                val remoteId = appleRemoteId
                if (remoteId != null) {
                    runCatching {
                        pageCache.invalidate(PageKind.ALBUM, listOf(remoteId, remoteId.removePrefix("l.")))
                    }
                    loadFailed.value = false
                    loadApple()?.join()
                }
                if (remoteId == null) load()
            } finally {
                _refreshing.value = false
            }
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
