// Concord: new — every road to an artist ends on the page that can answer for them.
//
// The app links artists by id from a dozen places: album creators, song menus, the player's own
// artist rows, the library, search. A YouTube id belongs to Mochy's artist screen; an Apple id to
// Apple's. Where the id is Apple's but not in Apple's form (library rows keep generated or channel
// ids), the artist's songs say where they came from, and their name finds Apple's id.
package com.mochyy.mochymusic.ui.screens.artist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleTokens
import com.mochyy.mochymusic.db.MusicDatabase
import androidx.compose.material3.TopAppBarScrollBehavior
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Which page an artist id belongs to. */
sealed interface ArtistTarget {
    data object Resolving : ArtistTarget
    /** Apple's own id, ready for apple_artist/{id} */
    data class Apple(val appleId: String) : ArtistTarget
    /** Mochy's YouTube artist screen */
    data object YouTube : ArtistTarget
}

@HiltViewModel
class ArtistRouteViewModel @Inject constructor(
    private val database: MusicDatabase,
    private val tokens: AppleTokens,
) : ViewModel() {

    private val _target = MutableStateFlow<ArtistTarget>(ArtistTarget.Resolving)
    val target: StateFlow<ArtistTarget> = _target.asStateFlow()

    fun resolve(rawId: String) {
        viewModelScope.launch {
            _target.value = resolveBlocking(rawId)
        }
    }

    private suspend fun resolveBlocking(rawId: String): ArtistTarget = withContext(Dispatchers.IO) {
        // an Apple artist id is numeric; that is the common case and needs no lookup
        if (rawId.isNotEmpty() && rawId.all(Char::isDigit)) return@withContext ArtistTarget.Apple(rawId)

        // the id may be a row id (a channel or a generated one) or, for songs this app made up,
        // the artist's name itself
        val row = runCatching { database.getArtistById(rawId) }.getOrNull()
        val name = row?.name ?: rawId.takeIf { candidate -> candidate.any { it.isLetter() } }
            ?: return@withContext ArtistTarget.YouTube
        val hasApple = runCatching {
            if (row != null) database.hasAppleTracks(row.id) else database.hasAppleTracksByName(name)
        }.getOrDefault(false)
        if (!hasApple) return@withContext ArtistTarget.YouTube

        val pair = tokens.read() ?: return@withContext ArtistTarget.YouTube
        val appleId = runCatching { AppleClient(pair).artistIdByName(name) }.getOrNull()
        if (appleId != null) ArtistTarget.Apple(appleId) else ArtistTarget.YouTube
    }
}

/**
 * The artist destination's front door: resolves the id, then hands over to whichever artist page can
 * answer for it. An Apple artist replaces this entry, so Back leaves the page rather than bouncing.
 */
@Composable
fun ArtistRoute(
    rawId: String,
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    if (rawId.isNotEmpty() && rawId.all(Char::isDigit)) {
        AppleArtistScreen(navController)
        return
    }

    val viewModel: ArtistRouteViewModel = hiltViewModel()
    val target by viewModel.target.collectAsState()
    LaunchedEffect(rawId) { viewModel.resolve(rawId) }

    when (val state = target) {
        ArtistTarget.Resolving -> Box(modifier = Modifier.fillMaxSize())
        is ArtistTarget.Apple -> LaunchedEffect(state.appleId) {
            navController.navigate("apple_artist/${state.appleId}") {
                popUpTo("artist/{artistId}") { inclusive = true }
            }
        }
        ArtistTarget.YouTube -> ArtistScreen(navController, scrollBehavior)
    }
}
