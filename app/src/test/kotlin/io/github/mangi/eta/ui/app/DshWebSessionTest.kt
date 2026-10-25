package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.terminal.DaemonLogsResult
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTask
import io.github.mangi.eta.agent.terminal.DetachedTaskStatus
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshWebSessionTest {
    @Test fun reusesMatchingDaemonWithoutStartingAnother() = runBlocking {
        val tasks = FakeTasks(existing = true)
        val result = session(tasks).launch(ENV, "user", BACKEND)
        assertTrue(result is DshWebLaunchResult.Opened)
        assertEquals(0, tasks.starts)
        assertTrue(tasks.stops.isEmpty())
    }

    @Test fun opensTokenUrlFromStartupLog() = runBlocking {
        val tasks = FakeTasks(existing = false)
        val result = session(tasks).launch(ENV, "user", BACKEND) as DshWebLaunchResult.Opened
        assertEquals("http://127.0.0.1:5494/?token=abc_123", result.url)
    }

    @Test fun browserFailureDoesNotStopReusedDaemon() = runBlocking {
        val tasks = FakeTasks(existing = true)
        val result = session(tasks, open = false).launch(ENV, "user", BACKEND)
        assertEquals(DshWebLaunchResult.Failed("BROWSER_UNAVAILABLE"), result)
        assertTrue(tasks.stops.isEmpty())
    }

    @Test fun exitedNewDaemonFailsImmediatelyAndIsCleanedUp() = runBlocking {
        val tasks = FakeTasks(existing = false, running = false)
        val result = session(tasks).launch(ENV, "user", BACKEND)
        assertEquals(DshWebLaunchResult.Failed("DSH_EXITED"), result)
        assertEquals(listOf("new"), tasks.stops)
    }

    @Test fun timeoutCleansOnlyNewDaemon() = runBlocking {
        val tasks = FakeTasks(existing = false, output = "dsh web: starting")
        val result = session(tasks).launch(ENV, "user", BACKEND)
        assertEquals(DshWebLaunchResult.Failed("URL_TIMEOUT"), result)
        assertEquals(listOf("new"), tasks.stops)
    }

    @Test fun cancellationCleansNewDaemonAndPreservesReusedDaemon() = runBlocking {
        for (existing in listOf(false, true)) {
            val tasks = FakeTasks(existing = existing, output = "starting")
            val read = CompletableDeferred<Unit>()
            tasks.onLogs = { read.complete(Unit) }
            val job = launch { DshWebSession(tasks, { true }, waitIntervalMs = 60_000).launch(ENV, "user", BACKEND) }
            read.await()
            job.cancelAndJoin()
            assertEquals(if (existing) emptyList<String>() else listOf("new"), tasks.stops)
        }
    }

    @Test fun ignoresPlainServerAddressWithoutToken() {
        assertNull(DshWebSession.linkFromLogs("dsh web: http://127.0.0.1:3030/"))
        assertEquals(
            "http://127.0.0.1:3030/?token=A-b_c9",
            DshWebSession.linkFromLogs("noise\ndsh web: http://127.0.0.1:3030/?token=A-b_c9\n"),
        )
    }

    @Test fun serverAddressStripsTokenForStatus() {
        assertEquals(
            "http://127.0.0.1:3030",
            DshWebSession.serverAddressFromLogs("dsh web: http://127.0.0.1:3030/?token=secret"),
        )
    }

    private fun session(tasks: FakeTasks, open: Boolean = true) =
        DshWebSession(tasks, { open }, waitAttempts = 1, waitIntervalMs = 0)

    private class FakeTasks(
        existing: Boolean,
        private val running: Boolean = true,
        private val output: String = "dsh web: http://127.0.0.1:5494/?token=abc_123",
    ) : DshWebSession.Tasks {
        private var task: DetachedTask? = if (existing) task("old") else null
        var starts = 0
        var lastCommand: String? = null
        val stops = mutableListOf<String>()
        var onLogs: () -> Unit = {}
        override fun list() = task?.let { listOf(DetachedTaskStatus(it, running)) }.orEmpty()
        override fun start(
            environment: TerminalEnvironment,
            identity: String,
            command: String,
        ): DaemonStartResult {
            starts++
            lastCommand = command
            return DaemonStartResult.Started(task("new", command).also { task = it })
        }
        override fun logs(id: String): DaemonLogsResult { onLogs(); return DaemonLogsResult(ok = true, text = output) }
        override fun stop(id: String) {
            stops += id
            if (task?.id == id) task = null
        }
        companion object {
            private fun task(id: String, command: String = DshWebSession.COMMAND) = DetachedTask(
                id, 10, "owner", command, "/workspace", "user", ENV, "/log", 0, BACKEND,
            )
        }
    }

    companion object {
        private val ENV = TerminalEnvironment.DEBIAN
        private val BACKEND = LinuxExecutionBackend.PROOT
    }
}
