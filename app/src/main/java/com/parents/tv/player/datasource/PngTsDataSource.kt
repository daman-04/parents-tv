package com.parents.tv.player.datasource

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Custom ExoPlayer DataSource that intercepts HLS transport stream segments disguised as PNG images
 * (used by TikTok CDN / DaddyLive sports relays), unmasks them using PngTsUnmasker,
 * and feeds pure MPEG-TS packets (0x47 sync bytes) directly into ExoPlayer.
 *
 * All non-PNG requests (playlists, audio, normal TS chunks) are streamed through transparently.
 */
class PngTsDataSource(
    private val upstream: DataSource
) : DataSource {

    class Factory(
        private val upstreamFactory: DataSource.Factory
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return PngTsDataSource(upstreamFactory.createDataSource())
        }
    }

    private var activeStream: ByteArrayInputStream? = null
    private var isIntercepted = false
    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        this.uri = dataSpec.uri
        val urlStr = dataSpec.uri.toString()

        // Playlists (.m3u8) must NEVER be intercepted or buffered as static arrays
        if (urlStr.contains(".m3u8")) {
            isIntercepted = false
            activeStream = null
            return upstream.open(dataSpec)
        }

        // Check if this request is a media segment disguised as PNG image
        val isDisguisedPng = urlStr.contains(".image") ||
                urlStr.contains("tiktokcdn") ||
                urlStr.contains("edge.cowedd4855ws") ||
                urlStr.contains("/dl/")

        if (!isDisguisedPng) {
            isIntercepted = false
            activeStream = null
            return upstream.open(dataSpec)
        }

        // Open upstream
        upstream.open(dataSpec)

        // Read up to the first 8 bytes to verify if it is a PNG
        val buffer = ByteArray(32768)
        val baos = ByteArrayOutputStream()
        var read: Int
        while (upstream.read(buffer, 0, buffer.size).also { read = it } != C.RESULT_END_OF_INPUT) {
            baos.write(buffer, 0, read)
        }
        val rawData = baos.toByteArray()

        if (PngTsUnmasker.isPng(rawData)) {
            val startTime = System.currentTimeMillis()
            val unmasked = PngTsUnmasker.unmask(rawData)
            val elapsed = System.currentTimeMillis() - startTime

            if (unmasked != null && unmasked.isNotEmpty() && unmasked[0] == 0x47.toByte()) {
                Log.d(TAG, "Unmasked ${rawData.size}B PNG -> ${unmasked.size}B TS in ${elapsed}ms")
                isIntercepted = true
                val bais = ByteArrayInputStream(unmasked)
                if (dataSpec.position > 0) {
                    bais.skip(dataSpec.position)
                }
                activeStream = bais
                val remaining = unmasked.size.toLong() - dataSpec.position
                return if (remaining >= 0) remaining else C.LENGTH_UNSET.toLong()
            } else {
                Log.w(TAG, "Failed to unmask PNG segment (${rawData.size}B)")
            }
        }

        // Not PNG or unmasking failed: serve raw downloaded data
        isIntercepted = true
        val bais = ByteArrayInputStream(rawData)
        if (dataSpec.position > 0) {
            bais.skip(dataSpec.position)
        }
        activeStream = bais
        val remaining = rawData.size.toLong() - dataSpec.position
        return if (remaining >= 0) remaining else C.LENGTH_UNSET.toLong()
    }

    @Throws(IOException::class)
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        val stream = activeStream
        if (isIntercepted && stream != null) {
            val bytesRead = stream.read(target, offset, length)
            return if (bytesRead == -1) C.RESULT_END_OF_INPUT else bytesRead
        }
        return upstream.read(target, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri ?: uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        activeStream = null
        isIntercepted = false
        try {
            upstream.close()
        } catch (ignored: Exception) {
        }
    }

    companion object {
        private const val TAG = "PngTsDataSource"
    }
}
