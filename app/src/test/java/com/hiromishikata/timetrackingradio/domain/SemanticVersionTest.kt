package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {
    @Test
    fun parsesThreePartVersionsWithOptionalVPrefix() {
        val cases = listOf(
            "v1.2.3" to SemanticVersion(1, 2, 3),
            "1.0.12" to SemanticVersion(1, 0, 12),
            "v1.2" to null,
            "v1.2.3-beta" to null,
            "" to null,
            "vx.y.z" to null,
        )
        cases.forEach { (text, expected) ->
            assertEquals("text=$text", expected, parseSemanticVersion(text))
        }
    }

    @Test
    fun comparesNumerically() {
        val cases = listOf(
            Triple("1.0.10", "1.0.9", 1),
            Triple("1.0.9", "1.0.9", 0),
            Triple("0.9.99", "1.0.0", -1),
        )
        cases.forEach { (left, right, sign) ->
            val result = parseSemanticVersion(left)!!.compareTo(parseSemanticVersion(right)!!)
            assertTrue("left=$left right=$right result=$result", Integer.signum(result) == sign)
        }
    }
}
