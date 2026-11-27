// Concord: new — Apple's Widevine licence request body, in one place.
//
// The song path (AppleMusicModule.acquireLicense) and the radio tiers send the same six fields; only
// the host differs (`play.itunes.apple.com` for songs, a station's own `keyServerUrl` for radio).
// Keeping the shape here means the two cannot drift.
package com.mochyy.mochymusic.module.applemusic

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

object AppleLicence {

    fun body(challenge: ByteArray, keyUri: String, adamId: String): ByteArray = buildJsonObject {
        put("challenge", Base64.getEncoder().encodeToString(challenge))
        put("key-system", "com.widevine.alpha")
        put("uri", keyUri)
        put("adamId", adamId)
        put("isLibrary", false)
        put("user-initiated", true)
    }.toString().toByteArray()
}
