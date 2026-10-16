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
        private const val PREF_PREFIX_FAILURE = "fail_cnt_"
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
}
