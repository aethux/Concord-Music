// Concord: new — one shape for a sign-in page opened over the app.
package com.mochyy.mochymusic.ui.screens.setup.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.importer.account.AccountImportSession
import com.mochyy.mochymusic.importer.account.AccountService
import com.mochyy.mochymusic.ui.screens.settings.importsync.CaptureWebContent
import com.mochyy.mochymusic.ui.screens.settings.importsync.onSignInTick

/** the import browser's own grey, so every sign-in surface matches */
private val BrowserSurface = Color(0xFF1C1C1C)

/**
 * A sign-in page as a sheet: one bar — icon, title, address, close — over the page, on the import
 * browser's grey, with nothing behind it and no second handle.
 *
 * This is the module sign-in's shape, and it is used by every sign-in the app opens over itself: the
 * import sheet opened from a signed-out page and the setup wizard's first step. Three surfaces, one
 * look — the browser's own two-part header belongs to the onboarding's provider flow, not here.
 *
 * [url] is the bar's address line and the caller owns it, so a page change shows live. [topInset]
 * lifts the content out from under a sheet's own handle row when this sits inside one.
 */
@Composable
internal fun ImportSignInWeb(
    service: AccountService,
    session: AccountImportSession,
    presence: Float,
    url: String,
    onUrlChange: (String) -> Unit,
    onClose: () -> Unit,
    topInset: Dp = 0.dp,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = presence }
            .background(BrowserSurface)
            .padding(top = topInset),
    ) {
        BrowserSheetBar(
            icon = { ProviderSquircle(service, size = 38.dp) },
            title = stringResource(R.string.onb_sign_in_to, service.label),
            url = url,
            onClose = onClose,
        )
        Spacer(Modifier.height(12.dp))
        CaptureWebContent(
            startUrl = service.loginUrl.orEmpty(),
            playerOrigins = service.playerOrigins,
            desktopSite = service.desktopSite,
            onHeader = session::onHeader,
            onTick = { view -> service.onSignInTick(view, session::onApplePageProbe, session::onCookies) },
            onExit = onClose,
            onUrlChange = onUrlChange,
            // tall enough for Apple's whole sign-in, short enough to stay a sheet
            modifier = Modifier
                .fillMaxWidth()
                .height((LocalConfiguration.current.screenHeightDp * 0.78f).dp),
        )
    }
}
