package com.hiromishikata.timetrackingradio.ui

import android.content.Context
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.ConnectionCheckResult
import com.hiromishikata.timetrackingradio.domain.Selection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class ConnectionCheckMessageTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun eachOutcomeHasAReadableEnglishMessage() {
        val cases = listOf(
            ConnectionCheckResult.Connected(null) to "Connection OK: no current selection",
            ConnectionCheckResult.Connected(Selection(CellKey("project-a", ActivityKind.WORK), Instant.parse("2026-11-08T08:39:00Z")))
                to "Connection OK: project-a / work since 08:39 UTC",
            ConnectionCheckResult.ServerRejected("bad key") to "Server reported an error: bad key",
            ConnectionCheckResult.Failed("endpoint returned HTTP 500") to "Connection failed: endpoint returned HTTP 500",
        )
        cases.forEach { (result, expected) ->
            assertEquals("result=$result", expected, connectionCheckMessage(context, result))
        }
    }
}
