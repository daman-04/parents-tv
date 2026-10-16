package com.parents.tv.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.parents.tv.data.model.Channel
import com.parents.tv.data.model.Stream
import com.parents.tv.data.prefs.AppPreferences
import com.parents.tv.databinding.ActivityMainBinding
import com.parents.tv.player.TvPlayerManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var playerManager: TvPlayerManager
    private lateinit var preferences: AppPreferences

    private val handler = Handler(Looper.getMainLooper())
    private var errorTimer: CountDownTimer? = null
    private var currentChannelIndex = 0

    // Initial reliable channels for Step 4 (Punjabi first)
    private val channelList = listOf(
        Channel(
            id = "DDPunjabi.in",
            name = "DD Punjabi",
            category = "Punjabi Live",
            streams = listOf(
                Stream("https://d3qs3d2rkhfqrt.cloudfront.net/out/v1/da821c24a59d4e57960497aeaca8fb33/index.m3u8")
            )
        ),
        Channel(
            id = "GTCPunjabi.in",
            name = "GTC Punjabi",
            category = "Punjabi Live",
            streams = listOf(
                Stream("https://cdn.ottlive.co.in/gtcpunjabi/index.m3u8")
            )
        ),
        Channel(
            id = "AkaalChannel.uk",
            name = "Akaal Channel",
            category = "Punjabi Devotional",
            streams = listOf(
                Stream("https://cdn-globecast.akamaized.net/live/eds/akaal_tv/hls1_smart_akaal/akaal_tv.m3u8")
            )
        ),
        Channel(
            id = "9XTashan.in",
            name = "9X Tashan",
            category = "Punjabi Music",
            streams = listOf(
                Stream("https://amg01281-9xmediapvtltd-9xtashan-samsungin-xz1sd.amagi.tv/playlist/amg01281-9xmediapvtltd-9xtashan-samsungin/playlist.m3u8")
            )
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = AppPreferences(this)

        // Initialize Player Manager (uses SurfaceView & 720p cap)
        playerManager = TvPlayerManager(
            context = this,
            playerView = binding.playerView,
            onPlaybackStateChanged = { isPlaying, isBuffering ->
                runOnUiThread {
                    binding.loadingIndicator.visibility = if (isBuffering) View.VISIBLE else View.GONE
                    if (isPlaying) {
                        cancelErrorTimer()
                        binding.errorBannerCard.visibility = View.GONE
                    }
                }
            },
            onPlaybackError = { errorMsg ->
                runOnUiThread {
                    handleChannelFailure()
                }
            }
        )

        // Restore last channel
        val savedId = preferences.lastChannelId
        val foundIndex = channelList.indexOfFirst { it.id == savedId }
        currentChannelIndex = if (foundIndex != -1) foundIndex else preferences.lastChannelIndex.coerceIn(0, channelList.size - 1)

        // Start video immediately on launch before any other UI
        playChannel(currentChannelIndex)
    }

    private fun playChannel(index: Int) {
        if (channelList.isEmpty()) return

        currentChannelIndex = (index + channelList.size) % channelList.size
        val channel = channelList[currentChannelIndex]

        // Persist last played channel immediately
        preferences.lastChannelId = channel.id
        preferences.lastChannelIndex = currentChannelIndex

        cancelErrorTimer()
        binding.errorBannerCard.visibility = View.GONE
        binding.loadingIndicator.visibility = View.VISIBLE

        showChannelInfo(channel, currentChannelIndex)

        val streamUrl = channel.streams.firstOrNull()?.url
        if (!streamUrl.isNullOrEmpty()) {
            playerManager.playStream(streamUrl)
        } else {
            handleChannelFailure()
        }
    }

    private fun showChannelInfo(channel: Channel, index: Int) {
        binding.channelBadge.text = "CH ${index + 1}"
        binding.channelName.text = channel.name
        binding.channelCategory.text = channel.category?.uppercase() ?: "LIVE TV"

        binding.channelInfoCard.visibility = View.VISIBLE
        binding.channelInfoCard.alpha = 1f

        handler.removeCallbacks(hideInfoRunnable)
        handler.postDelayed(hideInfoRunnable, 3500)
    }

    private val hideInfoRunnable = Runnable {
        binding.channelInfoCard.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction { binding.channelInfoCard.visibility = View.GONE }
            .start()
    }

    private fun handleChannelFailure() {
        binding.loadingIndicator.visibility = View.GONE
        binding.errorBannerCard.visibility = View.VISIBLE

        cancelErrorTimer()
        // PRD Section 5.1: If channel fails, move to next channel after 5 seconds
        errorTimer = object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000) + 1
                binding.errorCountdownText.text = "Switching to next channel in $secondsLeft seconds..."
            }

            override fun onFinish() {
                binding.errorBannerCard.visibility = View.GONE
                nextChannel()
            }
        }.start()
    }

    private fun cancelErrorTimer() {
        errorTimer?.cancel()
        errorTimer = null
    }

    private fun nextChannel() {
        playChannel(currentChannelIndex + 1)
    }

    private fun prevChannel() {
        playChannel(currentChannelIndex - 1)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                prevChannel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                nextChannel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val channel = channelList[currentChannelIndex]
                showChannelInfo(channel, currentChannelIndex)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (binding.channelInfoCard.visibility == View.VISIBLE) {
                    binding.channelInfoCard.visibility = View.GONE
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        playerManager.resume()
    }

    override fun onStop() {
        super.onStop()
        playerManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelErrorTimer()
        handler.removeCallbacks(hideInfoRunnable)
        playerManager.release()
    }
}
