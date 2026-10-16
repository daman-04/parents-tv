package com.parents.tv.data.prefs

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("parents_tv_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_CHANNEL_ID = "last_channel_id"
        private const val KEY_VIEW_MODE = "view_mode"
        private const val KEY_LAST_CHANNEL_INDEX = "last_channel_index"
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
}
