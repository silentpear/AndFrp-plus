package io.github.ferryapp.ferry.data

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ferry_settings", Context.MODE_PRIVATE)

    var concurrency: Int
        get() = prefs.getInt(KEY_CONCURRENCY, 3).coerceIn(1, 8)
        set(value) = prefs.edit().putInt(KEY_CONCURRENCY, value.coerceIn(1, 8)).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var showHidden: Boolean
        get() = prefs.getBoolean(KEY_SHOW_HIDDEN, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_HIDDEN, value).apply()

    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        set(value) = prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()

    var darkMode: Int
        get() = prefs.getInt(KEY_DARK_MODE, 0)
        set(value) = prefs.edit().putInt(KEY_DARK_MODE, value).apply()

    var appLock: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_APP_LOCK, value).apply()

    var conflictPolicy: ConflictPolicy
        get() = runCatching {
            ConflictPolicy.valueOf(prefs.getString(KEY_CONFLICT, ConflictPolicy.ASK.name)!!)
        }.getOrDefault(ConflictPolicy.ASK)
        set(value) = prefs.edit().putString(KEY_CONFLICT, value.name).apply()

    var speedLimitKbps: Int
        get() = prefs.getInt(KEY_SPEED_LIMIT, 0)
        set(value) = prefs.edit().putInt(KEY_SPEED_LIMIT, value.coerceAtLeast(0)).apply()

    var lastLocalDir: String
        get() = prefs.getString(KEY_LAST_LOCAL, null) ?: "/sdcard"
        set(value) = prefs.edit().putString(KEY_LAST_LOCAL, value).apply()

    private companion object {
        const val KEY_CONCURRENCY = "concurrency"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_SHOW_HIDDEN = "show_hidden"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_APP_LOCK = "app_lock"
        const val KEY_CONFLICT = "conflict_policy"
        const val KEY_SPEED_LIMIT = "speed_limit"
        const val KEY_LAST_LOCAL = "last_local_dir"
    }
}
