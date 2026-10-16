package com.parents.tv.data.prefs

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("parents_tv_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_CHANNEL_ID = "last_channel_id"
        private const val KEY_VIEW_MODE = "view_mode"
        private const val KEY_LAST_CHANNEL_INDEX = "last_channel_index"
        private const val KEY_LAST_FEED_FETCH = "last_feed_fetch"
        private const val KEY_KNOWN_CHANNELS = "known_channel_ids"
        private const val KEY_DEFAULT_CHANNEL = "default_channel"
        private const val KEY_CUSTOM_FEED_URL = "custom_feed_url"
        private const val KEY_FAVORITES = "favorite_channel_ids"
        private const val KEY_RECENT_CHANNELS = "recent_channel_ids"
        private const val KEY_ASPECT_RATIO = "aspect_ratio_mode"
        private const val PREF_PREFIX_FAILURE = "fail_cnt_"
    }

    var aspectRatioMode: Int
        get() = prefs.getInt(KEY_ASPECT_RATIO, 0) // 0: Fit, 1: Zoom, 2: Stretch/Fill
        set(value) = prefs.edit().putInt(KEY_ASPECT_RATIO, value).apply()

    fun getChannelAspectRatio(channelId: String): Int {
        return prefs.getInt("aspect_ratio_$channelId", aspectRatioMode)
    }

    fun setChannelAspectRatio(channelId: String, mode: Int) {
        prefs.edit().putInt("aspect_ratio_$channelId", mode).apply()
    }

    fun getFavoriteChannelIds(): Set<String> {
        return prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
    }

    fun isFavorite(channelId: String): Boolean {
        return getFavoriteChannelIds().contains(channelId)
    }

    fun toggleFavorite(channelId: String): Boolean {
        val current = getFavoriteChannelIds().toMutableSet()
        val isNowFav = if (current.contains(channelId)) {
            current.remove(channelId)
            false
        } else {
            current.add(channelId)
            true
        }
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply()
        return isNowFav
    }

    fun getRecentChannelIds(): List<String> {
        val raw = prefs.getString(KEY_RECENT_CHANNELS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(",").filter { it.isNotEmpty() }
    }

    fun recordRecentChannel(channelId: String) {
        val current = getRecentChannelIds().toMutableList()
        current.remove(channelId)
        current.add(0, channelId)
        val trimmed = current.take(6)
        prefs.edit().putString(KEY_RECENT_CHANNELS, trimmed.joinToString(",")).apply()
    }

    var lastChannelId: String?
        get() = prefs.getString(KEY_LAST_CHANNEL_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_CHANNEL_ID, value).apply()

    var lastChannelIndex: Int
        get() = prefs.getInt(KEY_LAST_CHANNEL_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_CHANNEL_INDEX, value).apply()

    var viewMode: String
        get() = prefs.getString(KEY_VIEW_MODE, "grid") ?: "grid"
        set(value) = prefs.edit().putString(KEY_VIEW_MODE, value).apply()

    var lastFeedFetchTime: Long
        get() = prefs.getLong(KEY_LAST_FEED_FETCH, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_FEED_FETCH, value).apply()

    var defaultChannelId: String
        get() = prefs.getString(KEY_DEFAULT_CHANNEL, "DDPunjabi.in") ?: "DDPunjabi.in"
        set(value) = prefs.edit().putString(KEY_DEFAULT_CHANNEL, value).apply()

    var customFeedUrl: String?
        get() = prefs.getString(KEY_CUSTOM_FEED_URL, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_FEED_URL, value).apply()

    fun getKnownChannelIds(): Set<String> {
        return prefs.getStringSet(KEY_KNOWN_CHANNELS, emptySet()) ?: emptySet()
    }

    fun saveKnownChannelIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_KNOWN_CHANNELS, ids).apply()
    }

    fun getChannelFailureCount(channelId: String): Int {
        return prefs.getInt(PREF_PREFIX_FAILURE + channelId, 0)
    }

    fun incrementFailureCount(channelId: String): Int {
        val current = getChannelFailureCount(channelId) + 1
        prefs.edit().putInt(PREF_PREFIX_FAILURE + channelId, current).apply()
        return current
    }

    fun resetFailureCount(channelId: String) {
        prefs.edit().remove(PREF_PREFIX_FAILURE + channelId).apply()
    }

    fun getCustomChannelOrder(): List<String> {
        val raw = prefs.getString("custom_channel_order", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(",").filter { it.isNotEmpty() }
    }

    fun saveCustomChannelOrder(orderedIds: List<String>) {
        prefs.edit().putString("custom_channel_order", orderedIds.joinToString(",")).apply()
    }

    fun resetCustomChannelOrder() {
        prefs.edit().remove("custom_channel_order").apply()
    }
}
