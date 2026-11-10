package com.hiromishikata.timetrackingradio.adapter.update

import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.content.Intent
import android.content.IntentSender
import com.hiromishikata.timetrackingradio.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class PackageInstallerSessionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val statusIntent: IntentSender = PendingIntent.getBroadcast(
        context,
        0,
        Intent("test.install.status"),
        PendingIntent.FLAG_IMMUTABLE,
    ).intentSender
    private val apk = File("updates/app-update.apk")

    private class RecordingInstallSession(
        private val writeFailure: IOException? = null,
        private val commitFailure: IOException? = null,
    ) : InstallSessionApi {
        val calls = mutableListOf<String>()
        var writtenApk: File? = null
        var committedIntent: IntentSender? = null

        override fun createSession(): Int {
            calls += "createSession"
            return SESSION_ID
        }

        override fun writeApk(sessionId: Int, name: String, apk: File) {
            calls += "writeApk:$sessionId:$name"
            writtenApk = apk
            writeFailure?.let { throw it }
        }

        override fun commit(sessionId: Int, statusIntent: IntentSender) {
            calls += "commit:$sessionId"
            committedIntent = statusIntent
            commitFailure?.let { throw it }
        }

        override fun abandon(sessionId: Int) {
            calls += "abandon:$sessionId"
        }
    }

    @Test
    fun aSessionIsCreatedWrittenAndCommittedWithTheApk() {
        val session = RecordingInstallSession()
        val sessionId = commitApkSession(session, apk, statusIntent)
        assertEquals(SESSION_ID, sessionId)
        assertEquals(
            listOf("createSession", "writeApk:$SESSION_ID:app-update.apk", "commit:$SESSION_ID"),
            session.calls,
        )
        assertSame(apk, session.writtenApk)
        assertSame(statusIntent, session.committedIntent)
    }

    @Test
    fun aFailedWriteAbandonsTheSessionAndRethrows() {
        val session = RecordingInstallSession(writeFailure = IOException("disk full"))
        val thrown = runCatching { commitApkSession(session, apk, statusIntent) }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is IOException)
        assertEquals("disk full", thrown!!.message)
        assertEquals(listOf("createSession", "writeApk:$SESSION_ID:app-update.apk", "abandon:$SESSION_ID"), session.calls)
    }

    @Test
    fun aFailedCommitAbandonsTheSessionAndRethrows() {
        val session = RecordingInstallSession(commitFailure = IOException("commit refused"))
        val thrown = runCatching { commitApkSession(session, apk, statusIntent) }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is IOException)
        assertEquals("commit refused", thrown!!.message)
        assertEquals(
            listOf("createSession", "writeApk:$SESSION_ID:app-update.apk", "commit:$SESSION_ID", "abandon:$SESSION_ID"),
            session.calls,
        )
    }

    @Test
    fun theInstallStatusIsInterpretedPerStatusCode() {
        data class Case(
            val name: String,
            val status: Int,
            val message: String?,
            val expected: InstallStatusOutcome,
        )
        val cases = listOf(
            Case(
                "pending user action asks for confirmation",
                PackageInstaller.STATUS_PENDING_USER_ACTION,
                null,
                InstallStatusOutcome.ConfirmationNeeded,
            ),
            Case("success is installed", PackageInstaller.STATUS_SUCCESS, null, InstallStatusOutcome.Installed),
            Case(
                "failure keeps its message",
                PackageInstaller.STATUS_FAILURE,
                "boom",
                InstallStatusOutcome.Failed("boom"),
            ),
            Case(
                "failure without a message names its status",
                PackageInstaller.STATUS_FAILURE,
                null,
                InstallStatusOutcome.Failed("status ${PackageInstaller.STATUS_FAILURE}"),
            ),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.expected, interpretInstallStatus(case.status, case.message))
        }
    }

    private companion object {
        const val SESSION_ID = 7
    }
}
