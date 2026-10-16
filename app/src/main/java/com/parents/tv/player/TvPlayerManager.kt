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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.parents.tv.data.model.Stream

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

    private var exoPlayer: ExoPlayer? = null
    private var currentStreams: List<Stream> = emptyList()
    private var currentStreamIndex = 0

    private val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0 (Linux; Android 9; Fire TV)")
        .setConnectTimeoutMs(7000)
        .setReadTimeoutMs(7000)
        .setAllowCrossProtocolRedirects(true)

    init {
        initPlayer()
    }

    private fun initPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2000,
                /* maxBufferMs = */ 6000,
                /* bufferForPlaybackMs = */ 600,
                /* bufferForPlaybackAfterRebufferMs = */ 1200
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val trackParameters = TrackSelectionParameters.Builder(context)
            .setMaxVideoSize(1280, 720)
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
                val message = error.localizedMessage ?: "Playback error"
                Log.w(TAG, "Stream error on link $currentStreamIndex: $message")
                handleStreamFailure(message)
            }
        })

        exoPlayer = player
        playerView.player = player
    }

    fun playStreams(streams: List<Stream>) {
        currentStreams = streams
        currentStreamIndex = 0
        tryPlayCurrentStream()
    }

    private fun tryPlayCurrentStream() {
        val player = exoPlayer ?: return
        if (currentStreams.isEmpty() || currentStreamIndex >= currentStreams.size) {
            onPlaybackError("No working stream links available")
            return
        }

        val url = currentStreams[currentStreamIndex].url
        Log.d(TAG, "Playing stream link [$currentStreamIndex/${currentStreams.size}]: $url")

        val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(url))

        player.setMediaSource(mediaSource)
        player.prepare()
        player.play()
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

    fun pause() {
        exoPlayer?.pause()
    }

    fun resume() {
        exoPlayer?.play()
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        playerView.player = null
    }
}
