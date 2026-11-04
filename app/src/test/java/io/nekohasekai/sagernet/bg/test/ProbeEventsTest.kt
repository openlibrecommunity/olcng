package io.nekohasekai.sagernet.bg.test

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeEventsTest {
    @Test
    fun flushTimeoutDoesNotDiscardCompletedResults() = runBlocking {
        val events = Channel<Int>(16)
        launch {
            repeat(1000) {
                delay(1)
                events.send(it)
            }
            events.close()
        }
        val received = mutableListOf<Int>()
        while (true) {
            val result = receiveProbeEvent(events, 1) ?: continue
            if (result.isClosed) break
            received.add(result.getOrThrow())
        }
        assertEquals((0 until 1000).toList(), received)
    }
}
