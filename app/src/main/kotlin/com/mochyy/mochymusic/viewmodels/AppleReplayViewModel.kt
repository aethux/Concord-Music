// Concord: new — Apple's Replay: the years it keeps a summary for, and the playlist each one is.
package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleReplayYear
import com.mochyy.mochymusic.applesync.AppleTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AppleReplayViewModel @Inject constructor(
    private val tokens: AppleTokens,
) : ViewModel() {

    /** null while the read is in flight */
    private val _years = MutableStateFlow<List<AppleReplayYear>?>(null)
    val years: StateFlow<List<AppleReplayYear>?> = _years.asStateFlow()

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

    /**
     * The playlist a year is, read when its row is tapped rather than for every year up front: the
     * year list is one request, and a person opens one year.
     */
    suspend fun playlistIdOf(year: AppleReplayYear): String? = withContext(Dispatchers.IO) {
        val pair = tokens.read() ?: return@withContext null
        runCatching { AppleClient(pair).replayPlaylistId(year.summaryId) }.getOrNull()
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val pair = tokens.read()
            _signedOut.value = pair == null
            if (pair == null) return@launch
            val years = runCatching { AppleClient(pair).replayYears() }.getOrNull()
            _failed.value = years == null
            _years.value = years.orEmpty()
        }
    }
}
