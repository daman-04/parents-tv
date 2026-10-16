package com.parents.tv.player

import android.content.Context
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

@OptIn(UnstableApi::class)
class TvPlayerManager(
    private val context: Context,
    private val playerView: PlayerView,
    private val onPlaybackStateChanged: (isPlaying: Boolean, isBuffering: Boolean) -> Unit,
    private val onPlaybackError: (errorMsg: String) -> Unit
) {
    private var exoPlayer: ExoPlayer? = null
    private val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0 (Linux; Android 9; Fire TV)")
        .setConnectTimeoutMs(8000)
        .setReadTimeoutMs(8000)
        .setAllowCrossProtocolRedirects(true)

    init {
        initPlayer()
    }

    private fun initPlayer() {
        // Fast buffer tuning for older Fire TV Stick: start fast, low memory consumption
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2000,
                /* maxBufferMs = */ 6000,
                /* bufferForPlaybackMs = */ 600,
                /* bufferForPlaybackAfterRebufferMs = */ 1200
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Cap maximum resolution to 720p (1280x720) to save decode CPU and memory
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
                        onPlaybackError(message)
                    }
                })

        exoPlayer = player
        playerView.player = player
    }

    fun playStream(streamUrl: String) {
        val player = exoPlayer ?: return

        // Swap the stream on the existing player instance - never recreate player
        val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(streamUrl))

        player.setMediaSource(mediaSource)
        player.prepare()
        player.play()
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
