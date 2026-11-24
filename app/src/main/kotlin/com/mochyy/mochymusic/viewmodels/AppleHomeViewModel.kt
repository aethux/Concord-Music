// Concord: new — Home's Apple tab: Apple's Listen Now groups, read once and kept while the screen is open.
package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleHomeGroup
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
class AppleHomeViewModel @Inject constructor(
    private val tokens: AppleTokens,
    private val pageCache: ApplePageCache,
) : ViewModel() {

    /** null while the first read is in flight; empty when Apple sent nothing. */
    private val _groups = MutableStateFlow<List<AppleHomeGroup>?>(null)
    val groups: StateFlow<List<AppleHomeGroup>?> = _groups.asStateFlow()

    /** a read that failed, told apart from an account with no recommendations */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** no Apple session at all: the page draws the sign-in prompt instead of its shelves */
    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    init {
        load()
    }

    fun retry() {
        _failed.value = false
        load()
    }

    /**
     * A station is played, not opened: Apple's own app starts one on tap. The queue reads its first
     * page and keeps asking for more, so the name and art are read here too.
     */
    suspend fun stationQueue(id: String): AppleStationQueue? = withContext(Dispatchers.IO) {
        val pair = tokens.read() ?: return@withContext null
        val client = AppleClient(pair)
        AppleStationQueue(client, id, runCatching { client.station(id) }.getOrNull())
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val pair = tokens.read()
            _signedOut.value = pair == null
            val groups = pair?.let { runCatching { AppleClient(it, pageCache).homeGroups() }.getOrNull() }
            _failed.value = groups == null
            _groups.value = groups.orEmpty()
        }
    }
}
