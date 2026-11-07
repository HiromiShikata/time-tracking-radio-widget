package com.hiromishikata.timetrackingradio.adapter.store

class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
