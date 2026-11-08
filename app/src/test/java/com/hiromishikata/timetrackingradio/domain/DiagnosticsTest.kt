package com.hiromishikata.timetrackingradio.domain

import com.hiromishikata.timetrackingradio.usecase.FakeDiagnosticLogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DiagnosticsTest {
    @Test
    fun redactEndpointKeyReplacesOnlyTheKeyValue() {
        val cases = listOf(
            "https://script.example.com/exec?key=s3cr3t" to "https://script.example.com/exec?key=***",
            "https://script.example.com/exec?a=1&key=s3cr3t&b=2" to "https://script.example.com/exec?a=1&key=***&b=2",
            "https://script.example.com/exec" to "https://script.example.com/exec",
        )
        cases.forEach { (url, expected) ->
            assertEquals("url=$url", expected, redactEndpointKey(url))
        }
    }

    @Test
    fun endpointKeySecretsCoverTheRawAndDecodedValue() {
        assertEquals(setOf("a%2Bb%20c", "a+b c"), endpointKeySecrets("https://h.example/exec?key=a%2Bb%20c"))
        assertEquals(emptySet<String>(), endpointKeySecrets("https://h.example/exec"))
    }

    @Test
    fun scrubSecretsRemovesEverySecretOccurrence() {
        val secrets = setOf("a%2Bb%20c", "a+b c")
        val cases = listOf(
            "failed for https://h.example/exec?key=a%2Bb%20c" to "failed for https://h.example/exec?key=***",
            "echo a+b c back" to "echo *** back",
            "nothing to hide" to "nothing to hide",
        )
        cases.forEach { (text, expected) ->
            assertEquals("text=$text", expected, scrubSecrets(text, secrets))
        }
    }

    @Test
    fun hostOfReturnsOnlyTheHost() {
        assertEquals("script.googleusercontent.com", hostOf("https://script.googleusercontent.com/echo?user_content_key=t0ken"))
        assertEquals("unknown", hostOf("not a url with spaces"))
    }

    @Test
    fun renderDiagnosticLogListsNewestFirstAndIsEmptyWithoutEntries() {
        val entries = listOf(
            DiagnosticEntry(2, Instant.parse("2026-11-08T00:02:00Z"), DiagnosticEventType.TAP_RECEIVED, "project=a, activity=work", false),
            DiagnosticEntry(1, Instant.parse("2026-11-08T00:01:00Z"), DiagnosticEventType.SETTINGS_SAVED, "projects=2", true),
        )
        assertEquals(
            "2026-11-08T00:02:00Z tap_received project=a, activity=work\n2026-11-08T00:01:00Z settings_saved projects=2",
            renderDiagnosticLog(entries),
        )
        assertEquals("", renderDiagnosticLog(emptyList()))
    }

    @Test
    fun encodeDiagnosticsUploadWritesEachEntryWithAtEventAndDetail() {
        val entries = listOf(
            DiagnosticEntry(1, Instant.parse("2026-11-08T00:01:00Z"), DiagnosticEventType.EXCEPTION, "bad \"quote\"\nline", false),
        )
        assertEquals(
            """{"diagnostics":[{"at":"2026-11-08T00:01:00Z","event":"exception","detail":"bad \"quote\"\nline"}]}""",
            encodeDiagnosticsUpload(entries),
        )
    }

    @Test
    fun parseDiagnosticsUploadResponseDeliversOnlyOnOkResult() {
        val cases = listOf(
            """{"result":"ok","accepted":2}""" to true,
            """{"result":"ng","message":"unsupported request body"}""" to false,
            """{"result":"ok"}""" to true,
            """{"accepted":2}""" to false,
            "not json" to false,
            "[]" to false,
        )
        cases.forEach { (body, delivered) ->
            assertEquals(
                "body=$body",
                delivered,
                parseDiagnosticsUploadResponse(body) is DiagnosticsUploadResult.Delivered,
            )
        }
    }

    @Test
    fun recorderStoresTheScrubbedDetailWithTheClockTime() {
        val log = FakeDiagnosticLogRepository()
        val recorder = DiagnosticRecorder(log, setOf("s3cr3t"), clock = { Instant.parse("2026-11-08T00:03:00Z") })
        recorder.record(DiagnosticEventType.EXCEPTION, "failed at https://h.example/exec?key=s3cr3t")
        assertEquals(
            listOf(Triple(DiagnosticEventType.EXCEPTION, "failed at https://h.example/exec?key=***", Instant.parse("2026-11-08T00:03:00Z"))),
            log.recorded.map { Triple(it.type, it.detail, it.at) },
        )
        assertTrue(log.recorded.none { it.detail.contains("s3cr3t") })
    }

    @Test
    fun describeFailureNamesTheClassAndMessage() {
        assertEquals("IllegalStateException: boom", describeFailure(IllegalStateException("boom")))
    }
}
