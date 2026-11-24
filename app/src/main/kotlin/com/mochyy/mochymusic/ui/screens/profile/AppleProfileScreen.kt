// Concord: new — one person on Apple Music: their face, their handle and the playlists they share.
//
// Cider's resource (`apple-api-reference.md` §A) gives exactly this: the profile attributes and
// `relationships["shared-playlists"]`. There is no follower count to draw — `?include=followers`
// answers 400 — so the page shows what Apple actually publishes and nothing invented.
package com.mochyy.mochymusic.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.applesync.AppleCovers
import com.mochyy.mochymusic.applesync.ApplePlaylistRef
import com.mochyy.mochymusic.ui.utils.backToMain
import com.mochyy.mochymusic.viewmodels.AppleProfileViewModel

@Composable
fun AppleProfileScreen(
    navController: NavController,
    viewModel: AppleProfileViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val uriHandler = LocalUriHandler.current
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + 24.dp

    Box(Modifier.fillMaxSize()) {
        when {
            profile == null && failed -> Centered {
                // Apple handed the playlist this person's name and picture, then answered 404 for
                // their page: show what we have and say plainly why there is no more
                val face = AppleCovers.sized(viewModel.fallbackArtworkUrl, 500)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(132.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f)),
                ) {
                    if (face != null) {
                        AsyncImage(
                            model = face,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        // Apple named this person but sent no picture: a glyph, not an empty circle
                        Icon(
                            painter = painterResource(R.drawable.person),
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(52.dp),
                        )
                    }
                }
                if (viewModel.fallbackName.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = viewModel.fallbackName,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.apple_profile_private),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            profile == null -> Centered {
                Text(
                    text = stringResource(R.string.apple_profile_loading),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            else -> {
                val person = profile!!
                LazyColumn(
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 128.dp, bottom = bottom),
                ) {
                    item(key = "header") {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val face = AppleCovers.sized(person.artworkUrl, 500)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(132.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f)),
                            ) {
                                if (face != null) {
                                    AsyncImage(
                                        model = face,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.person),
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.size(52.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = person.name,
                                color = Color.White,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val handle = person.handle
                            if (handle != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "@$handle",
                                    color = Color.White.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.clickable {
                                        uriHandler.openUri(person.url ?: "https://music.apple.com/profile/$handle")
                                    },
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = if (person.playlists.isEmpty()) {
                                    stringResource(R.string.apple_profile_no_playlists)
                                } else {
                                    pluralStringResource(
                                        R.plurals.apple_profile_playlists,
                                        person.playlists.size,
                                        person.playlists.size,
                                    )
                                },
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(26.dp))
                        }
                    }
                    items(person.playlists, key = { it.id }) { ref ->
                        SharedPlaylistRow(
                            ref = ref,
                            onClick = { navController.navigate("apple_playlist/${ref.id}") },
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = { if (!navController.navigateUp()) navController.backToMain() },
            modifier = Modifier.padding(start = 8.dp, top = 48.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.arrow_back),
                contentDescription = stringResource(R.string.action_back),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun SharedPlaylistRow(ref: ApplePlaylistRef, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        AsyncImage(
            model = AppleCovers.sized(ref.artworkUrl, 120),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = ref.name,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOfNotNull(
                ref.curator,
                ref.trackCount?.let { count -> pluralStringResource(R.plurals.n_song, count, count) },
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { content() }
    }
}
