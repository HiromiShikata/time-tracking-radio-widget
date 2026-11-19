package com.hiromishikata.timetrackingradio.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class LatestRelease(
    val version: SemanticVersion,
    val apkDownloadUrl: String?,
    val apkSizeBytes: Long?,
    val changeLines: List<String> = emptyList(),
)

fun parseLatestRelease(body: String): LatestRelease = parseReleaseObject(parseJsonObject(body))

fun parseReleaseList(body: String): List<LatestRelease> =
    parseJsonArray(body)
        .map { it as? JsonObject ?: throw MalformedResponseException("release is not a JSON object") }
        .filterNot { it.booleanFieldOrNull("draft") == true || it.booleanFieldOrNull("prerelease") == true }
        .map { parseReleaseObject(it) }
        .sortedByDescending { it.version }

private fun parseReleaseObject(root: JsonObject): LatestRelease {
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
        parseReleaseChangeLines(root.stringFieldOrNull("body")),
    )
}
