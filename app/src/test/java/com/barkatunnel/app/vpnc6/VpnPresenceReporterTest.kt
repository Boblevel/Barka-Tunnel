package com.barkatunnel.app.vpnc6

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

class VpnPresenceReporterTest {
    private class QueueExecutor : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        var stopped = false
        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            tasks.add(command)
        }
        fun drain() { while (tasks.isNotEmpty()) tasks.remove().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return tasks.toMutableList().also { tasks.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    private data class Event(val session: String, val online: Boolean, val port: Int?)
    private class Fixture {
        val executor = QueueExecutor()
        val events = mutableListOf<Event>()
        var time = 0L
        var fail = false
        val reporter = VpnPresenceReporter(
            send = { id, online, port ->
                events.add(Event(id, online, port))
                if (fail) throw java.io.IOException("offline")
            },
            now = { time }, executor = executor
        )
    }

    @Test fun presenceIsAsynchronousAndLimitedToOneHeartbeatPerThirtySeconds() {
        val f = Fixture()
        val id = f.reporter.start()!!
        assertEquals(id, java.util.UUID.fromString(id).toString())
        f.reporter.heartbeat(id, 10808)
        assertTrue(f.events.isEmpty())
        f.executor.drain()
        assertEquals(listOf(Event(id, true, 10808)), f.events)
        f.time = 29_999
        f.reporter.heartbeat(id, 10808)
        f.executor.drain()
        assertEquals(1, f.events.size)
        f.time = 30_000
        f.reporter.heartbeat(id, 10808)
        f.executor.drain()
        assertEquals(2, f.events.size)
    }

    @Test fun slowNetworkDoesNotAccumulateHeartbeatRequests() {
        val f = Fixture(); val id = f.reporter.start()
        repeat(100) {
            f.time += 30_000
            f.reporter.heartbeat(id, 10808)
        }
        assertEquals(1, f.executor.tasks.size)
        f.executor.drain()
        assertEquals(1, f.events.size)
    }

    @Test fun stoppingBeforeQueuedHeartbeatSuppressesOnlineAndSendsOfflineOnce() {
        val f = Fixture(); val id = f.reporter.start()!!
        f.reporter.heartbeat(id, 10808)
        f.reporter.stop(); f.reporter.stop()
        f.executor.drain()
        assertEquals(listOf(Event(id, false, null)), f.events)
        f.time = 60_000
        f.reporter.heartbeat(id, 10808)
        f.executor.drain()
        assertEquals(1, f.events.size)
    }

    @Test fun stoppingDuringHeartbeatIsOrderedAfterOnline() {
        val executor = QueueExecutor()
        val onlineEvents = mutableListOf<Boolean>()
        lateinit var reporter: VpnPresenceReporter
        reporter = VpnPresenceReporter(send = { _, online, _ ->
            onlineEvents.add(online)
            if (online) reporter.stop()
        }, now = { 0 }, executor = executor)
        reporter.heartbeat(reporter.start(), 10808)
        executor.drain()
        assertEquals(listOf(true, false), onlineEvents)
    }

    @Test fun reconnectRejectsOldMonitorAndUsesNewSession() {
        val f = Fixture(); val old = f.reporter.start()!!
        f.reporter.heartbeat(old, 10808); f.executor.drain()
        val current = f.reporter.start()!!
        assertNotEquals(old, current)
        f.reporter.heartbeat(old, 10808)
        f.reporter.heartbeat(current, 10809)
        f.executor.drain()
        assertEquals(listOf(Event(old, true, 10808), Event(old, false, null),
            Event(current, true, 10809)), f.events)
    }

    @Test fun networkErrorsDoNotEscapeOrPreventFollowingUpdates() {
        val f = Fixture(); val id = f.reporter.start()!!
        f.fail = true
        f.reporter.heartbeat(id, 10808); f.executor.drain()
        f.time = 30_000; f.fail = false
        f.reporter.heartbeat(id, 10808); f.executor.drain()
        assertEquals(2, f.events.size)
        f.fail = true
        f.reporter.close(); f.executor.drain()
        assertEquals(Event(id, false, null), f.events.last())
    }

    @Test fun destructionClosesExecutorAndCannotRestartPresence() {
        val f = Fixture(); val id = f.reporter.start()!!
        f.reporter.heartbeat(id, 10808)
        f.reporter.close(); f.reporter.close()
        assertNull(f.reporter.start())
        f.reporter.heartbeat(id, 10808)
        f.executor.drain()
        assertTrue(f.executor.isTerminated)
        assertEquals(listOf(Event(id, false, null)), f.events)
    }

    @Test fun presenceDoesNotBeginWithoutAConnectedSession() {
        val f = Fixture()
        f.reporter.heartbeat(null, 10808)
        f.reporter.heartbeat(java.util.UUID.randomUUID().toString(), 10808)
        f.executor.drain()
        assertTrue(f.events.isEmpty())
    }
}
