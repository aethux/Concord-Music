// Concord: new — Apple's Browse ("New") page, and one section's room, from the same reader.
//
// Two routes share this view model: the tab's page (no `roomId`) and the room a section's "View all"
// opens (`roomId` in the route), so the room's title is on screen before its read lands and the page
// has one loading/error path instead of two.
package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.applesync.AppleBrowseRoom
import com.mochyy.mochymusic.applesync.AppleBrowseSection
import com.mochyy.mochymusic.applesync.AppleClient
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
class AppleBrowseViewModel @Inject constructor(
    private val tokens: AppleTokens,
    private val pageCache: ApplePageCache,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** set on a room's route only; the page itself has none */
    private val roomId: String? = savedStateHandle.get<String>("roomId")?.takeIf { it.isNotBlank() }

    /** the title the row carried, so a room draws its header before the read lands */
    val title: String = savedStateHandle.get<String>("title").orEmpty()

    /** null while the read is in flight; empty when Apple sent no section this app can draw */
    private val _sections = MutableStateFlow<List<AppleBrowseSection>?>(null)
    val sections: StateFlow<List<AppleBrowseSection>?> = _sections.asStateFlow()

    /** the room, once read */
    private val _room = MutableStateFlow<AppleBrowseRoom?>(null)
    val room: StateFlow<AppleBrowseRoom?> = _room.asStateFlow()

    /** a read that failed, told apart from a page Apple returned empty */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** no Apple session at all: the page draws the sign-in prompt instead of its rows */
    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    init {
        load()
    }

    fun retry() {
        _failed.value = false
        load()
    }

    /** a station is played, not opened; the queue reads its first page and keeps asking for more */
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
            val client = AppleClient(pair, pageCache)
            val room = roomId
            if (room != null) {
                val read = runCatching { client.browseRoom(room) }.getOrNull()
                _failed.value = read == null
                _room.value = read
            } else {
                val read = runCatching { client.browseSections() }.getOrNull()
                _failed.value = read == null
                _sections.value = read.orEmpty()
            }
        }
    }
}
