package com.hiromishikata.timetrackingradio.adapter.store

interface KeyValueStore {
    fun read(key: String): String?

    fun write(key: String, value: String?)
}
