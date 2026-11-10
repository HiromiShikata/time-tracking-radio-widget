package com.hiromishikata.timetrackingradio.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class LatestRelease(val version: SemanticVersion, val apkDownloadUrl: String?, val apkSizeBytes: Long?)

fun parseLatestRelease(body: String): LatestRelease {
    val root = parseJsonObject(body)
    val tag = root.stringField("tag_name")
    val version = parseSemanticVersion(tag) ?: throw MalformedResponseException("tag is not a version: $tag")
    val assets = root["assets"] as? JsonArray ?: JsonArray(emptyList())
    val apkAsset = assets
        .map { it as? JsonObject ?: throw MalformedResponseException("asset is not an object") }
        .firstOrNull { it.stringFieldOrNull("name")?.endsWith(".apk") == true }
    return LatestRelease(
        version,
        apkAsset?.stringField("browser_download_url"),
        apkAsset?.longFieldOrNull("size"),
    )
}
