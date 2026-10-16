package com.parents.tv.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.parents.tv.data.model.Stream
import com.parents.tv.player.datasource.PngTsDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class TvPlayerManager(
    private val context: Context,
    private val playerView: PlayerView,
    private val onPlaybackStateChanged: (isPlaying: Boolean, isBuffering: Boolean) -> Unit,
    private val onPlaybackError: (errorMsg: String) -> Unit
) {
    companion object {
        private const val TAG = "TvPlayerManager"
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(10, 3, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var exoPlayer: ExoPlayer? = null
    private var currentStreams: List<Stream> = emptyList()
    private var currentStreamIndex = 0
    private var behindLiveWindowRetries = 0
    private var streamRetryCount = 0
    private var playJob: Job? = null

    init {
        initPlayer()
    }

    private fun createDataSourceFactory(url: String): DataSource.Factory {
        val factory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")

        val headers = mutableMapOf<String, String>()
        if (url.contains("damitv.st")) {
            headers["Referer"] = "https://damitv.st/"
            headers["Origin"] = "https://damitv.st"
        } else if (url.contains("dlive.sx") || url.contains("dembed.st") || url.contains("cowedd4855ws.sbs")) {
            headers["Referer"] = "https://dlive.sx/"
        }
        if (headers.isNotEmpty()) {
            factory.setDefaultRequestProperties(headers)
        }
        return PngTsDataSource.Factory(factory)
    }

    private fun initPlayer() {
        // Optimized buffer for Live TV: prevents lag/stutter while maintaining fast channel startup
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 12000,
                /* maxBufferMs = */ 25000,
                /* bufferForPlaybackMs = */ 1200,
                /* bufferForPlaybackAfterRebufferMs = */ 2500
            )
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val trackParameters = TrackSelectionParameters.Builder(context)
            .setMaxVideoSize(1920, 1080)
            .setPreferredTextLanguage("pa")
            .build()

        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()

        player.trackSelectionParameters = trackParameters
        player.playWhenReady = true
        player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> onPlaybackStateChanged(false, true)
                    Player.STATE_READY -> onPlaybackStateChanged(true, false)
                    Player.STATE_ENDED -> onPlaybackStateChanged(false, false)
                    Player.STATE_IDLE -> onPlaybackStateChanged(false, false)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlaybackStateChanged(isPlaying, exoPlayer?.playbackState == Player.STATE_BUFFERING)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
                    error.cause is androidx.media3.exoplayer.source.BehindLiveWindowException) {
                    if (behindLiveWindowRetries < 2) {
                        behindLiveWindowRetries++
                        Log.i(TAG, "Behind live window on link $currentStreamIndex, re-seeking to live edge (attempt $behindLiveWindowRetries)...")
                        exoPlayer?.seekToDefaultPosition()
                        exoPlayer?.prepare()
                        return
                    }
                    Log.w(TAG, "Exceeded live window re-seek attempts on link $currentStreamIndex, failing over...")
                } else if (streamRetryCount < 2 && (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                           error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                           error.cause is IOException)) {
                    streamRetryCount++
                    Log.w(TAG, "Transient network glitch on link $currentStreamIndex, retrying (attempt $streamRetryCount)...")
                    playerView.postDelayed({
                        exoPlayer?.prepare()
                        exoPlayer?.play()
                    }, 400)
                    return
                }
                val message = error.localizedMessage ?: "Playback error"
                Log.w(TAG, "Stream error on link $currentStreamIndex: $message")
                handleStreamFailure(message)
            }
        })

        exoPlayer = player
        playerView.player = player
    }

    fun playStreams(streams: List<Stream>) {
        playJob?.cancel()
        currentStreams = streams
        currentStreamIndex = 0
        behindLiveWindowRetries = 0
        streamRetryCount = 0
        tryPlayCurrentStream()
    }

    private fun tryPlayCurrentStream() {
        val player = exoPlayer ?: return
        behindLiveWindowRetries = 0
        streamRetryCount = 0
        if (currentStreams.isEmpty() || currentStreamIndex >= currentStreams.size) {
            onPlaybackError("No working stream links available")
            return
        }

        val rawUrl = currentStreams[currentStreamIndex].url
        Log.d(TAG, "Playing stream link [$currentStreamIndex/${currentStreams.size}]: $rawUrl")

        playJob?.cancel()
        playJob = CoroutineScope(Dispatchers.Main).launch {
            val resolvedUrl = withContext(Dispatchers.IO) {
                resolveStreamUrl(rawUrl)
            }

            if (exoPlayer != player) return@launch

            val streamFactory = createDataSourceFactory(resolvedUrl)
            val mediaSource = HlsMediaSource.Factory(streamFactory)
                .setAllowChunklessPreparation(false)
                .createMediaSource(MediaItem.fromUri(resolvedUrl))

            player.setMediaSource(mediaSource)
            player.prepare()
            player.play()
        }
    }

    private fun resolveStreamUrl(url: String): String {
        if (!url.contains("damitv.st") || url.contains("tk=")) {
            return url
        }
        val token = fetchDamitvToken()
        return if (token != null) {
            val sep = if (url.contains("?")) "&" else "?"
            "$url${sep}tk=${token.first}&e=${token.second}"
        } else {
            url
        }
    }

    private var cachedDamitvToken: Pair<String, Long>? = null

    private fun fetchDamitvToken(): Pair<String, Long>? {
        val now = System.currentTimeMillis() / 1000
        val current = cachedDamitvToken
        if (current != null && current.second > now + 300) {
            return current
        }
        return try {
            val conn = java.net.URL("https://damitv.st/papi/hls-token").openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            conn.setRequestProperty("Referer", "https://damitv.st/")
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(body)
                val tk = json.getString("tk")
                val e = json.getLong("e")
                val pair = tk to e
                cachedDamitvToken = pair
                pair
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch damitv hls-token: ${e.message}")
            null
        }
    }

    private fun handleStreamFailure(errorMsg: String) {
        currentStreamIndex++
        if (currentStreamIndex < currentStreams.size) {
            Log.d(TAG, "Trying backup stream link #$currentStreamIndex...")
            tryPlayCurrentStream()
        } else {
            onPlaybackError(errorMsg)
        }
    }

    fun getStreamServerStatus(): Pair<Int, Int> {
        val total = currentStreams.size.coerceAtLeast(1)
        val current = (currentStreamIndex + 1).coerceAtMost(total)
        return current to total
    }

    fun cycleStreamServer(): Pair<Int, Int> {
        if (currentStreams.size <= 1) return 1 to 1
        currentStreamIndex = (currentStreamIndex + 1) % currentStreams.size
        tryPlayCurrentStream()
        return (currentStreamIndex + 1) to currentStreams.size
    }

    fun setAspectRatioMode(mode: Int) {
        playerView.resizeMode = when (mode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    fun getAspectRatioName(mode: Int): String {
        return when (mode) {
            1 -> "Zoom (Crop)"
            2 -> "Stretch (16:9)"
            else -> "Fit (Auto 4:3 / 16:9)"
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun resume() {
        exoPlayer?.play()
    }

    fun release() {
        playJob?.cancel()
        playJob = null
        exoPlayer?.release()
        exoPlayer = null
        playerView.player = null
    }
}
