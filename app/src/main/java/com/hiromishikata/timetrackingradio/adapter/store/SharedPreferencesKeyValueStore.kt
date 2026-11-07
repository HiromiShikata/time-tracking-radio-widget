package com.hiromishikata.timetrackingradio.adapter.store

import android.content.SharedPreferences
import androidx.core.content.edit

class SharedPreferencesKeyValueStore(private val preferences: SharedPreferences) : KeyValueStore {
    override fun read(key: String): String? = preferences.getString(key, null)

    override fun write(key: String, value: String?) {
        preferences.edit {
            if (value == null) remove(key) else putString(key, value)
        }
    }
}
