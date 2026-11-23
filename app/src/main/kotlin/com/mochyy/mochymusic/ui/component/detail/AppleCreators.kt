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
 * Apple's people for a playlist, and whether they are still on their way. null means the question
 * does not apply — not an Apple id — so the caller keeps whatever credit it already had.
 */
data class ApplePeople(
    val loading: Boolean,
    val people: List<CreatorUi>,
)

@Composable
fun rememberApplePeople(remoteId: String?): ApplePeople? {
    val context = LocalContext.current
    val appleId = remoteId?.takeIf { it.startsWith("p.") || it.startsWith("pl.") }
    var people by remember(remoteId) { mutableStateOf<List<CreatorUi>?>(null) }

    LaunchedEffect(appleId) {
        val id = appleId ?: return@LaunchedEffect
        // A page opened seconds after a cold start can find the Apple session still loading, and a
        // single failed read would leave the header crediting one person for as long as the page is
        // open. Three goes, then it settles on what the import recorded.
        repeat(3) { attempt ->
            people = withContext(Dispatchers.IO) {
                val tokens = EntryPointAccessors
                    .fromApplication(context.applicationContext, AppleCreatorsEntryPoint::class.java)
                    .appleTokens()
                val pair = tokens.read() ?: return@withContext emptyList<CreatorUi>()
                val playlist = runCatching { AppleClient(pair).playlistPage(id) }.getOrNull()
                    ?: return@withContext emptyList<CreatorUi>()
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
            if (!people.isNullOrEmpty()) return@LaunchedEffect
            kotlinx.coroutines.delay(900L * (attempt + 1))
        }
        // three goes and still nothing: an empty list is a real answer, and stops the header waiting
        if (people == null) people = emptyList()
    }

    if (appleId == null) return null
    return people?.let { ApplePeople(loading = false, people = it) }
        ?: ApplePeople(loading = true, people = emptyList())
}


/**
 * An Apple artist's picture by name, for the places that only carry the name: the song details
 * sheet credits whoever made the song, and an Apple row keeps no artwork of its own.
 */
private val appleAvatarCache = mutableMapOf<String, String?>()

@Composable
fun rememberAppleArtistAvatar(artistName: String?): String? {
    val context = LocalContext.current
    var url by remember(artistName) {
        mutableStateOf(artistName?.let { appleAvatarCache[it] })
    }

    LaunchedEffect(artistName) {
        val name = artistName?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (appleAvatarCache.containsKey(name)) {
            url = appleAvatarCache[name]
            return@LaunchedEffect
        }
        val fetched = withContext(Dispatchers.IO) {
            val tokens = EntryPointAccessors
                .fromApplication(context.applicationContext, AppleCreatorsEntryPoint::class.java)
                .appleTokens()
            val pair = tokens.read() ?: return@withContext null
            val client = AppleClient(pair)
            val id = runCatching { client.artistIdByName(name) }.getOrNull() ?: return@withContext null
            runCatching { client.artistPage(id)?.artworkUrl }.getOrNull()
                ?.let { AppleCovers.sized(it, 300) }
        }
        appleAvatarCache[name] = fetched
        url = fetched
    }

    return url
}
