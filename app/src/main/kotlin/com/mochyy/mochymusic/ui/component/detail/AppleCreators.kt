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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.component.ListDialog
import com.mochyy.mochymusic.ui.component.ListItem
import com.mochyy.mochymusic.ui.component.detail.CreatorUi
import com.mochyy.mochymusic.applesync.AppleClient
import com.mochyy.mochymusic.applesync.AppleCollaborator
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.ApplePageCache
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
    fun pageCache(): ApplePageCache
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
                val entry = EntryPointAccessors
                    .fromApplication(context.applicationContext, AppleCreatorsEntryPoint::class.java)
                val pair = entry.appleTokens().read() ?: return@withContext emptyList<CreatorUi>()
                val playlist = runCatching { AppleClient(pair, entry.pageCache()).playlistPage(id) }.getOrNull()
                    ?: return@withContext emptyList<CreatorUi>()
                (listOfNotNull(playlist.owner) + playlist.collaborators)
                    .distinctBy { it.handle ?: it.name }
                    .map { person ->
                        CreatorUi(
                            name = person.name,
                            avatarUrl = person.avatarUrl?.let { url -> AppleCovers.sized(url, 160) },
                            artistId = null,
                            appleProfileId = person.id,
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
            val entry = EntryPointAccessors
                .fromApplication(context.applicationContext, AppleCreatorsEntryPoint::class.java)
            val pair = entry.appleTokens().read() ?: return@withContext null
            val client = AppleClient(pair, entry.pageCache())
            val id = runCatching { client.artistIdByName(name) }.getOrNull() ?: return@withContext null
            runCatching { client.artistPage(id)?.artworkUrl }.getOrNull()
                ?.let { AppleCovers.sized(it, 300) }
        }
        appleAvatarCache[name] = fetched
        url = fetched
    }

    return url
}

/**
 * Who added each song on a shared playlist, keyed by the **catalogue** song id.
 *
 * The local page is the one people actually open for their own playlists, and Apple answers this
 * only on the catalogue representation — the library one has no per-track meta at all. It is the same
 * read [rememberApplePeople] makes, so the page cache keeps both for the same six hours.
 */
@Composable
fun rememberAppleAddedBy(remoteId: String?): Map<String, AppleCollaborator> {
    val context = LocalContext.current
    val appleId = remoteId?.takeIf { it.startsWith("p.") || it.startsWith("pl.") }
    var addedBy by remember(remoteId) { mutableStateOf<Map<String, AppleCollaborator>>(emptyMap()) }

    LaunchedEffect(appleId) {
        val id = appleId ?: return@LaunchedEffect
        repeat(2) { attempt ->
            addedBy = withContext(Dispatchers.IO) {
                val entry = EntryPointAccessors
                    .fromApplication(context.applicationContext, AppleCreatorsEntryPoint::class.java)
                val pair = entry.appleTokens().read() ?: return@withContext emptyMap()
                runCatching { AppleClient(pair, entry.pageCache()).playlistPage(id) }
                    .getOrNull()?.addedBy.orEmpty()
            }
            if (addedBy.isNotEmpty()) return@LaunchedEffect
            kotlinx.coroutines.delay(900L * (attempt + 1))
        }
    }
    return addedBy
}

/**
 * A row's adder, at its trailing edge: the face only, 18 dp, so it reads as a mark rather than a
 * control. Each person's picture is the 500 px avatar Apple attaches to their profile.
 */
@Composable
fun AppleAddedByAvatar(avatarUrl: String?, onClick: (() -> Unit)? = null) {
    val base = Modifier
        .size(18.dp)
        .clip(CircleShape)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    val sized = avatarUrl?.let { AppleCovers.sized(it, 36) }
    if (sized != null) {
        AsyncImage(
            model = sized,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = base,
        )
        return
    }
    // a person Apple names but draws no picture for (a private profile): a glyph, not a blank row
    Box(
        contentAlignment = Alignment.Center,
        modifier = base.background(Color.White.copy(alpha = 0.16f)),
    ) {
        Icon(
            painter = painterResource(R.drawable.person),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(11.dp),
        )
    }
}

/**
 * The people on a playlist, as Mochy lists a choice: one row each, a chevron, and the row opens the
 * person in this app. The faces used to hand the whole row to Apple's website, which Android resolves
 * to the Apple Music app — the choice belongs here instead.
 */
@Composable
fun AppleCreatorsSheet(
    people: List<CreatorUi>,
    onDismiss: () -> Unit,
    onPersonClick: (String) -> Unit,
) {
    ListDialog(onDismiss = onDismiss) {
        items(items = people, key = { it.name }) { person ->
            val profileId = person.appleProfileId
            ListItem(
                title = person.name,
                modifier = Modifier.clickable(enabled = profileId != null) {
                    if (profileId != null) {
                        onDismiss()
                        onPersonClick(profileId)
                    }
                },
                thumbnailContent = {
                    AsyncImage(
                        model = person.avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f)),
                    )
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}
