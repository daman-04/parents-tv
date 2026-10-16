package com.parents.tv.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.parents.tv.data.model.Channel
import com.parents.tv.data.prefs.AppPreferences
import com.parents.tv.data.repository.ChannelRepository
import com.parents.tv.databinding.ActivityMainBinding
import com.parents.tv.player.TvPlayerManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var playerManager: TvPlayerManager
    private lateinit var preferences: AppPreferences
    private lateinit var repository: ChannelRepository

    private val handler = Handler(Looper.getMainLooper())
    private var errorTimer: CountDownTimer? = null
    private var currentChannelIndex = 0
    private var channelList: MutableList<Channel> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = AppPreferences(this)
        repository = ChannelRepository(this, preferences)

        // Initialize Player Manager (uses SurfaceView, fast buffer & 720p cap)
        playerManager = TvPlayerManager(
            context = this,
            playerView = binding.playerView,
            onPlaybackStateChanged = { isPlaying, isBuffering ->
                runOnUiThread {
                    binding.loadingIndicator.visibility = if (isBuffering) View.VISIBLE else View.GONE
                    if (isPlaying) {
                        cancelErrorTimer()
                        binding.errorBannerCard.visibility = View.GONE
                        val currentCh = channelList.getOrNull(currentChannelIndex)
                        if (currentCh != null) {
                            repository.recordChannelSuccess(currentCh.id)
                        }
                    }
                }
            },
            onPlaybackError = { errorMsg ->
                runOnUiThread {
                    handleChannelFailure()
                }
            }
        )

        // Load channels immediately from cache or bundled assets (<15ms, never blocks)
        val initialFeed = repository.loadInitialFeed()
        channelList = repository.getAllChannels().toMutableList()

        // Check network state
        val isOnline = repository.isNetworkAvailable()
        binding.offlineBanner.visibility = if (!isOnline) View.VISIBLE else View.GONE

        // Restore last channel or fallback to default Punjabi channel
        val savedId = preferences.lastChannelId ?: initialFeed.defaultChannel
        val foundIndex = channelList.indexOfFirst { it.id == savedId }
        currentChannelIndex = if (foundIndex != -1) {
            foundIndex
        } else {
            preferences.lastChannelIndex.coerceIn(0, (channelList.size - 1).coerceAtLeast(0))
        }

        // Start video immediately on launch before any grid loading
        playChannel(currentChannelIndex)

        // Background update check if online (> 24 hours stale)
        if (isOnline) {
            lifecycleScope.launch {
                val hasUpdate = repository.refreshFeedIfStale()
                if (hasUpdate) {
                    val freshChannels = repository.getAllChannels()
                    if (freshChannels.isNotEmpty()) {
                        val activeChId = channelList.getOrNull(currentChannelIndex)?.id
                        channelList.clear()
                        channelList.addAll(freshChannels)
                        if (activeChId != null) {
                            val newIdx = channelList.indexOfFirst { it.id == activeChId }
                            if (newIdx != -1) currentChannelIndex = newIdx
                        }
                    }
                }
            }
        }
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

        if (channel.streams.isNotEmpty()) {
            playerManager.playStreams(channel.streams)
        } else {
            handleChannelFailure()
        }
    }

    private fun showChannelInfo(channel: Channel, index: Int) {
        binding.channelBadge.text = "CH ${index + 1}"
        binding.channelName.text = channel.name
        binding.channelCategory.text = channel.category?.uppercase() ?: "LIVE TV"

        // "New" badge indicator
        binding.channelNewBadge.visibility = if (channel.isNew) View.VISIBLE else View.GONE

        // Channel logo via Coil (fast, memory-cached, light)
        if (!channel.logo.isNullOrEmpty()) {
            binding.channelLogo.visibility = View.VISIBLE
            binding.channelLogo.load(channel.logo) {
                crossfade(false)
            }
        } else {
            binding.channelLogo.visibility = View.GONE
        }

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

        val currentCh = channelList.getOrNull(currentChannelIndex)
        if (currentCh != null) {
            repository.recordChannelFailure(currentCh.id)
        }

        cancelErrorTimer()
        // PRD Section 5.1 & 6.4: If channel fails, move to next channel after 5 seconds
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
                val channel = channelList.getOrNull(currentChannelIndex)
                if (channel != null) {
                    showChannelInfo(channel, currentChannelIndex)
                }
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
