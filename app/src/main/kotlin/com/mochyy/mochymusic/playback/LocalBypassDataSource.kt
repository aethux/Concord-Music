// LocalBypassDataSource.kt

package com.mochyy.mochymusic.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** reads a song that's already a file on the phone straight from that file, and sends everything else through the song caches */
@UnstableApi
class LocalBypassDataSource(
    private val local: DataSource,
    private val cached: DataSource,
) : DataSource {

    private var opened: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener)
        cached.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (isOnPhone(dataSpec.uri) || isStationStream(dataSpec) || isAppleRadio(dataSpec.uri)) local else cached
        opened = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(opened) { "read before open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = opened?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = opened?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            opened?.close()
        } finally {
            opened = null
        }
    }

    class Factory(
        private val local: DataSource.Factory,
        private val cached: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = LocalBypassDataSource(local.createDataSource(), cached.createDataSource())
    }

    companion object {
        // a picked file, a folder's song or a shared copy: caching them only made a second copy of the user's music
        fun isOnPhone(uri: Uri): Boolean = when (uri.scheme) {
            "content", "file", "android.resource", "asset" -> true
            else -> false
        }

        /**
         * A station's own stream: a live one never ends, so writing it into a cache would grow
         * until the cache evicted the songs the user saved. The service marks the request with
         * [com.mochyy.mochymusic.playback.queues.StationStreamQueue.CacheKeyPrefix].
         */
        fun isStationStream(dataSpec: DataSpec): Boolean =
            dataSpec.key?.startsWith(com.mochyy.mochymusic.playback.queues.StationStreamQueue.CacheKeyPrefix) == true

        /**
         * Apple's radio hosts serve the manifest and its segments. Nothing under them is cached: a
         * live window has to come from the network on every refresh, or it would never move.
         */
        fun isAppleRadio(uri: Uri): Boolean = uri.host in com.mochyy.mochymusic.module.applemusic.AppleRadioHls.Hosts
    }
}
