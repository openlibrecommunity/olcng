package io.nekohasekai.sagernet.bg.test

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbePipelineTest {
    @Test
    fun cancellationDrainsQueuedResultsWithoutInventingInflightFailures() = runBlocking {
        withTimeout(5000) {
            val queued = CompletableDeferred<Unit>()
            val releaseCollector = CompletableDeferred<Unit>()
            val persisted = mutableListOf<Int>()
            val batch = launch {
                runProbePipeline<Int>(produce = { events ->
                    repeat(1000) { events.trySend(it).getOrThrow() }
                    queued.complete(Unit)
                    awaitCancellation()
                }, collect = { events ->
                    releaseCollector.await()
                    for (result in events) persisted.add(result)
                })
            }
            queued.await()
            batch.cancel()
            releaseCollector.complete(Unit)
            batch.join()
            assertEquals((0 until 1000).toList(), persisted)
            assertTrue(batch.isCancelled)
        }
    }

    @Test
    fun cancellationWaitsForWorkerCleanupBeforeClosingEvents() = runBlocking {
        withTimeout(5000) {
            val started = CompletableDeferred<Unit>()
            val persisted = mutableListOf<Int>()
            val batch = launch {
                runProbePipeline<Int>(produce = { events ->
                    launch {
                        try {
                            started.complete(Unit)
                            awaitCancellation()
                        } finally {
                            events.trySend(42).getOrThrow()
                        }
                    }
                }, collect = { events ->
                    for (result in events) persisted.add(result)
                })
            }
            started.await()
            batch.cancelAndJoin()
            assertEquals(listOf(42), persisted)
        }
    }

    @Test
    fun completionDrainsEveryResult() = runBlocking {
        val persisted = mutableListOf<Int>()
        runProbePipeline<Int>(produce = { events ->
            repeat(1000) { events.trySend(it).getOrThrow() }
        }, collect = { events ->
            for (result in events) persisted.add(result)
        })
        assertEquals((0 until 1000).toList(), persisted)
    }

    @Test
    fun immediateCancellationClosesChannel() = runBlocking {
        withTimeout(5000) {
            val batch = launch(start = CoroutineStart.UNDISPATCHED) {
                runProbePipeline<Int>(produce = { awaitCancellation() }, collect = { events ->
                    for (result in events) error("unexpected result $result")
                })
            }
            batch.cancelAndJoin()
        }
    }
}
