package org.starbridge.app

import android.content.Context
import org.starbridge.core.server.SettingsStore

/** [SettingsStore] backed by SharedPreferences: survives app restarts. */
class PrefsSettings(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("starbridge", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
