package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkDownloadTest {
    @Test
    fun theDownloadedSizeMustMatchTheExpectedSize() {
        data class Case(
            val name: String,
            val actualBytes: Long,
            val expectedBytes: Long,
            val expectedMessage: String?,
        )
        val cases = listOf(
            Case("equal sizes pass", 10L, 10L, null),
            Case("smaller download throws", 9L, 10L, "downloaded size 9 does not match expected 10"),
            Case("larger download throws", 11L, 10L, "downloaded size 11 does not match expected 10"),
        )
        cases.forEach { case ->
            val thrown = runCatching { verifyDownloadedSize(case.actualBytes, case.expectedBytes) }.exceptionOrNull()
            if (case.expectedMessage == null) {
                assertNull(case.name, thrown)
            } else {
                assertTrue("${case.name}: thrown=$thrown", thrown is ApkDownloadException)
                assertEquals(case.name, case.expectedMessage, thrown!!.message)
            }
        }
    }
}
