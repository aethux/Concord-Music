// Concord: new. Debug-only inspector: one persistent WebView on music.apple.com, kept alive so the
// page's own surface can be enumerated over the DevTools protocol (concord/tools/apple_probe.py).
// Lives in the debug source set, so it is never in a release build. It shares the app's WebView
// cookie jar, so it opens already signed in to whatever account the import screen signed into.
package com.mochyy.mochymusic

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import com.mochyy.mochymusic.importer.account.DESKTOP_USER_AGENT

class AppleInspectorActivity : Activity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = DESKTOP_USER_AGENT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = WebViewClient()
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        val address = EditText(this).apply {
            setSingleLine()
            setText(intent.getStringExtra("url") ?: "https://music.apple.com/")
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(address, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(
                Button(this@AppleInspectorActivity).apply {
                    text = "Go"
                    setOnClickListener { web.loadUrl(address.text.toString()) }
                },
            )
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            },
        )

        web.loadUrl(address.text.toString())
    }
}
