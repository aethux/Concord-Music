// LocalPlaylistViewModel.kt

package com.mochyy.mochymusic.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.constants.HideVideoSongsKey
import com.mochyy.mochymusic.constants.PlaylistSongSortDescendingKey
import com.mochyy.mochymusic.constants.PlaylistSongSortType
import com.mochyy.mochymusic.constants.PlaylistSongSortTypeKey
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.entities.PlaylistSong
import com.mochyy.mochymusic.extensions.reversed
import com.mochyy.mochymusic.extensions.toEnum
import com.mochyy.mochymusic.playlistcover.PlaylistCovers
import com.mochyy.mochymusic.utils.SyncUtils
import com.mochyy.mochymusic.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LocalPlaylistViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
    private val tokens: com.mochyy.mochymusic.applesync.AppleTokens,
    private val pageCache: com.mochyy.mochymusic.applesync.ApplePageCache,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val database = database

    val playlistId = savedStateHandle.get<String>("playlistId")!!

    /** True while a pull is re-reading this playlist from Apple: the page's own ring. */
    private val _refreshing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val refreshing: kotlinx.coroutines.flow.StateFlow<Boolean> = _refreshing

    /**
     * A pull on this page re-reads this playlist and nothing else, Cider's per-page refresh: Apple's
     * copy of it is fetched again and this playlist's own rows are rewritten in Apple's order.
     */
    fun refreshFromApple(remoteId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_refreshing.value) return@launch
            _refreshing.value = true
            try {
                com.mochyy.mochymusic.applesync.ApplePlaylistRefresh(database, tokens, pageCache)
                    .run(playlistId, remoteId)
            } catch (e: Exception) {
                timber.log.Timber.tag("PlaylistRefresh").w(e, "could not re-read %s", remoteId)
            } finally {
                _refreshing.value = false
            }
        }
    }
    val playlist =
        database
            .playlist(playlistId)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // where an imported playlist came from, for crediting whoever made it there
    val imported =
        database
            .importedPlaylist(playlistId)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val playlistSongs: StateFlow<List<PlaylistSong>> =
        combine(
            database.playlistSongs(playlistId),
            context.dataStore.data
                .map {
                    Triple(
                        it[PlaylistSongSortTypeKey].toEnum(PlaylistSongSortType.CUSTOM),
                        it[PlaylistSongSortDescendingKey] ?: true,
                        it[HideVideoSongsKey] ?: false
                    )
                }.distinctUntilChanged(),
        ) { songs, (sortType, sortDescending, hideVideoSongs) ->
            val filteredSongs = if (hideVideoSongs) {
                songs.filter { !it.song.song.isVideo }
            } else {
                songs
            }
            when (sortType) {
                PlaylistSongSortType.CUSTOM -> filteredSongs
                PlaylistSongSortType.CREATE_DATE -> filteredSongs.sortedBy { it.map.id }
                PlaylistSongSortType.NAME -> {
                    val collator = Collator.getInstance(Locale.getDefault())
                    collator.strength = Collator.PRIMARY
                    filteredSongs.sortedWith(compareBy(collator) { it.song.song.title })
                }
                PlaylistSongSortType.ARTIST -> {
                    val collator = Collator.getInstance(Locale.getDefault())
                    collator.strength = Collator.PRIMARY
                    filteredSongs
                        .sortedWith(compareBy(collator) { song -> song.song.artists.joinToString("") { it.name } })
                        .groupBy { it.song.album?.title }
                        .flatMap { (_, songsByAlbum) ->
                            songsByAlbum.sortedBy {
                                it.song.artists.joinToString(
                                    ""
                                ) { it.name }
                            }
                        }
                }

                PlaylistSongSortType.PLAY_TIME -> filteredSongs.sortedBy { it.song.song.totalPlayTime }
            }.reversed(sortDescending && sortType != PlaylistSongSortType.CUSTOM)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {

            playlist.first { it != null }?.playlist?.browseId?.let { browseId ->
                syncUtils.syncPlaylist(browseId, playlistId)
            }
        }

        // A generated cover takes its colours from the playlist's top song and carries its title:
        // when either changes it is drawn again. Any other cover - a picture the user chose, a
        // remote playlist's own image, the collage of songs - is left exactly as it is.
        viewModelScope.launch {
            combine(playlist.filterNotNull(), database.playlistSongs(playlistId)) { entity, songs ->
                val top = songs.minWithOrNull(compareBy({ it.map.position }, { it.map.id }))?.song
                Triple(entity.playlist, top?.id, top?.song?.thumbnailUrl)
            }
                .distinctUntilChanged()
                .collect { (entity, topSongId, artworkUrl) ->
                    PlaylistCovers.refreshIfStale(context, database, entity, topSongId, artworkUrl)
                }
        }

        viewModelScope.launch {
            val sortedSongs =
                playlistSongs.first().sortedWith(compareBy({ it.map.position }, { it.map.id }))
            database.transaction {
                sortedSongs.forEachIndexed { index, playlistSong ->
                    if (playlistSong.map.position != index) {
                        update(playlistSong.map.copy(position = index))
                    }
                }
            }
        }
    }
}
