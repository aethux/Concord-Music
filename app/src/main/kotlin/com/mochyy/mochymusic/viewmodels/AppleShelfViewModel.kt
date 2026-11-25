// Concord: new — one Home shelf drawn as its own page.
package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleHomeGroup
import com.mochyy.mochymusic.applesync.AppleReplaySource
import com.mochyy.mochymusic.applesync.AppleReplayYear
import com.mochyy.mochymusic.applesync.AppleTokens
import com.mochyy.mochymusic.db.MusicDatabase
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
class AppleShelfViewModel @Inject constructor(
    private val tokens: AppleTokens,
    /** a card's playlist may already be imported, and the row it opens is that playlist */
    val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel(), AppleReplaySource {

    /** the title Home already had, so the page carries a header before its read lands */
    val title: String = savedStateHandle.get<String>("title").orEmpty()

    private val groupId: String = savedStateHandle.get<String>("groupId").orEmpty()

    /** null while the read is in flight, or when Apple no longer knows this shelf */
    private val _shelf = MutableStateFlow<AppleHomeGroup?>(null)
    val shelf: StateFlow<AppleHomeGroup?> = _shelf.asStateFlow()

    /**
     * One flag for "there is nothing to draw": a failed read and an expired group id look the same
     * to the person, because Apple mints a shelf's id per feed read.
     */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    init {
        load()
    }

    fun retry() {
        _failed.value = false
        load()
    }

    /** a station is played, not opened: its name and art are read here for the queue */
    suspend fun stationQueue(id: String): AppleStationQueue? = withContext(Dispatchers.IO) {
        val pair = tokens.read() ?: return@withContext null
        val client = AppleClient(pair)
        AppleStationQueue(client, id, runCatching { client.station(id) }.getOrNull())
    }

    /** the Replay card's one step: the years Apple keeps, and the playlist each year is */
    override suspend fun replayYears(): List<AppleReplayYear> = withContext(Dispatchers.IO) {
        val pair = tokens.read() ?: return@withContext emptyList()
        runCatching { AppleClient(pair).replayYears() }.getOrNull().orEmpty()
    }

    override suspend fun replayPlaylistId(year: AppleReplayYear): String? = withContext(Dispatchers.IO) {
        val pair = tokens.read() ?: return@withContext null
        runCatching { AppleClient(pair).replayPlaylistId(year.summaryId) }.getOrNull()
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val pair = tokens.read()
            _signedOut.value = pair == null
            if (pair == null) return@launch
            val client = AppleClient(pair)
            var shelf = runCatching { client.homeShelf(groupId) }.getOrNull()
            if (shelf == null) {
                // Apple mints a shelf's id inside the feed response it came with, and Home may be
                // reading a feed it cached minutes ago — so this page can be handed an id that has
                // already expired. One more feed read, then ask for the same shelf by name; its row
                // is a real answer even if the second see-all read fails too.
                val fresh = runCatching { client.homeGroups() }.getOrNull()
                    ?.firstOrNull { it.title.equals(title, ignoreCase = true) }
                if (fresh != null) {
                    shelf = runCatching { client.homeShelf(fresh.id) }.getOrNull() ?: fresh
                }
            }
            _failed.value = shelf == null
            _shelf.value = shelf
        }
    }
}
