// Concord: new — one Apple profile and the playlists it shares, read once per page.
package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.ApplePageCache
import com.mochyy.mochymusic.applesync.ApplePersonProfile
import com.mochyy.mochymusic.applesync.AppleTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppleProfileViewModel @Inject constructor(
    private val tokens: AppleTokens,
    private val pageCache: ApplePageCache,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** the `sp.…` id from the route */
    private val profileId: String = savedStateHandle.get<String>("profileId")!!

    private val _profile = MutableStateFlow<ApplePersonProfile?>(null)
    val profile: StateFlow<ApplePersonProfile?> = _profile.asStateFlow()

    /** true only when Apple answered nothing at all — a private profile is a 404, not a failure */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    init {
        load()
    }

    fun retry() {
        _failed.value = false
        load()
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val pair = tokens.read()
            val remote = pair?.let { runCatching { AppleClient(it, pageCache).socialProfile(profileId) }.getOrNull() }
            if (remote == null) {
                _failed.value = true
                return@launch
            }
            _profile.value = remote
        }
    }
}
