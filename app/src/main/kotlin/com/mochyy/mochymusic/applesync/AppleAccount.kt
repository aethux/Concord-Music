// Concord: the Import & sync page could read the whole library without ever saying whose library it
// was. This reads Apple's own social profile (`/v1/me/social-profile`, the name Cider shows in its
// chip) and can sign the account out again by clearing the token pair the Apple module stores.
package com.mochyy.mochymusic.applesync

import android.content.Context
import com.mochyy.mochymusic.applesync.di.ApplePlaylistEntryPoint
import com.mochyy.mochymusic.importer.account.AccountHttp
import dagger.hilt.android.EntryPointAccessors
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object AppleAccount {

    data class Me(val name: String, val handle: String?)

    private const val Base = "https://amp-api.music.apple.com"
    private const val Path = "/v1/me/social-profile?fields%5Bsocial-profiles%5D=name,handle"

    private suspend fun entry(context: Context): ApplePlaylistEntryPoint? = runCatching {
        EntryPointAccessors.fromApplication(context.applicationContext, ApplePlaylistEntryPoint::class.java)
    }.getOrNull()

    suspend fun signedIn(context: Context): Boolean =
        runCatching { entry(context)?.tokens()?.read() != null }.getOrDefault(false)

    /**
     * The signed-in person, or null when signed out and when the read fails — the page then says
     * "Signed in" without a name rather than claiming there is no account.
     */
    suspend fun me(context: Context): Me? = runCatching {
        val tokens = entry(context)?.tokens()?.read() ?: return null
        val answer = AccountHttp.get(Base + Path, tokens.headers, "Apple Music")
        if (answer.status != 200) return null
        val attributes = AccountHttp.json.parseToJsonElement(answer.body).jsonObject["data"]
            ?.jsonArray?.firstOrNull()?.jsonObject?.get("attributes")?.jsonObject
            ?: return null
        val name = attributes["name"]?.jsonPrimitive?.contentOrNull ?: return null
        Me(name, attributes["handle"]?.jsonPrimitive?.contentOrNull)
    }.getOrNull()

    /** Signs out by removing the token pair; nothing is deleted from the account. */
    suspend fun signOut(context: Context): Boolean = runCatching {
        entry(context)?.tokens()?.clear()
        true
    }.getOrDefault(false)
}
