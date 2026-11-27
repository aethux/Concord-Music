// Concord: new — Apple's radio HLS manifests, as this app's player needs to see them.
//
// Probed on the account 2026-11-27 (concord/cider/live-radio-streams.md): a radio manifest carries
// three `#EXT-X-KEY` lines for the same KID — FairPlay, PlayReady and Widevine. The Widevine one is
// `METHOD=SAMPLE-AES` with the key inline as a `data:` URI that already holds a **PSSH box**.
//
// Three things the manifests get wrong for this app:
//  * Media3 takes a Widevine `data:` URI to be a PSSH box, which Apple's is — but this app's song
//    path only ever saw bare key ids, so it rebuilds the box; here Apple's own bytes are kept, so
//    whatever the licence server wants to see in it stays there.
//  * PlayReady and FairPlay lines would give the player two more schemes it cannot use, so only the
//    Widevine line is kept.
//  * A radio **episode**'s init segment declares `tenc` with an all-zero default KID (the live
//    channels' init carries the real one). FairPlay ignores that label; Widevine looks the key up by
//    it and finds nothing — "Provided content key is not in license: key_id = 0000…", seen on the
//    phone 2026-11-27. `patchInit` writes the playlist's KID into the zeros.
package com.mochyy.mochymusic.module.applemusic

import java.net.URI
import java.util.Base64

object AppleRadioHls {

    /** the hosts Apple serves radio manifests and segments from */
    val Hosts = setOf("itsliveradio.apple.com", "liveradio.music.apple.com", "liveradiobackup.music.apple.com")

    fun isPlaylist(host: String?, path: String?): Boolean =
        host in Hosts && path.orEmpty().endsWith(".m3u8", ignoreCase = true)

    /** the Widevine key's `data:` URI as Apple wrote it; the licence body sends this back */
    fun keyUri(playlist: String): String? =
        widevineLine(playlist)?.let { AppleHls.attribute(it, "URI") }

    /** a rewritten manifest, and what the init segment will need to be patched with */
    class Rewritten(
        val playlist: String,
        /** the absolute URL of the `#EXT-X-MAP` init segment, when the manifest names one */
        val initUrl: String?,
        /** the key id the init's `tenc` should name, when it names zeros instead */
        val keyId: ByteArray?,
    )

    fun rewrite(playlist: String, baseUrl: String?): Rewritten {
        val line = widevineLine(playlist) ?: return Rewritten(playlist, null, null)
        val uri = AppleHls.attribute(line, "URI") ?: return Rewritten(playlist, null, null)
        val pssh = psshOf(uri) ?: return Rewritten(playlist, null, null)
        val keyId = AppleHls.keyIdOf(pssh)
        val psshUri = "data:text/plain;base64," + Base64.getEncoder().encodeToString(pssh)
        // Apple's own method: `SAMPLE-AES`, which its segments really are (`schm` says cbcs)
        val method = AppleHls.attribute(line, "METHOD") ?: "SAMPLE-AES"
        val text = playlist.lines().mapNotNull { current ->
            when {
                !current.startsWith("#EXT-X-KEY") -> current
                current == line ->
                    """#EXT-X-KEY:METHOD=$method,URI="$psshUri",KEYFORMAT="${AppleHls.WidevineKeyFormat}",KEYFORMATVERSIONS="1""""
                // the FairPlay and PlayReady copies of the same key: one scheme is all this player has
                else -> null
            }
        }.joinToString("\n")
        val map = Regex("""#EXT-X-MAP:URI="([^"]+)"""").find(playlist)?.groupValues?.getOrNull(1)
        val initUrl = map?.let { runCatching { URI(baseUrl).resolve(it.trim()).toString() }.getOrNull() }
        return Rewritten(playlist = text, initUrl = initUrl, keyId = keyId)
    }

    /**
     * The init segment with its `tenc` default KID filled in, when — and only when — it was zeros.
     * The KID is a lookup label, not key material: the licence grants this very id, and the samples'
     * encryption is untouched.
     */
    fun patchInit(init: ByteArray, keyId: ByteArray): ByteArray {
        if (keyId.size != 16) return init
        val bytes = init.copyOf()
        val tenc = indexOf(bytes, "tenc".toByteArray())
        if (tenc < 0 || tenc + 8 + 20 > bytes.size) return bytes
        val kidStart = tenc + 8 + 4
        val current = bytes.copyOfRange(kidStart, kidStart + 16)
        if (current.any { it != 0.toByte() }) return bytes
        keyId.copyInto(bytes, kidStart)
        return bytes
    }

    private fun widevineLine(playlist: String): String? = playlist.lines().firstOrNull { line ->
        line.startsWith("#EXT-X-KEY") && AppleHls.attribute(line, "KEYFORMAT") == AppleHls.WidevineKeyFormat
    }

    /**
     * The key's PSSH box: Apple's payload when it already is one (radio names its vendor and content
     * id inside it), otherwise a box built around a bare 16-byte key id.
     */
    private fun psshOf(uri: String): ByteArray? {
        val payload = runCatching { Base64.getDecoder().decode(uri.substringAfter(',', "")) }.getOrNull()
            ?: return null
        return when {
            payload.size > 32 && payload.copyOfRange(4, 8).contentEquals("pssh".toByteArray()) -> payload
            payload.size == 16 -> AppleHls.psshFor(payload)
            payload.size > 32 -> AppleHls.psshFor(AppleHls.keyIdOf(payload))
            else -> null
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
