// Concord: new — the people on an Apple playlist, for the pages that are not Apple's own.
//
// A playlist imported from Apple opens as a local playlist, which is right: it is editable, its
// tracks are in the library, and it is the page the rest of the app links to. What it did not have
// was the people Apple knows about — the owner's profile and any collaborators — so it credits a
// name and no faces. Apple's social-profiles carry a 500px avatar each; this reads them once.
package com.mochyy.mochymusic.ui.component.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.AppleTokens
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppleCreatorsEntryPoint {
    fun appleTokens(): AppleTokens
}

/**
 * The owner and collaborators of an Apple playlist, as header creators. Empty for anything that is
 * not an Apple id, and empty while it loads, so the header keeps its existing credit until then.
 */
@Composable
fun rememberAppleCreators(remoteId: String?): List<CreatorUi> {
    val context = LocalContext.current
    var people by remember(remoteId) { mutableStateOf(emptyList<CreatorUi>()) }

    LaunchedEffect(remoteId) {
        val id = remoteId ?: return@LaunchedEffect
        if (!id.startsWith("p.") && !id.startsWith("pl.")) return@LaunchedEffect
        people = withContext(Dispatchers.IO) {
            val tokens = EntryPointAccessors
                .fromApplication(context.applicationContext, AppleCreatorsEntryPoint::class.java)
                .appleTokens()
            val pair = tokens.read() ?: return@withContext emptyList()
            val playlist = runCatching { AppleClient(pair).playlistPage(id) }.getOrNull()
                ?: return@withContext emptyList()
            (listOfNotNull(playlist.owner) + playlist.collaborators)
                .distinctBy { it.handle ?: it.name }
                .map { person ->
                    CreatorUi(
                        name = person.name,
                        avatarUrl = person.avatarUrl?.let { url -> AppleCovers.sized(url, 160) },
                        artistId = null,
                    )
                }
        }
    }

    return people
}
