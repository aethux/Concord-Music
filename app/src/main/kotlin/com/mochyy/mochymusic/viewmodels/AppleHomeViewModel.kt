// Concord: new — Home's Apple tab: Apple's Listen Now groups, read once and kept while the screen is open.
package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleHomeGroup
import com.mochyy.mochymusic.applesync.AppleTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppleHomeViewModel @Inject constructor(
    private val tokens: AppleTokens,
) : ViewModel() {

    /** null while the first read is in flight; empty when Apple sent nothing. */
    private val _groups = MutableStateFlow<List<AppleHomeGroup>?>(null)
    val groups: StateFlow<List<AppleHomeGroup>?> = _groups.asStateFlow()

    /** a read that failed, told apart from an account with no recommendations */
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
            val groups = pair?.let { runCatching { AppleClient(it).homeGroups() }.getOrNull() }
            _failed.value = groups == null
            _groups.value = groups.orEmpty()
        }
    }
}
