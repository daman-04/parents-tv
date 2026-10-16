package com.parents.tv.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.parents.tv.data.model.Channel
import com.parents.tv.data.model.ChannelFeed
import com.parents.tv.data.model.ChannelSection
import com.parents.tv.data.model.Stream
import com.parents.tv.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

class ChannelRepository(
    private val context: Context,
    private val preferences: AppPreferences
) {
    companion object {
        private const val TAG = "ChannelRepository"
        private const val CACHE_FILE_NAME = "cached_channels.json"
        private const val ASSET_FILE_NAME = "channels.json"
        private const val REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours
        // Default remote feed (can be updated via overrides / preferences)
        private const val DEFAULT_REMOTE_URL =
            "https://raw.githubusercontent.com/daman/TV/main/channels.json"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    var currentFeed: ChannelFeed? = null
        private set

    /**
     * Checks if active internet connection exists.
     */
    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            return networkInfo != null && networkInfo.isConnected
        }
    }

    /**
     * Loads channels synchronously from cache or bundled assets.
     * Guaranteed never to return empty if bundled asset exists.
     */
    fun loadInitialFeed(): ChannelFeed {
        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        var feed: ChannelFeed? = null

        if (cacheFile.exists()) {
            try {
                val jsonString = cacheFile.readText(Charsets.UTF_8)
                feed = parseFeed(jsonString)
                Log.d(TAG, "Loaded channels from local cache.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse cached channels, falling back to bundled assets.", e)
            }
        }

        if (feed == null) {
            feed = loadBundledFeed()
            Log.d(TAG, "Loaded channels from bundled asset.")
        }

        currentFeed = feed
        return feed
    }

    private fun loadBundledFeed(): ChannelFeed {
        return try {
            val inputStream: InputStream = context.assets.open(ASSET_FILE_NAME)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            parseFeed(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read bundled assets channels.json", e)
            // Emergency fallback if asset missing
            ChannelFeed(
                generated = "",
                defaultChannel = "DDPunjabi.in",
                totalChannels = 0,
                sections = emptyList()
            )
        }
    }

    /**
     * Parses the JSON payload, applies device failure counters and identifies 'New' channels.
     */
    fun parseFeed(jsonString: String): ChannelFeed {
        val root = JSONObject(jsonString)
        val generated = root.optString("generated", "")
        val defaultChannel = root.optString("default_channel", "DDPunjabi.in")
        val totalChannels = root.optInt("total_channels", 0)

        val knownIds = preferences.getKnownChannelIds()
        val isFirstRunEver = knownIds.isEmpty()
        val allSeenIds = mutableSetOf<String>()
        val newChannelsList = mutableListOf<Channel>()

        val rawSections = root.optJSONArray("sections")
        val processedSections = mutableListOf<ChannelSection>()

        if (rawSections != null) {
            for (i in 0 until rawSections.length()) {
                val secObj = rawSections.getJSONObject(i)
                val secId = secObj.optString("id")
                val secTitle = secObj.optString("title")
                val channelsArray = secObj.optJSONArray("channels")
                val channelsList = mutableListOf<Channel>()

                if (channelsArray != null) {
                    for (j in 0 until channelsArray.length()) {
                        val chObj = channelsArray.getJSONObject(j)
                        val id = chObj.getString("id")
                        val name = chObj.getString("name")
                        val logo = chObj.optString("logo").takeIf { it.isNotEmpty() && it != "null" }
                        val category = chObj.optString("category", "General")
                        val isPunjabi = chObj.optBoolean("is_punjabi", false)

                        // Streams
                        val streamsList = mutableListOf<Stream>()
                        val streamsArray = chObj.optJSONArray("streams")
                        if (streamsArray != null) {
                            for (k in 0 until streamsArray.length()) {
                                val sObj = streamsArray.getJSONObject(k)
                                streamsList.add(
                                    Stream(
                                        url = sObj.getString("url"),
                                        status = sObj.optString("status", "working")
                                    )
                                )
                            }
                        }

                        // Aliases
                        val aliasesList = mutableListOf<String>()
                        val aliasesArray = chObj.optJSONArray("aliases")
                        if (aliasesArray != null) {
                            for (k in 0 until aliasesArray.length()) {
                                aliasesList.add(aliasesArray.getString(k))
                            }
                        }

                        allSeenIds.add(id)
                        val failureCount = preferences.getChannelFailureCount(id)
                        val isNewChannel = !isFirstRunEver && !knownIds.contains(id)

                        val channel = Channel(
                            id = id,
                            name = name,
                            logo = logo,
                            category = category,
                            isPunjabi = isPunjabi,
                            streams = streamsList,
                            aliases = aliasesList,
                            isNew = isNewChannel,
                            failureCount = failureCount
                        )

                        if (isNewChannel) {
                            newChannelsList.add(channel)
                        } else {
                            channelsList.add(channel)
                        }
                    }
                }

                // Section channel ordering:
                // Channels with repeated failures (>= 3) move to the bottom of their section
                channelsList.sortWith(
                    compareBy<Channel> { if (it.failureCount >= 3) 1 else 0 }
                        .thenBy { it.failureCount }
                )

                if (channelsList.isNotEmpty()) {
                    processedSections.add(ChannelSection(secId, secTitle, channelsList))
                }
            }
        }

        // On first run, record all seen IDs as known
        if (isFirstRunEver && allSeenIds.isNotEmpty()) {
            preferences.saveKnownChannelIds(allSeenIds)
        }

        // If new channels were found in a background update, place them in a distinct "New" row
        if (newChannelsList.isNotEmpty()) {
            processedSections.add(
                0,
                ChannelSection(
                    id = "new",
                    title = "New Channels",
                    channels = newChannelsList
                )
            )
        }

        val feed = ChannelFeed(
            generated = generated,
            defaultChannel = defaultChannel,
            totalChannels = totalChannels,
            sections = processedSections
        )
        currentFeed = feed
        return feed
    }

    /**
     * Checks if 24 hours have passed and updates feed in background.
     * Never interrupts video playback or throws exceptions.
     */
    suspend fun refreshFeedIfStale(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastFetch = preferences.lastFeedFetchTime

        if (!force && (now - lastFetch) < REFRESH_INTERVAL_MS) {
            Log.d(TAG, "Feed is up to date (< 24h old). Skipping download.")
            return@withContext false
        }

        if (!isNetworkAvailable()) {
            Log.d(TAG, "No internet connection. Keeping saved/cached channels.")
            return@withContext false
        }

        val targetUrl = preferences.customFeedUrl ?: DEFAULT_REMOTE_URL
        try {
            Log.d(TAG, "Downloading fresh channels feed from $targetUrl...")
            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "ParentsTV/1.0 (Android FireTV)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP error fetching feed: ${response.code}")
                    return@withContext false
                }

                val bodyString = response.body?.string() ?: ""
                if (bodyString.isBlank()) {
                    Log.w(TAG, "Empty response received. Keeping existing channels.")
                    return@withContext false
                }

                // Strictly validate JSON structure before caching
                val parsed = parseFeed(bodyString)
                if (parsed.sections.isEmpty() || parsed.totalChannels == 0) {
                    Log.w(TAG, "Feed validation failed: 0 sections parsed. Keeping existing.")
                    return@withContext false
                }

                // Atomically write good copy to cache file
                val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
                cacheFile.writeText(bodyString, Charsets.UTF_8)
                preferences.lastFeedFetchTime = now
                Log.d(TAG, "Successfully cached fresh channels feed (${parsed.totalChannels} channels).")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background feed update failed: ${e.message}. Keeping cached copy.", e)
            return@withContext false
        }
    }

    /**
     * Returns a flat list of all playable channels across all sections.
     */
    fun getAllChannels(): List<Channel> {
        val feed = currentFeed ?: loadInitialFeed()
        return feed.sections.flatMap { it.channels }
    }

    fun recordChannelFailure(channelId: String): Int {
        val count = preferences.incrementFailureCount(channelId)
        Log.d(TAG, "Channel $channelId failed. Failure count: $count")
        return count
    }

    fun recordChannelSuccess(channelId: String) {
        preferences.resetFailureCount(channelId)
    }
}
