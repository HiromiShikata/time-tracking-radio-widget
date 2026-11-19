package com.hiromishikata.timetrackingradio.domain

data class ReleaseChanges(val version: SemanticVersion, val changeLines: List<String>)

private const val BULLET_STAR = "* "
private const val BULLET_DASH = "- "
private const val FULL_CHANGELOG_PREFIX = "**Full Changelog**"
private const val FIRST_CONTRIBUTION_MARKER = "made their first contribution in"
private val contributorSuffix = Regex(""" by @\S+ in \S+$""")

fun parseReleaseChangeLines(body: String?): List<String> {
    if (body == null) return emptyList()
    return body.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { it.startsWith("#") || it.startsWith(FULL_CHANGELOG_PREFIX) || it.contains(FIRST_CONTRIBUTION_MARKER) }
        .map { stripBullet(it).replace(contributorSuffix, "") }
}

private fun stripBullet(line: String): String =
    if (line.startsWith(BULLET_STAR) || line.startsWith(BULLET_DASH)) line.substring(2).trimStart() else line

fun composeUpdateNotificationText(releases: List<ReleaseChanges>): String =
    releases
        .flatMap { release -> listOf("v${release.version}") + release.changeLines.map { "- $it" } }
        .joinToString(separator = "\n")
