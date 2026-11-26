// Concord: new — the Search tab's Apple state.
//
// Three reads, each with its own lifetime: Browse Categories once when the page opens, the terms
// under the typed text after a pause, and one catalogue answer per submitted term. A newer term
// cancels an older one (`collectLatest`), so a slow answer can never land under a fresher one.
package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleSearchCategory
import com.mochyy.mochymusic.applesync.AppleSearchItem
import com.mochyy.mochymusic.applesync.AppleSearchReader
import com.mochyy.mochymusic.applesync.AppleSearchSection
import com.mochyy.mochymusic.applesync.AppleTokens
import com.mochyy.mochymusic.applesync.db.AppleSyncDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One submitted term's answer. [term] is what the rows belong to, so a stale answer is visible. */
data class AppleSearchResults(
    val term: String = "",
    val loading: Boolean = false,
    val failed: Boolean = false,
    val signedOut: Boolean = false,
    val sections: List<AppleSearchSection> = emptyList(),
    /** catalogue ids the library already holds; only songs can be told, the sidecar stores songs */
    val owned: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = !loading && !failed && !signedOut && sections.isEmpty()
}

@OptIn(FlowPreview::class)
@HiltViewModel
class AppleSearchViewModel @Inject constructor(
    private val tokens: AppleTokens,
    private val appleDao: AppleSyncDao,
) : ViewModel() {

    /** the empty field's landing; null until the first read answers */
    private val _categories = MutableStateFlow<List<AppleSearchCategory>?>(null)
    val categories: StateFlow<List<AppleSearchCategory>?> = _categories.asStateFlow()

    /** no Apple session: the landing asks to sign in instead of drawing tiles */
    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _results = MutableStateFlow(AppleSearchResults())
    val results: StateFlow<AppleSearchResults> = _results.asStateFlow()

    private val typed = MutableStateFlow("")
    /** the submitted term and the attempt number, so asking again for the same term still asks */
    private val submitted = MutableStateFlow("" to 0)

    init {
        loadCategories()
        watchTyping()
        watchSubmissions()
    }

    /** what is in the field right now: fetched as suggestions once typing pauses */
    fun onTyped(text: String) {
        typed.value = text
    }

    /** a submitted term: the catalogue answer this page draws */
    fun search(term: String) {
        submitted.value = term to (submitted.value.second + 1)
    }

    /** the same term again: a read that failed is worth one more ask */
    fun retry() {
        submitted.value = submitted.value.first to (submitted.value.second + 1)
    }

    fun loadCategories() {
        viewModelScope.launch(Dispatchers.IO) {
            val pair = tokens.read()
            _signedOut.value = pair == null
            if (pair == null) {
                _categories.value = emptyList()
                return@launch
            }
            val page = runCatching { AppleClient(pair).searchLanding() }.getOrNull()
            _categories.value = page?.let(AppleSearchReader::categories).orEmpty()
        }
    }

    fun retryCategories() {
        _categories.value = null
        loadCategories()
    }

    private fun watchTyping() {
        viewModelScope.launch(Dispatchers.IO) {
            typed
                .map { it.trim() }
                .debounce(SuggestionDelayMs)
                .distinctUntilChanged()
                .collectLatest { text ->
                    if (text.isEmpty()) {
                        _suggestions.value = emptyList()
                        return@collectLatest
                    }
                    val pair = tokens.read()
                    if (pair == null) {
                        _suggestions.value = emptyList()
                        return@collectLatest
                    }
                    val page = runCatching { AppleClient(pair).searchSuggestions(text) }.getOrNull()
                    _suggestions.value = page?.let(AppleSearchReader::suggestions).orEmpty()
                }
        }
    }

    private fun watchSubmissions() {
        viewModelScope.launch(Dispatchers.IO) {
            submitted.collectLatest { (raw, _) ->
                val term = raw.trim()
                if (term.isEmpty()) {
                    _results.value = AppleSearchResults()
                    return@collectLatest
                }
                _results.value = AppleSearchResults(term = term, loading = true)
                val pair = tokens.read()
                if (pair == null) {
                    _results.value = AppleSearchResults(term = term, signedOut = true)
                    return@collectLatest
                }
                val client = AppleClient(pair)
                // two reads side by side: Apple drops videos from a search that also asks for songs
                val (page, videoPage) = coroutineScope {
                    val songs = async { runCatching { client.searchPage(term) }.getOrNull() }
                    val videos = async { runCatching { client.searchVideos(term) }.getOrNull() }
                    songs.await() to videos.await()
                }
                if (page == null) {
                    _results.value = AppleSearchResults(term = term, failed = true)
                    return@collectLatest
                }
                val sections = run {
                    val audio = AppleSearchReader.sections(page)
                    val videos = videoPage?.let(AppleSearchReader::sections).orEmpty()
                    val merged = if (videos.isEmpty()) {
                        audio
                    } else {
                        // the videos ride directly above the playlists, as they do in Apple's own
                        // All view; the audio call's own order is otherwise left alone
                        val at = audio.indexOfFirst { it.kind == AppleSearchItem.Kind.Playlist }
                        if (at < 0) audio + videos else audio.take(at) + videos + audio.drop(at)
                    }
                    // Songs first and Artists straight after them, then Apple's own order for the
                    // rest: a search is about a recording or a name before it is about a release.
                    // The sort is stable, so Albums, Videos and Playlists keep Apple's order.
                    merged.sortedBy { section ->
                        when (section.kind) {
                            AppleSearchItem.Kind.Song -> 0
                            AppleSearchItem.Kind.Artist -> 1
                            else -> 2
                        }
                    }
                }
                val songIds = sections
                    .firstOrNull { it.kind == AppleSearchItem.Kind.Song }
                    ?.items?.map { it.id }.orEmpty()
                val owned = if (songIds.isEmpty()) {
                    emptySet()
                } else {
                    runCatching { appleDao.catalogIdsInLibrary(songIds).toSet() }
                        .getOrDefault(emptySet())
                }
                _results.value = AppleSearchResults(
                    term = term,
                    sections = sections,
                    owned = owned,
                )
            }
        }
    }

    private companion object {
        /** long enough that a word in progress is one read, short enough to feel live */
        const val SuggestionDelayMs = 300L
    }
}
