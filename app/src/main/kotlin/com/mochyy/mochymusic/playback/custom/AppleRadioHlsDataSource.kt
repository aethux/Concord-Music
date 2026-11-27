// Concord: new — Apple's radio manifests and init segments, fixed as they are loaded.
//
// Two things in Apple's radio bytes need correcting before the player can decrypt them
// (concord/cider/live-radio-streams.md, probed on the account 2026-11-27):
//
//  * the manifest's Widevine key line is rewritten through `AppleRadioHls` so Media3 sees one scheme
//    and Apple's own PSSH box;
//  * a **radio episode**'s init segment names its key with an all-zero `tenc` default KID, which the
//    CDM cannot match against the licence. The playlist's KID is written into the zeros.
//
// A finite episode could be fixed once, but a live station's playlist is a window re-fetched every
// few seconds, so both fixes happen on the way in. Everything else — segments, songs, downloads —
// passes straight through. The playlist and its init arrive on different DataSource instances, so
// the map between them lives on the factory.
package com.mochyy.mochymusic.playback.custom

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.mochyy.mochymusic.module.applemusic.AppleRadioHls
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
class AppleRadioHlsDataSource(
    private val upstream: DataSource,
    /** init segment URL -> the key id its `tenc` should carry, written by the playlist that named it */
    private val initKeyIds: ConcurrentHashMap<String, ByteArray>,
) : DataSource {

    private var opened: DataSource? = null
    /** the bytes to serve instead of the network's, when this request was one of the two fixed ones */
    private var buffered: ByteArray? = null
    private var position = 0
    private var currentUri: Uri? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        buffered = null
        position = 0
        opened = upstream
        val length = upstream.open(dataSpec)
        responseHeaders = upstream.responseHeaders

        val keyId = initKeyIds[dataSpec.uri.toString()]
        when {
            // an init segment whose tenc named zeros
            keyId != null -> {
                buffered = AppleRadioHls.patchInit(upstream.readAll(), keyId)
            }
            // a manifest: rewrite its key line, and remember the KID its init segment will need
            AppleRadioHls.isPlaylist(dataSpec.uri.host, dataSpec.uri.path) -> {
                val rewritten = AppleRadioHls.rewrite(upstream.readAll().decodeToString(), dataSpec.uri.toString())
                val initUrl = rewritten.initUrl
                val kId = rewritten.keyId
                if (initUrl != null && kId != null) initKeyIds[initUrl] = kId
                buffered = rewritten.playlist.toByteArray()
            }
            else -> return length
        }

        upstream.close()
        opened = null
        return buffered!!.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytes = buffered ?: return checkNotNull(opened) { "read before open" }.read(buffer, offset, length)
        if (position >= bytes.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, bytes.size - position)
        System.arraycopy(bytes, position, buffer, offset, count)
        position += count
        return count
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    override fun close() {
        try {
            opened?.close()
        } finally {
            opened = null
            buffered = null
            position = 0
            responseHeaders = emptyMap()
        }
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        private val initKeyIds = ConcurrentHashMap<String, ByteArray>()

        override fun createDataSource(): DataSource =
            AppleRadioHlsDataSource(upstream.createDataSource(), initKeyIds)
    }
}

/** a playlist or an init segment is a few kilobytes; the rewriter needs the whole response */
private fun DataSource.readAll(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (true) {
        val read = read(chunk, 0, chunk.size)
        if (read == C.RESULT_END_OF_INPUT) break
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}
