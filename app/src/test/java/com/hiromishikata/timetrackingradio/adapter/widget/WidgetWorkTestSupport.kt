package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.adapter.http.HttpRequest
import com.hiromishikata.timetrackingradio.adapter.http.HttpResponse
import com.hiromishikata.timetrackingradio.adapter.http.HttpTransport
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueDiagnosticLogRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSnapshotRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.robolectric.Shadows
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

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

internal fun WidgetController.acceptQueued(cell: CellKey): QueuedTapAccept =
    acceptQueuedTap(cell) { tappedAt, request -> WidgetWorkScheduler.buildTapRequest(cell, tappedAt, request) }

internal fun installWorkManagerForTests(context: Context) {
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
}

internal fun installWorkManagerWithControlledExecutor(context: Context): ExecutorService {
    val executor = Executors.newSingleThreadExecutor { Thread(it, "widget-work-test").apply { isDaemon = true } }
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(executor).build())
    return executor
}

internal fun uniqueWorkInfos(context: Context, uniqueWorkName: String, timeoutMillis: Long = 30_000): List<WorkInfo> =
    WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueWorkName)
        .get(timeoutMillis, TimeUnit.MILLISECONDS)

internal fun awaitUniqueWork(
    context: Context,
    uniqueWorkName: String,
    what: String,
    timeoutMillis: Long = 30_000,
    condition: (List<WorkInfo>) -> Boolean,
): List<WorkInfo> {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (true) {
        val infos = uniqueWorkInfos(context, uniqueWorkName)
        if (condition(infos)) return infos
        check(System.currentTimeMillis() < deadline) {
            "$what did not happen under $uniqueWorkName within $timeoutMillis ms; last states: ${infos.map { it.state }}"
        }
        Thread.sleep(10)
    }
}

internal fun awaitWorkFinished(context: Context, uniqueWorkName: String, timeoutMillis: Long = 30_000) {
    awaitUniqueWork(context, uniqueWorkName, "work finishing", timeoutMillis) { infos -> infos.all { it.state.isFinished } }
}

internal fun storedTapInput(context: Context, workRequest: OneTimeWorkRequest): Data {
    val captured = AtomicReference<Data?>(null)
    val configuration = Configuration.Builder()
        .setExecutor(Executors.newSingleThreadExecutor { Thread(it, "widget-tap-input").apply { isDaemon = true } })
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                captured.compareAndSet(null, workerParameters.inputData)
                return object : ListenableWorker(appContext, workerParameters) {
                    override fun startWork(): ListenableFuture<Result> = Futures.immediateFuture(Result.success())
                }
            }
        })
        .build()
    WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    WidgetWorkScheduler.enqueueWorkRequest(context, workRequest)
    meetConstraintsOnceQueued(context, WidgetSelectionWorker.UNIQUE_WORK_NAME)
    val deadline = System.currentTimeMillis() + 30_000
    while (captured.get() == null) {
        check(System.currentTimeMillis() < deadline) { "the tap's work did not start under ${WidgetSelectionWorker.UNIQUE_WORK_NAME}" }
        Thread.sleep(10)
    }
    return checkNotNull(captured.get())
}

internal val TEST_TAPPED_AT: Instant = Instant.parse("2026-11-09T02:19:03.112Z")

internal const val TEST_TAPPED_AT_TEXT = "2026-11-09T02:19:03.112Z"

internal val WAITING_FOR_NETWORK_TEXT = Regex("waiting for (the )?network", RegexOption.IGNORE_CASE)

internal fun cellTapIntent(project: String, activity: String): Intent =
    Intent(SelectionWidgetProvider.ACTION_CELL_TAP)
        .putExtra(SelectionWidgetProvider.EXTRA_PROJECT, project)
        .putExtra(SelectionWidgetProvider.EXTRA_ACTIVITY, activity)

internal fun driveUniqueWorkToFinish(context: Context, uniqueWorkName: String, timeoutMillis: Long = 60_000) {
    val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (true) {
        val infos = uniqueWorkInfos(context, uniqueWorkName)
        if (infos.all { it.state.isFinished }) return
        check(System.currentTimeMillis() < deadline) {
            "$uniqueWorkName did not finish within $timeoutMillis ms; last states: ${infos.map { it.state }}"
        }
        infos.filterNot { it.state.isFinished }.forEach { info ->
            driver.setAllConstraintsMet(info.id)
            driver.setInitialDelayMet(info.id)
        }
        Thread.sleep(10)
    }
}

internal fun meetAllConstraints(context: Context, uniqueWorkName: String) {
    val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
    uniqueWorkInfos(context, uniqueWorkName).filterNot { it.state.isFinished }.forEach { info ->
        driver.setAllConstraintsMet(info.id)
        driver.setInitialDelayMet(info.id)
    }
}

internal fun meetConstraintsOnceQueued(context: Context, uniqueWorkName: String) {
    awaitUniqueWork(context, uniqueWorkName, "work being queued") { it.isNotEmpty() }
    meetAllConstraints(context, uniqueWorkName)
}

internal fun postedBodyOf(request: HttpRequest): JsonObject =
    Json.parseToJsonElement(request.body.orEmpty()).jsonObject

internal fun renderedView(context: Context, snapshot: WidgetSnapshot): View =
    SelectionWidgetRemoteViewsFactory(context)
        .build(WIDGET_TEST_SETTINGS, snapshot, Instant.parse("2026-11-09T02:19:03.112Z"))
        .apply(context, FrameLayout(context))

internal fun renderedStatusText(context: Context, snapshot: WidgetSnapshot): String =
    renderedView(context, snapshot).findViewById<TextView>(R.id.status_text).text.toString()

internal fun renderedCellDrawableResId(context: Context, snapshot: WidgetSnapshot, cell: CellKey): Int {
    val rowIndex = WIDGET_TEST_SETTINGS.projects.indexOf(cell.project)
    val row = renderedView(context, snapshot).findViewById<ViewGroup>(R.id.grid_rows).getChildAt(rowIndex)
    val iconId = when (cell.activity) {
        ActivityKind.WORK -> R.id.cell_work_icon
        ActivityKind.MEETING -> R.id.cell_meeting_icon
    }
    return Shadows.shadowOf(row.findViewById<ImageView>(iconId).drawable).createdFromResId
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

internal val WIDGET_WORK_NAMES: List<String>
    get() = listOf(
        WidgetSelectionWorker.UNIQUE_WORK_NAME,
        WidgetWorkScheduler.DRAIN_UNIQUE_WORK_NAME,
        WidgetWorkScheduler.DRAIN_RETRY_UNIQUE_WORK_NAME,
    )

internal fun allWidgetWorkInfos(context: Context): List<WorkInfo> = WIDGET_WORK_NAMES.flatMap { uniqueWorkInfos(context, it) }

internal fun meetConstraintsOfAllWidgetWork(context: Context) {
    val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
    allWidgetWorkInfos(context).filterNot { it.state.isFinished }.forEach { driver.setAllConstraintsMet(it.id) }
}

internal fun meetConstraintsAndDelaysOfAllWidgetWork(context: Context) {
    val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
    allWidgetWorkInfos(context).filterNot { it.state.isFinished }.forEach { info ->
        driver.setAllConstraintsMet(info.id)
        driver.setInitialDelayMet(info.id)
    }
}

internal fun driveAllWidgetWorkToFinish(context: Context, timeoutMillis: Long = 60_000) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (true) {
        val infos = allWidgetWorkInfos(context)
        if (infos.all { it.state.isFinished }) return
        check(System.currentTimeMillis() < deadline) {
            "widget work did not finish within $timeoutMillis ms; last states: ${infos.map { it.state }}"
        }
        meetConstraintsAndDelaysOfAllWidgetWork(context)
        Thread.sleep(10)
    }
}

internal fun awaitCondition(what: String, timeoutMillis: Long = 30_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "$what did not happen within $timeoutMillis ms" }
        Thread.sleep(10)
    }
}

internal fun pendingWidgetWorkIds(context: Context): Set<UUID> =
    allWidgetWorkInfos(context).filterNot { it.state.isFinished }.map { it.id }.toSet()

/** Meets the constraints of the work that was not pending when [known] was taken, so older retries keep their delay. */
internal fun meetConstraintsOfWidgetWorkNotIn(context: Context, known: Set<UUID>) {
    val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
    allWidgetWorkInfos(context).filterNot { it.state.isFinished || it.id in known }.forEach { driver.setAllConstraintsMet(it.id) }
}
