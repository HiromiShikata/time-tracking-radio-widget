package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.hiromishikata.timetrackingradio.adapter.http.HttpRequest
import com.hiromishikata.timetrackingradio.adapter.http.HttpResponse
import com.hiromishikata.timetrackingradio.adapter.http.HttpTransport
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueDiagnosticLogRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSnapshotRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import java.util.concurrent.CopyOnWriteArrayList

internal val WIDGET_TEST_SETTINGS = WidgetSettings("https://example.com/exec?key=example", listOf("project-a", "project-b"))

private val SELECTED_PROJECT = Regex("\"project\":\"([^\"]+)\"")
private val SELECTED_ACTIVITY = Regex("\"activity\":\"([^\"]+)\"")

internal class ForbiddenTransport : HttpTransport {
    var calls = 0
        private set

    override fun send(request: HttpRequest): HttpResponse {
        calls += 1
        throw AssertionError("no network call is allowed on this path: ${request.method}")
    }
}

internal class EchoSelectionTransport(private val beforeReply: (HttpRequest) -> Unit = {}) : HttpTransport {
    val requests: MutableList<HttpRequest> = CopyOnWriteArrayList()

    override fun send(request: HttpRequest): HttpResponse {
        requests += request
        beforeReply(request)
        val body = request.body.orEmpty()
        val project = SELECTED_PROJECT.find(body)?.groupValues?.get(1)
        val activity = SELECTED_ACTIVITY.find(body)?.groupValues?.get(1)
        return when {
            body.startsWith("{\"diagnostics\"") -> HttpResponse(200, null, """{"result":"ok","accepted":1}""")
            request.method == "GET" || project == null || activity == null ->
                HttpResponse(200, null, """{"result":"ok","selection":null}""")
            else -> HttpResponse(
                200,
                null,
                """{"result":"ok","selection":{"project":"$project","activity":"$activity","since":"2026-11-07T08:39:00Z"}}""",
            )
        }
    }
}

internal fun selectedProjectOf(request: HttpRequest): String? = SELECTED_PROJECT.find(request.body.orEmpty())?.groupValues?.get(1)

internal fun widgetPreferences(context: Context) =
    SharedPreferencesKeyValueStore(context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE))

internal fun widgetSnapshots(context: Context) = KeyValueWidgetSnapshotRepository(widgetPreferences(context))

internal fun widgetDiagnosticLog(context: Context) = KeyValueDiagnosticLogRepository(widgetPreferences(context))

internal fun installWorkManagerForTests(context: Context) {
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
}

internal fun uniqueWorkInfos(context: Context, uniqueWorkName: String): List<WorkInfo> =
    WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueWorkName).get()

internal fun awaitWorkFinished(context: Context, uniqueWorkName: String, timeoutMillis: Long = 5_000) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (uniqueWorkInfos(context, uniqueWorkName).any { !it.state.isFinished }) {
        check(System.currentTimeMillis() < deadline) { "work under $uniqueWorkName did not finish" }
        Thread.sleep(10)
    }
}

internal fun installHarmlessWidgetWorkFactories() {
    SelectionWidgetProvider.controllerFactory = { WidgetController(it, { EchoSelectionTransport() }, { EchoSelectionTransport() }, publish = { _, _ -> }) }
    WidgetSelectionWorker.controllerFactory = { WidgetController(it, { EchoSelectionTransport() }, { EchoSelectionTransport() }, publish = { _, _ -> }) }
    WidgetDiagnosticsUploadWorker.controllerFactory = { WidgetController(it, { EchoSelectionTransport() }, { EchoSelectionTransport() }, publish = { _, _ -> }) }
}

internal fun restoreDefaultWidgetFactories() {
    SelectionWidgetProvider.controllerFactory = { WidgetController(it) }
    WidgetSelectionWorker.controllerFactory = { WidgetController(it) }
    WidgetDiagnosticsUploadWorker.controllerFactory = { WidgetController(it) }
}
