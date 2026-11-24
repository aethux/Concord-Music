// Concord: new — the one thing a screen shows when there is no Apple Music session.
package com.mochyy.mochymusic.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mochyy.mochymusic.R

/**
 * The whole page when Apple's content cannot load because nobody is signed in: a centred line of copy
 * and one action. Nothing else is drawn, so a signed-out screen never looks like an empty library.
 */
@Composable
fun AppleSignInPrompt(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.login),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.apple_sign_in_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.apple_sign_in_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onSignIn) {
            Text(stringResource(R.string.apple_sign_in_action))
        }
    }
}
