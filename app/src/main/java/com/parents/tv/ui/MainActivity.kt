package com.parents.tv.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.parents.tv.data.model.Channel
import com.parents.tv.data.prefs.AppPreferences
import com.parents.tv.data.repository.ChannelRepository
import com.parents.tv.data.search.SearchMatcher
import com.parents.tv.databinding.ActivityMainBinding
import com.parents.tv.player.TvPlayerManager
import com.parents.tv.ui.adapter.CategoryTab
import com.parents.tv.ui.adapter.CategoryTabAdapter
import com.parents.tv.ui.adapter.ChannelAdapter
import com.parents.tv.updater.AppUpdater
import com.parents.tv.updater.UpdateCheckResult
import com.parents.tv.updater.UpdateInfo
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var playerManager: TvPlayerManager
    private lateinit var preferences: AppPreferences
    private lateinit var repository: ChannelRepository

    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var categoryTabAdapter: CategoryTabAdapter

    private val appUpdater = AppUpdater()
    private var activeUpdateInfo: UpdateInfo? = null
    private var isDownloadingUpdate = false

    private val handler = Handler(Looper.getMainLooper())
    private var errorTimer: CountDownTimer? = null
    private var currentChannelIndex = 0
    private var previousChannelIndex = -1
    private var isManuallyPaused = false
    private var channelList: MutableList<Channel> = mutableListOf()
    private var activeCategoryTabId = "punjabi"
    private var isGridMode = true
    private var isReorderMode = false
    private var pickedReorderChannel: Channel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = AppPreferences(this)
        repository = ChannelRepository(this, preferences)
        isGridMode = preferences.viewMode == "grid"

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
            onPlaybackError = {
                runOnUiThread {
                    handleChannelFailure()
                }
            }
        )

        // Restore saved aspect ratio mode (0: Fit, 1: Zoom, 2: Stretch)
        playerManager.setAspectRatioMode(preferences.aspectRatioMode)
        updateAspectRatioButtonText()

        // Load channels immediately from cache or bundled assets (<15ms, never blocks)
        val initialFeed = repository.loadInitialFeed()
        channelList = repository.getAllChannels().toMutableList()
        applyCustomOrder()

        // Sync favorite states from preferences
        val favoriteIds = preferences.getFavoriteChannelIds()
        channelList.forEach { ch ->
            ch.isFavorite = favoriteIds.contains(ch.id)
        }

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

        // Setup the Channel Menu & Grid Overlay
        setupChannelMenu()

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
                        val currentFavs = preferences.getFavoriteChannelIds()
                        channelList.clear()
                        freshChannels.forEach { ch ->
                            ch.isFavorite = currentFavs.contains(ch.id)
                            channelList.add(ch)
                        }
                        applyCustomOrder()
                        if (activeChId != null) {
                            val newIdx = channelList.indexOfFirst { it.id == activeChId }
                            if (newIdx != -1) currentChannelIndex = newIdx
                        }
                        refreshCategoryTabs()
                    }
                }
            }
        }

        // Automatic background app update check after 10 seconds
        if (isOnline) {
            handler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    checkAndPromptAppUpdate(isManual = false)
                }
            }, 10000)
        }
    }

    private fun setupChannelMenu() {
        // Channel Adapter setup
        channelAdapter = ChannelAdapter(
            channels = emptyList(),
            isGridMode = isGridMode,
            onChannelClick = { channel, _ ->
                if (isReorderMode) {
                    handleReorderChannelClick(channel)
                } else {
                    val targetIndex = channelList.indexOfFirst { it.id == channel.id }
                    if (targetIndex != -1) {
                        playChannel(targetIndex)
                    }
                    hideChannelMenu()
                }
            },
            onChannelLongClick = { channel, _ ->
                toggleFavoriteChannel(channel)
                true
            }
        )

        updateRecyclerViewLayoutManager()
        binding.channelsRecyclerView.adapter = channelAdapter

        // Category Tabs Setup
        categoryTabAdapter = CategoryTabAdapter(
            tabs = emptyList(),
            selectedIndex = 0,
            onTabClick = { tab, _ ->
                activeCategoryTabId = tab.id
                binding.searchInput.setText("")
                loadChannelsForActiveTab()
            }
        )
        binding.categoryRecyclerView.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.categoryRecyclerView.adapter = categoryTabAdapter
        refreshCategoryTabs()

        // Edit Channel Order Button
        binding.btnEditOrder.setOnClickListener {
            toggleReorderMode()
        }

        // View Mode Toggle (Grid <-> List)
        updateViewModeButtonText()
        binding.btnToggleView.setOnClickListener {
            isGridMode = !isGridMode
            preferences.viewMode = if (isGridMode) "grid" else "list"
            channelAdapter.setGridMode(isGridMode)
            updateRecyclerViewLayoutManager()
            updateViewModeButtonText()
        }

        // Aspect Ratio Toggle (Fit <-> Zoom <-> Stretch) per channel
        binding.btnAspectRatio.setOnClickListener {
            cycleCurrentChannelAspectRatio()
        }

        // Live Search Input Listener
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                filterChannels(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Search action listener (Enter / Search on remote or virtual keyboard)
        binding.searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val currentQuery = binding.searchInput.text.toString().trim()
                if (currentQuery.isNotEmpty()) {
                    val matches = SearchMatcher.search(currentQuery, channelList)
                    if (matches.isNotEmpty()) {
                        val targetChannel = matches.first()
                        val targetIndex = channelList.indexOfFirst { it.id == targetChannel.id }
                        if (targetIndex != -1) {
                            playChannel(targetIndex)
                            hideChannelMenu()
                            return@setOnEditorActionListener true
                        }
                    }
                }
            }
            false
        }
    }

    private fun cycleCurrentChannelAspectRatio() {
        val currentCh = channelList.getOrNull(currentChannelIndex) ?: return
        val currentMode = preferences.getChannelAspectRatio(currentCh.id)
        val nextMode = (currentMode + 1) % 3
        preferences.setChannelAspectRatio(currentCh.id, nextMode)
        playerManager.setAspectRatioMode(nextMode)
        updateAspectRatioButtonText(currentCh.id)

        val modeName = playerManager.getAspectRatioName(nextMode)
        Toast.makeText(
            this,
            "${currentCh.name}: $modeName",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateRecyclerViewLayoutManager() {
        if (isGridMode) {
            binding.channelsRecyclerView.layoutManager = GridLayoutManager(this, 4)
        } else {
            binding.channelsRecyclerView.layoutManager = LinearLayoutManager(this)
        }
    }

    private fun updateViewModeButtonText() {
        binding.btnToggleView.text = if (isGridMode) "☷ Grid View" else "☰ List View"
    }

    private fun updateAspectRatioButtonText(channelId: String? = null) {
        val chId = channelId ?: channelList.getOrNull(currentChannelIndex)?.id
        val mode = if (chId != null) preferences.getChannelAspectRatio(chId) else preferences.aspectRatioMode
        binding.btnAspectRatio.text = "📐 ${playerManager.getAspectRatioName(mode)}"
    }

    private fun toggleReorderMode() {
        isReorderMode = !isReorderMode
        pickedReorderChannel = null
        binding.reorderModeBanner.visibility = if (isReorderMode) View.VISIBLE else View.GONE
        binding.btnEditOrder.text = if (isReorderMode) "✓ Done Editing" else "↕ Edit Order"
        if (isReorderMode) {
            binding.reorderModeBanner.text = "↕ REORDER MODE: Highlight a channel, press OK to grab, use ▲/▼ to move, press OK to place"
            Toast.makeText(this, "Reorder Mode: Click any channel to pick it up, then click destination slot!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Channel order saved permanently!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleReorderChannelClick(channel: Channel) {
        val picked = pickedReorderChannel
        if (picked == null) {
            pickedReorderChannel = channel
            binding.reorderModeBanner.text = "📌 SELECTED: ${channel.name} ➔ Click destination channel to place here, or press Back to cancel"
            Toast.makeText(this, "Selected '${channel.name}'. Click target channel to place here.", Toast.LENGTH_SHORT).show()
        } else {
            val fromIndex = channelList.indexOfFirst { it.id == picked.id }
            val toIndex = channelList.indexOfFirst { it.id == channel.id }
            if (fromIndex != -1 && toIndex != -1 && fromIndex != toIndex) {
                val moved = channelList.removeAt(fromIndex)
                channelList.add(toIndex, moved)
                preferences.saveCustomChannelOrder(channelList.map { it.id })
                loadChannelsForActiveTab()
                Toast.makeText(this, "✓ Moved '${moved.name}' to slot ${toIndex + 1}!", Toast.LENGTH_SHORT).show()
            }
            pickedReorderChannel = null
            binding.reorderModeBanner.text = "↕ REORDER MODE: Highlight a channel, press OK to grab, use ▲/▼ to move, press OK to place"
        }
    }

    private fun refreshCategoryTabs() {
        val favCount = channelList.count { it.isFavorite }
        val punjabiCount = channelList.count { it.isPunjabi || it.languages.contains("pan") || it.name.lowercase().contains("punjabi") }
        val sportsCount = channelList.count {
            val cat = it.category?.lowercase() ?: ""
            val name = it.name.lowercase()
            cat.contains("sport") || name.contains("sport") || name.contains("cricket")
        }
        val englishCount = channelList.count {
            it.languages.contains("eng") || (it.category?.lowercase()?.contains("english") == true) || it.name.lowercase().contains("english")
        }
        val newsCount = channelList.count {
            val cat = it.category?.lowercase() ?: ""
            val name = it.name.lowercase()
            cat.contains("news") || name.contains("news") || name.contains("samachar")
        }
        val moviesCount = channelList.count {
            val cat = it.category?.lowercase() ?: ""
            val name = it.name.lowercase()
            cat.contains("movie") || cat.contains("cinema") || cat.contains("entertainment") || cat.contains("series") || name.contains("cinema") || name.contains("movies") || name.contains("gold") || name.contains("cineplex") || name.contains("max") || name.contains("action")
        }
        val devotionalCount = channelList.count {
            val cat = it.category?.lowercase() ?: ""
            val name = it.name.lowercase()
            cat.contains("relig") || cat.contains("devo") || name.contains("simran") || name.contains("gurbani") || name.contains("bhakti") || name.contains("aastha") || name.contains("sanskar") || name.contains("fateh")
        }
        val musicCount = channelList.count {
            val cat = it.category?.lowercase() ?: ""
            val name = it.name.lowercase()
            cat.contains("music") || name.contains("music") || name.contains("9x") || name.contains("mtv") || name.contains("mastiii")
        }
        val kidsCount = channelList.count {
            val cat = it.category?.lowercase() ?: ""
            val name = it.name.lowercase()
            cat.contains("kid") || cat.contains("child") || cat.contains("documentary") || cat.contains("educat") || cat.contains("science") || cat.contains("nature") || name.contains("discovery") || name.contains("nat geo") || name.contains("history") || name.contains("pogo") || name.contains("cartoon") || name.contains("sonic") || name.contains("hungama") || name.contains("nickelodeon") || name.contains("disney") || name.contains("planet")
        }
        val hindiCount = channelList.count {
            it.languages.contains("hin") && !it.isPunjabi && !it.name.lowercase().contains("punjabi")
        }

        val tabs = mutableListOf<CategoryTab>()
        tabs.add(CategoryTab("fav", "★ Favorites", favCount))
        tabs.add(CategoryTab("punjabi", "Punjabi", punjabiCount))
        tabs.add(CategoryTab("sports", "Sports", sportsCount))
        tabs.add(CategoryTab("english", "English", englishCount))
        tabs.add(CategoryTab("news", "News", newsCount))
        tabs.add(CategoryTab("movies", "Movies & Shows", moviesCount))
        tabs.add(CategoryTab("devotional", "Devotional", devotionalCount))
        tabs.add(CategoryTab("music", "Music", musicCount))
        tabs.add(CategoryTab("kids", "Kids & Info", kidsCount))
        tabs.add(CategoryTab("hindi", "Hindi", hindiCount))
        tabs.add(CategoryTab("all", "All Channels", channelList.size))

        val selectIdx = tabs.indexOfFirst { it.id == activeCategoryTabId }.coerceAtLeast(0)
        categoryTabAdapter.setTabs(tabs, selectIdx)
    }

    private fun loadChannelsForActiveTab() {
        val filtered = when (activeCategoryTabId) {
            "fav" -> channelList.filter { it.isFavorite }
            "punjabi" -> channelList.filter { it.isPunjabi || it.languages.contains("pan") || it.name.lowercase().contains("punjabi") }
            "sports" -> channelList.filter {
                val cat = it.category?.lowercase() ?: ""
                val name = it.name.lowercase()
                cat.contains("sport") || name.contains("sport") || name.contains("cricket")
            }
            "english" -> channelList.filter {
                it.languages.contains("eng") || (it.category?.lowercase()?.contains("english") == true) || it.name.lowercase().contains("english")
            }
            "news" -> channelList.filter {
                val cat = it.category?.lowercase() ?: ""
                val name = it.name.lowercase()
                cat.contains("news") || name.contains("news") || name.contains("samachar")
            }
            "movies" -> channelList.filter {
                val cat = it.category?.lowercase() ?: ""
                val name = it.name.lowercase()
                cat.contains("movie") || cat.contains("cinema") || cat.contains("entertainment") || cat.contains("series") || name.contains("cinema") || name.contains("movies") || name.contains("gold") || name.contains("cineplex") || name.contains("max") || name.contains("action")
            }
            "devotional" -> channelList.filter {
                val cat = it.category?.lowercase() ?: ""
                val name = it.name.lowercase()
                cat.contains("relig") || cat.contains("devo") || name.contains("simran") || name.contains("gurbani") || name.contains("bhakti") || name.contains("aastha") || name.contains("sanskar") || name.contains("fateh")
            }
            "music" -> channelList.filter {
                val cat = it.category?.lowercase() ?: ""
                val name = it.name.lowercase()
                cat.contains("music") || name.contains("music") || name.contains("9x") || name.contains("mtv") || name.contains("mastiii")
            }
            "kids" -> channelList.filter {
                val cat = it.category?.lowercase() ?: ""
                val name = it.name.lowercase()
                cat.contains("kid") || cat.contains("child") || cat.contains("documentary") || cat.contains("educat") || cat.contains("science") || cat.contains("nature") || name.contains("discovery") || name.contains("nat geo") || name.contains("history") || name.contains("pogo") || name.contains("cartoon") || name.contains("sonic") || name.contains("hungama") || name.contains("nickelodeon") || name.contains("disney") || name.contains("planet")
            }
            "hindi" -> channelList.filter {
                it.languages.contains("hin") && !it.isPunjabi && !it.name.lowercase().contains("punjabi")
            }
            else -> channelList
        }

        channelAdapter.setChannels(filtered)
        binding.emptyMessage.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        if (filtered.isEmpty()) {
            binding.emptyMessage.text = if (activeCategoryTabId == "fav") {
                "No favorites yet.\nHold OK on any channel to add it to Favorites!"
            } else {
                "No channels found in this category."
            }
        }
    }

    private fun filterChannels(query: String) {
        if (query.isEmpty()) {
            binding.categoryRecyclerView.visibility = View.VISIBLE
            loadChannelsForActiveTab()
            return
        }

        binding.categoryRecyclerView.visibility = View.GONE
        val matches = SearchMatcher.search(query, channelList)
        channelAdapter.setChannels(matches)
        binding.emptyMessage.visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE
        if (matches.isEmpty()) {
            binding.emptyMessage.text = "No channels match \"$query\"\nTry speaking channel name or category"
        }
    }

    private fun toggleFavoriteChannel(channel: Channel) {
        val isNowFav = preferences.toggleFavorite(channel.id)
        channel.isFavorite = isNowFav

        refreshCategoryTabs()
        loadChannelsForActiveTab()

        val msg = if (isNowFav) "★ Added to Favorites" else "Removed from Favorites"
        Toast.makeText(this, "${channel.name}: $msg", Toast.LENGTH_SHORT).show()
    }

    private fun showChannelMenu(focusSearch: Boolean = false) {
        if (binding.channelMenuContainer.visibility == View.VISIBLE) {
            if (focusSearch) {
                binding.searchInput.requestFocus()
            }
            return
        }

        binding.channelInfoCard.visibility = View.GONE
        binding.channelMenuContainer.visibility = View.VISIBLE

        refreshCategoryTabs()
        loadChannelsForActiveTab()

        if (focusSearch) {
            binding.searchInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
        } else {
            // Focus the first channel tile or category tab
            binding.channelsRecyclerView.post {
                binding.channelsRecyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    ?: binding.categoryRecyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    private fun hideChannelMenu() {
        if (binding.channelMenuContainer.visibility == View.GONE) return

        // Hide keyboard if search had focus
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchInput.clearFocus()
        binding.searchInput.setText("")
        binding.categoryRecyclerView.visibility = View.VISIBLE

        binding.channelMenuContainer.visibility = View.GONE

        // Re-show brief info of currently playing channel
        val ch = channelList.getOrNull(currentChannelIndex)
        if (ch != null) {
            showChannelInfo(ch, currentChannelIndex)
        }
    }

    private fun playChannel(index: Int, switchSource: String? = null) {
        if (channelList.isEmpty()) return

        val targetIndex = if (index < 0) {
            Toast.makeText(this, "CH 1: ${channelList[0].name} (Start of Lineup)", Toast.LENGTH_SHORT).show()
            0
        } else if (index >= channelList.size) {
            Toast.makeText(this, "CH ${channelList.size}: ${channelList.last().name} (End of Lineup)", Toast.LENGTH_SHORT).show()
            channelList.size - 1
        } else {
            index
        }
        if (targetIndex != currentChannelIndex) {
            previousChannelIndex = currentChannelIndex
        }
        currentChannelIndex = targetIndex
        val channel = channelList[currentChannelIndex]
        isManuallyPaused = false

        // Persist last played channel immediately
        preferences.lastChannelId = channel.id
        preferences.lastChannelIndex = currentChannelIndex
        preferences.recordRecentChannel(channel.id)

        // Apply saved individual aspect ratio for this channel
        val chAspectRatio = preferences.getChannelAspectRatio(channel.id)
        playerManager.setAspectRatioMode(chAspectRatio)
        updateAspectRatioButtonText(channel.id)

        cancelErrorTimer()
        binding.errorBannerCard.visibility = View.GONE
        binding.loadingIndicator.visibility = View.VISIBLE

        showChannelInfo(channel, currentChannelIndex, switchSource)

        if (channel.streams.isNotEmpty()) {
            playerManager.playStreams(channel.streams)
        } else {
            handleChannelFailure()
        }
    }

    private fun showChannelInfo(channel: Channel, index: Int, switchSource: String? = null) {
        val badge = when (switchSource) {
            "recall" -> "⮌ RECALL CH ${index + 1}"
            "fav" -> "★ FAV CH ${index + 1}"
            "recent" -> "🕐 RECENT CH ${index + 1}"
            else -> "CH ${index + 1}"
        }
        binding.channelBadge.text = badge
        binding.channelName.text = channel.name
        binding.channelCategory.text = channel.category?.uppercase() ?: "LIVE TV"

        // "New" badge indicator
        binding.channelNewBadge.visibility = if (channel.isNew) View.VISIBLE else View.GONE

        // Favorite indicator
        binding.channelFavoriteIndicator.visibility =
            if (preferences.isFavorite(channel.id)) View.VISIBLE else View.GONE

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

    private fun recallPreviousChannel() {
        if (previousChannelIndex in 0 until channelList.size && previousChannelIndex != currentChannelIndex) {
            val target = previousChannelIndex
            playChannel(target, switchSource = "recall")
            Toast.makeText(this, "⮌ Recall: ${channelList[target].name}", Toast.LENGTH_SHORT).show()
        } else {
            prevChannel()
        }
    }

    private fun jumpNextFavoriteOrRecent() {
        val favs = channelList.filter { it.isFavorite }
        if (favs.isNotEmpty()) {
            val currentCh = channelList.getOrNull(currentChannelIndex)
            val currFavIdx = favs.indexOfFirst { it.id == currentCh?.id }
            val nextFavIdx = (currFavIdx + 1) % favs.size
            val nextFavChannel = favs[nextFavIdx]
            val targetIndex = channelList.indexOfFirst { it.id == nextFavChannel.id }
            if (targetIndex != -1) {
                playChannel(targetIndex, switchSource = "fav")
                Toast.makeText(
                    this,
                    "★ Favorite [${nextFavIdx + 1}/${favs.size}]: ${nextFavChannel.name}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            val recents = preferences.getRecentChannelIds()
                .filter { it != channelList.getOrNull(currentChannelIndex)?.id }
            if (recents.isNotEmpty()) {
                val nextRecentId = recents.first()
                val targetIndex = channelList.indexOfFirst { it.id == nextRecentId }
                if (targetIndex != -1) {
                    playChannel(targetIndex, switchSource = "recent")
                    Toast.makeText(this, "🕐 Recent: ${channelList[targetIndex].name}", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            nextChannel()
        }
    }

    private fun togglePlayPause() {
        if (isManuallyPaused) {
            playerManager.resume()
            isManuallyPaused = false
            Toast.makeText(this, "▶ Playing", Toast.LENGTH_SHORT).show()
        } else {
            playerManager.pause()
            isManuallyPaused = true
            Toast.makeText(this, "⏸ Paused", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showQuickOptionsDialog() {
        if (binding.quickOptionsContainer.visibility == View.VISIBLE) {
            hideQuickOptionsDialog()
            return
        }

        val currentCh = channelList.getOrNull(currentChannelIndex) ?: return
        val dialog = binding.dialogQuickOptions

        dialog.dialogChannelName.text = currentCh.name
        updateQuickOptionsDialogContent(currentCh)

        dialog.btnOptAspectRatio.setOnClickListener {
            val currentMode = preferences.getChannelAspectRatio(currentCh.id)
            val nextMode = (currentMode + 1) % 3
            preferences.setChannelAspectRatio(currentCh.id, nextMode)
            playerManager.setAspectRatioMode(nextMode)
            updateAspectRatioButtonText(currentCh.id)
            updateQuickOptionsDialogContent(currentCh)
            Toast.makeText(this, "${currentCh.name}: ${playerManager.getAspectRatioName(nextMode)}", Toast.LENGTH_SHORT).show()
        }

        dialog.btnOptFavorite.setOnClickListener {
            val isFav = preferences.toggleFavorite(currentCh.id)
            currentCh.isFavorite = isFav
            refreshCategoryTabs()
            updateQuickOptionsDialogContent(currentCh)
            val msg = if (isFav) "★ Added to Favorites" else "Removed from Favorites"
            Toast.makeText(this, "${currentCh.name}: $msg", Toast.LENGTH_SHORT).show()
        }

        dialog.btnOptMoveUp.setOnClickListener {
            moveCurrentChannelUp()
        }

        dialog.btnOptMoveDown.setOnClickListener {
            moveCurrentChannelDown()
        }

        dialog.btnOptStream.setOnClickListener {
            val (serverNum, totalServers) = playerManager.cycleStreamServer()
            updateQuickOptionsDialogContent(currentCh)
            Toast.makeText(this, "Switched to Stream Server $serverNum of $totalServers", Toast.LENGTH_SHORT).show()
        }

        dialog.btnOptViewMode.setOnClickListener {
            isGridMode = !isGridMode
            preferences.viewMode = if (isGridMode) "grid" else "list"
            channelAdapter.setGridMode(isGridMode)
            updateRecyclerViewLayoutManager()
            updateViewModeButtonText()
            updateQuickOptionsDialogContent(currentCh)
            val modeStr = if (isGridMode) "Grid View (4 tiles)" else "List View"
            Toast.makeText(this, "Menu Style: $modeStr", Toast.LENGTH_SHORT).show()
        }

        dialog.btnOptSearch.setOnClickListener {
            hideQuickOptionsDialog()
            showChannelMenu(focusSearch = true)
        }

        dialog.btnOptUpdate.setOnClickListener {
            hideQuickOptionsDialog()
            checkAndPromptAppUpdate(isManual = true)
        }

        binding.quickOptionsContainer.setOnClickListener {
            hideQuickOptionsDialog()
        }

        binding.quickOptionsContainer.visibility = View.VISIBLE
        dialog.btnOptAspectRatio.requestFocus()
    }

    private fun moveCurrentChannelUp() {
        if (currentChannelIndex <= 0) {
            Toast.makeText(this, "Already at top of channel list", Toast.LENGTH_SHORT).show()
            return
        }
        val targetIndex = currentChannelIndex - 1
        java.util.Collections.swap(channelList, currentChannelIndex, targetIndex)
        currentChannelIndex = targetIndex
        preferences.saveCustomChannelOrder(channelList.map { it.id })
        channelAdapter.setChannels(channelList)
        updateQuickOptionsDialogContent(channelList[currentChannelIndex])
        Toast.makeText(this, "Moved to CH ${currentChannelIndex + 1}: ${channelList[currentChannelIndex].name}", Toast.LENGTH_SHORT).show()
    }

    private fun moveCurrentChannelDown() {
        if (currentChannelIndex >= channelList.size - 1) {
            Toast.makeText(this, "Already at end of channel list", Toast.LENGTH_SHORT).show()
            return
        }
        val targetIndex = currentChannelIndex + 1
        java.util.Collections.swap(channelList, currentChannelIndex, targetIndex)
        currentChannelIndex = targetIndex
        preferences.saveCustomChannelOrder(channelList.map { it.id })
        channelAdapter.setChannels(channelList)
        updateQuickOptionsDialogContent(channelList[currentChannelIndex])
        Toast.makeText(this, "Moved to CH ${currentChannelIndex + 1}: ${channelList[currentChannelIndex].name}", Toast.LENGTH_SHORT).show()
    }

    private fun applyCustomOrder() {
        val customOrder = preferences.getCustomChannelOrder()
        if (customOrder.isNotEmpty()) {
            val orderMap = customOrder.withIndex().associate { it.value to it.index }
            channelList.sortWith(compareBy { orderMap[it.id] ?: (Int.MAX_VALUE - 1000) })
        }
    }

    private fun updateQuickOptionsDialogContent(channel: Channel) {
        val dialog = binding.dialogQuickOptions
        val chAspectRatio = preferences.getChannelAspectRatio(channel.id)
        dialog.tvOptAspectValue.text = playerManager.getAspectRatioName(chAspectRatio)

        val isFav = preferences.isFavorite(channel.id)
        dialog.tvOptFavoriteTitle.text = if (isFav) "★ Remove from Favorites" else "★ Add to Favorites"
        dialog.tvOptFavoriteSub.text = if (isFav) "Currently pinned in Favorites tab" else "Pin to Favorites tab for 1-click access"

        val (serverNum, totalServers) = playerManager.getStreamServerStatus()
        // If channel does not have multiple streams, don't show stream switch option
        if (totalServers > 1) {
            dialog.btnOptStream.visibility = View.VISIBLE
            dialog.tvOptStreamValue.text = "Server $serverNum of $totalServers (Click to switch)"
        } else {
            dialog.btnOptStream.visibility = View.GONE
        }

        dialog.tvOptMoveUpSub.text = "Current: CH ${currentChannelIndex + 1} (Move earlier)"
        dialog.tvOptMoveDownSub.text = "Current: CH ${currentChannelIndex + 1} (Move later)"

        dialog.tvOptViewModeValue.text = if (isGridMode) "Grid View (4 tiles per row)" else "List View (large text)"

        val currentVersionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.1.0"
        } catch (e: Exception) {
            "1.1.0"
        }
        dialog.tvOptUpdateSub.text = "Current: v$currentVersionName • Click to check & update in-app"
    }

    private fun hideQuickOptionsDialog() {
        binding.quickOptionsContainer.visibility = View.GONE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode

            // 0. If App Update Dialog is OPEN
            if (binding.updateDialogContainer.visibility == View.VISIBLE) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (!isDownloadingUpdate) {
                        hideUpdateDialog()
                        return true
                    } else {
                        Toast.makeText(this, "Downloading update in background...", Toast.LENGTH_SHORT).show()
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }

            // 1. If Quick Options Dialog is OPEN
            if (binding.quickOptionsContainer.visibility == View.VISIBLE) {
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
                    hideQuickOptionsDialog()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            // 2. If Channel Menu is OPEN
            if (binding.channelMenuContainer.visibility == View.VISIBLE) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (binding.searchInput.text.isNotEmpty()) {
                        binding.searchInput.setText("")
                        return true
                    }
                    hideChannelMenu()
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    showQuickOptionsDialog()
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
                    nextChannel()
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                    prevChannel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            // 3. If watching full-screen video
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    prevChannel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    nextChannel()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    nextChannel()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    prevChannel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    showChannelMenu()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    jumpNextFavoriteOrRecent()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    showChannelMenu()
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    showQuickOptionsDialog()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (binding.channelInfoCard.visibility == View.VISIBLE) {
                        binding.channelInfoCard.visibility = View.GONE
                        return true
                    }
                    // Open Channel Menu on Back per PRD Section 5.4 ("OK: open the grid. Back: also open the grid")
                    showChannelMenu()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // =========================================================================
    // IN-APP AUTO-UPDATE LOGIC
    // =========================================================================

    private fun checkAndPromptAppUpdate(isManual: Boolean) {
        if (isManual) {
            Toast.makeText(this, "Checking for app updates...", Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            when (val result = appUpdater.checkForUpdate(this@MainActivity)) {
                is UpdateCheckResult.Available -> {
                    showUpdateDialog(result.updateInfo)
                }
                is UpdateCheckResult.UpToDate -> {
                    if (isManual) {
                        Toast.makeText(this@MainActivity, "✓ App is up to date (v${result.currentVersion})", Toast.LENGTH_SHORT).show()
                    }
                }
                is UpdateCheckResult.Error -> {
                    if (isManual) {
                        Toast.makeText(this@MainActivity, "Update check failed: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(updateInfo: UpdateInfo) {
        activeUpdateInfo = updateInfo
        isDownloadingUpdate = false

        binding.tvUpdateTitle.text = "🚀 New TV App Update Available"
        binding.tvUpdateVersion.text = "Version ${updateInfo.versionName} is ready to install"
        binding.tvUpdateNotes.text = updateInfo.releaseNotes

        binding.layoutUpdateProgress.visibility = View.GONE
        binding.layoutUpdateButtons.visibility = View.VISIBLE
        binding.btnUpdateNow.isEnabled = true
        binding.btnUpdateLater.isEnabled = true

        binding.btnUpdateLater.setOnClickListener {
            hideUpdateDialog()
        }

        binding.btnUpdateNow.setOnClickListener {
            startApkDownload(updateInfo)
        }

        binding.updateDialogContainer.visibility = View.VISIBLE
        binding.btnUpdateNow.requestFocus()
    }

    private fun hideUpdateDialog() {
        if (isDownloadingUpdate) {
            Toast.makeText(this, "Downloading update in background...", Toast.LENGTH_SHORT).show()
        }
        binding.updateDialogContainer.visibility = View.GONE
    }

    private fun startApkDownload(updateInfo: UpdateInfo) {
        isDownloadingUpdate = true
        binding.layoutUpdateProgress.visibility = View.VISIBLE
        binding.btnUpdateNow.isEnabled = false
        binding.btnUpdateLater.isEnabled = false
        binding.pbUpdateDownload.progress = 0
        binding.tvUpdateStatus.text = "Downloading update: 0%..."

        lifecycleScope.launch {
            appUpdater.downloadAndInstall(
                activity = this@MainActivity,
                updateInfo = updateInfo,
                onProgress = { progress, bytesRead, totalBytes ->
                    val mbRead = bytesRead / (1024 * 1024.0)
                    val mbTotal = totalBytes / (1024 * 1024.0)
                    binding.pbUpdateDownload.progress = progress
                    binding.tvUpdateStatus.text = "Downloading update: $progress% (%.1f MB / %.1f MB)".format(mbRead, mbTotal)
                },
                onError = { errMsg ->
                    isDownloadingUpdate = false
                    binding.layoutUpdateProgress.visibility = View.GONE
                    binding.btnUpdateNow.isEnabled = true
                    binding.btnUpdateLater.isEnabled = true
                    binding.tvUpdateStatus.text = "Error: $errMsg"
                    Toast.makeText(this@MainActivity, "Download failed: $errMsg", Toast.LENGTH_LONG).show()
                }
            )
        }
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
