package com.hiromishikata.timetrackingradio.domain

data class SemanticVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"
}

private val semanticVersionPattern = Regex("""v?(\d+)\.(\d+)\.(\d+)""")

fun parseSemanticVersion(text: String): SemanticVersion? {
    val match = semanticVersionPattern.matchEntire(text) ?: return null
    val (major, minor, patch) = match.destructured
    return SemanticVersion(
        major.toIntOrNull() ?: return null,
        minor.toIntOrNull() ?: return null,
        patch.toIntOrNull() ?: return null,
    )
}
