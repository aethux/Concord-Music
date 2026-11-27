// Concord: new — Apple's Charts page: the genre chips, one genre's charts, and the switch between.
//
// The route asks for a genre (`apple_charts/{genreId}`), and the page the New tab opens passes
// "root": the page resolves that from Apple's own genre list (the first row, "Music") instead of a
// storefront number living in the app. A genre the list does not know falls back to the root, so a
// stale route still draws a page.
package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.applesync.AppleChartGroup
import com.mochyy.mochymusic.applesync.AppleChartsPage
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleGenre
import com.mochyy.mochymusic.applesync.ApplePageCache
import com.mochyy.mochymusic.applesync.AppleTokens
import com.mochyy.mochymusic.playback.queues.AppleStationQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AppleChartsViewModel @Inject constructor(
    private val tokens: AppleTokens,
    private val pageCache: ApplePageCache,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** the genre the route asked for, or "root" — the one Apple's genre list names first */
    private val requested: String = savedStateHandle.get<String>("genreId")
        ?.takeIf { it.isNotBlank() } ?: Root

    /** the chips, root first; null while the genre read is in flight */
    private val _genres = MutableStateFlow<List<AppleGenre>?>(null)
    val genres: StateFlow<List<AppleGenre>?> = _genres.asStateFlow()

    /** one genre's charts; null while its read is in flight */
    private val _page = MutableStateFlow<AppleChartsPage?>(null)
    val page: StateFlow<AppleChartsPage?> = _page.asStateFlow()

    /** the id whose chip is picked */
    private val _selected = MutableStateFlow(requested)
    val selected: StateFlow<String> = _selected.asStateFlow()

    /** a read that failed, told apart from a genre Apple answers with no charts */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** no Apple session at all: the page draws the sign-in prompt instead of its rows */
    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    private var client: AppleClient? = null

    init {
        load()
    }

    fun retry() {
        _failed.value = false
        if (_genres.value == null) load() else loadCharts(_selected.value)
    }

    /** A chip was tapped: the same read, for another genre. A cached genre paints at once. */
    fun select(genreId: String) {
        if (genreId == _selected.value && _page.value != null) return
        _selected.value = genreId
        loadCharts(genreId)
    }

    /** a station card is played, not opened — the charts page has none today, the opener wants one */
    suspend fun stationQueue(id: String): AppleStationQueue? = withContext(Dispatchers.IO) {
        val pair = tokens.read() ?: return@withContext null
        val client = AppleClient(pair)
        AppleStationQueue(client, id, runCatching { client.station(id) }.getOrNull())
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val pair = tokens.read()
            _signedOut.value = pair == null
            if (pair == null) return@launch
            val read = AppleClient(pair, pageCache)
            client = read
            val genres = runCatching { read.genres() }.getOrNull()
            // the chips are the page's only way to another genre, so a failed genre read is a
            // failed page rather than a page with rows and no way to leave them
            if (genres.isNullOrEmpty()) {
                _failed.value = true
                return@launch
            }
            _genres.value = genres
            val root = genres.first().id
            val genre = if (requested == Root || genres.none { it.id == requested }) root else requested
            _selected.value = genre
            loadCharts(genre)
        }
    }

    private fun loadCharts(genreId: String) {
        val read = client ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val root = _genres.value?.firstOrNull()?.id
            // the chips and the rows must agree, so the old genre's rows never sit under a new chip
            _page.value = null
            val page = runCatching {
                read.charts(genreId, withDailyAndCityCharts = genreId == root)
            }.getOrNull()
            _failed.value = page == null
            _page.value = page
        }
    }

    companion object {
        /** what the New tab passes: "the storefront's root genre", resolved from Apple's own list */
        const val Root = "root"
    }
}
