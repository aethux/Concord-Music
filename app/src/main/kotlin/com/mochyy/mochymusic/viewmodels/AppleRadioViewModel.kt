// Concord: new — the Radio page: Apple's radio section tree, read once and kept while the page is
// open. Two routes share it — the page itself and a section's own page, which reads the same cached
// tree, so opening a section costs no request.
package com.mochyy.mochymusic.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.ApplePageCache
import com.mochyy.mochymusic.applesync.AppleRadioItem
import com.mochyy.mochymusic.applesync.AppleRadioSection
import com.mochyy.mochymusic.applesync.AppleTokens
import com.mochyy.mochymusic.applesync.RadioFavorites
import com.mochyy.mochymusic.playback.queues.AppleStationQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AppleRadioViewModel @Inject constructor(
    private val tokens: AppleTokens,
    private val pageCache: ApplePageCache,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** null while the read is in flight; empty when Apple sent no section this app can draw. */
    private val _sections = MutableStateFlow<List<AppleRadioSection>?>(null)
    val sections: StateFlow<List<AppleRadioSection>?> = _sections.asStateFlow()

    /** a read that failed, told apart from a page Apple returned empty */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** no Apple session at all: the page draws the sign-in prompt instead of its sections */
    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    /** the stations kept on top, newest first — this phone's, not the account's */
    val favorites: StateFlow<List<AppleRadioItem>> = RadioFavorites.rows(context)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleFavorite(item: AppleRadioItem) {
        viewModelScope.launch(Dispatchers.IO) { RadioFavorites.toggle(context, item) }
    }

    /** Apple's `inFavorites` for a card that has one — an album or a playlist, never a station */
    suspend fun favorite(type: String, id: String): Boolean? = withContext(Dispatchers.IO) {
        val pair = tokens.read() ?: return@withContext null
        runCatching { AppleClient(pair, pageCache).favorite(type, id) }.getOrNull()
    }

    /** an artist's radio: the seeded station the artist page starts */
    suspend fun artistRadio(artistId: String): AppleStationQueue? = withContext(Dispatchers.IO) {
        AppleStationQueue.seeded(tokens, "ra.$artistId")
    }

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
            val sections = runCatching { AppleClient(pair, pageCache).radioSections() }.getOrNull()
            _failed.value = sections == null
            _sections.value = sections.orEmpty()
        }
    }
}
