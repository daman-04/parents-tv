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
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var playerManager: TvPlayerManager
    private lateinit var preferences: AppPreferences
    private lateinit var repository: ChannelRepository

    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var categoryTabAdapter: CategoryTabAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var errorTimer: CountDownTimer? = null
    private var currentChannelIndex = 0
    private var previousChannelIndex = -1
    private var isManuallyPaused = false
    private var channelList: MutableList<Channel> = mutableListOf()
    private var activeCategoryTabId = "punjabi"
    private var isGridMode = true

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
                        if (activeChId != null) {
                            val newIdx = channelList.indexOfFirst { it.id == activeChId }
                            if (newIdx != -1) currentChannelIndex = newIdx
                        }
                        refreshCategoryTabs()
                    }
                }
            }
        }
    }

    private fun setupChannelMenu() {
        // Channel Adapter setup
        channelAdapter = ChannelAdapter(
            channels = emptyList(),
            isGridMode = isGridMode,
            onChannelClick = { channel, _ ->
                val targetIndex = channelList.indexOfFirst { it.id == channel.id }
                if (targetIndex != -1) {
                    playChannel(targetIndex)
                }
                hideChannelMenu()
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

    private fun refreshCategoryTabs() {
        val favCount = channelList.count { it.isFavorite }
        val punjabiCount = channelList.count { it.isPunjabi }
        val sportsCount = channelList.count {
            val cat = it.category?.lowercase() ?: ""
            val name = it.name.lowercase()
            cat.contains("sport") || name.contains("sport")
        }
        val devotionalCount = channelList.count {
            val cat = it.category?.lowercase() ?: ""
            cat.contains("religious") || cat.contains("devotional")
        }
        val newsCount = channelList.count {
            val cat = it.category?.lowercase() ?: ""
            cat.contains("news")
        }
        val musicCount = channelList.count {
            val cat = it.category?.lowercase() ?: ""
            cat.contains("music")
        }
        val hindiCount = channelList.count {
            it.languages.contains("hin") && !it.isPunjabi
        }
        val englishCount = channelList.count {
            it.languages.contains("eng") && !it.isPunjabi
        }

        val tabs = mutableListOf<CategoryTab>()
        tabs.add(CategoryTab("fav", "★ Favorites", favCount))
        tabs.add(CategoryTab("punjabi", "Punjabi", punjabiCount))
        tabs.add(CategoryTab("sports", "Sports", sportsCount))
        tabs.add(CategoryTab("devotional", "Devotional", devotionalCount))
        tabs.add(CategoryTab("news", "News", newsCount))
        tabs.add(CategoryTab("music", "Music", musicCount))
        tabs.add(CategoryTab("hindi", "Hindi", hindiCount))
        tabs.add(CategoryTab("english", "English", englishCount))
        tabs.add(CategoryTab("all", "All Channels", channelList.size))

        val selectIdx = tabs.indexOfFirst { it.id == activeCategoryTabId }.coerceAtLeast(0)
        categoryTabAdapter.setTabs(tabs, selectIdx)
    }

    private fun loadChannelsForActiveTab() {
        val filtered = when (activeCategoryTabId) {
            "fav" -> channelList.filter { it.isFavorite }
            "punjabi" -> channelList.filter { it.isPunjabi }
            "sports" -> channelList.filter {
                val cat = it.category?.lowercase() ?: ""
                val name = it.name.lowercase()
                cat.contains("sport") || name.contains("sport")
            }
            "devotional" -> channelList.filter {
                val cat = it.category?.lowercase() ?: ""
                cat.contains("religious") || cat.contains("devotional")
            }
            "news" -> channelList.filter {
                val cat = it.category?.lowercase() ?: ""
                cat.contains("news")
            }
            "music" -> channelList.filter {
                val cat = it.category?.lowercase() ?: ""
                cat.contains("music")
            }
            "hindi" -> channelList.filter {
                it.languages.contains("hin") && !it.isPunjabi
            }
            "english" -> channelList.filter {
                it.languages.contains("eng") && !it.isPunjabi
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

        val targetIndex = (index + channelList.size) % channelList.size
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

        binding.quickOptionsContainer.setOnClickListener {
            hideQuickOptionsDialog()
        }

        binding.quickOptionsContainer.visibility = View.VISIBLE
        dialog.btnOptAspectRatio.requestFocus()
    }

    private fun updateQuickOptionsDialogContent(channel: Channel) {
        val dialog = binding.dialogQuickOptions
        val chAspectRatio = preferences.getChannelAspectRatio(channel.id)
        dialog.tvOptAspectValue.text = playerManager.getAspectRatioName(chAspectRatio)

        val isFav = preferences.isFavorite(channel.id)
        dialog.tvOptFavoriteTitle.text = if (isFav) "★ Remove from Favorites" else "★ Add to Favorites"
        dialog.tvOptFavoriteSub.text = if (isFav) "Currently pinned in Favorites tab" else "Pin to Favorites tab for 1-click access"

        val (serverNum, totalServers) = playerManager.getStreamServerStatus()
        dialog.tvOptStreamValue.text = if (totalServers > 1) {
            "Server $serverNum of $totalServers (Click to switch)"
        } else {
            "Server 1 of 1 (Primary Direct Stream)"
        }

        dialog.tvOptViewModeValue.text = if (isGridMode) "Grid View (4 tiles per row)" else "List View (large text)"
    }

    private fun hideQuickOptionsDialog() {
        binding.quickOptionsContainer.visibility = View.GONE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode

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
                return super.dispatchKeyEvent(event)
            }

            // 3. If watching full-screen video
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    prevChannel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    nextChannel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    recallPreviousChannel()
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
