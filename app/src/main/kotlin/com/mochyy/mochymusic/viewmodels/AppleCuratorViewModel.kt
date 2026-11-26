// Concord: new — a genre curator's page: the playlists Apple publishes for one "Browse Categories"
// tile, read once and kept while the page is open.
package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleCuratorPlaylist
import com.mochyy.mochymusic.applesync.AppleCuratorReader
import com.mochyy.mochymusic.applesync.AppleTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppleCuratorViewModel @Inject constructor(
    private val tokens: AppleTokens,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val curatorId: String = savedStateHandle.get<String>("curatorId").orEmpty()

    /** the tile's own label, which the search landing already sent — no second request for a name */
    val title: String = savedStateHandle.get<String>("title").orEmpty()

    /** null while the read is in flight; empty when the curator has nothing published */
    private val _playlists = MutableStateFlow<List<AppleCuratorPlaylist>?>(null)
    val playlists: StateFlow<List<AppleCuratorPlaylist>?> = _playlists.asStateFlow()

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

    private fun load() {
        if (curatorId.isBlank()) {
            _failed.value = true
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val pair = tokens.read()
            _signedOut.value = pair == null
            if (pair == null) return@launch
            val page = runCatching { AppleClient(pair).curatorPlaylists(curatorId) }.getOrNull()
            _failed.value = page == null
            _playlists.value = page?.let(AppleCuratorReader::playlists).orEmpty()
        }
    }
}
