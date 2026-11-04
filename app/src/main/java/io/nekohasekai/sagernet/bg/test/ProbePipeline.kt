package io.nekohasekai.sagernet.bg.test

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal suspend fun <T> runProbePipeline(
    produce: suspend CoroutineScope.(SendChannel<T>) -> Unit,
    collect: suspend (ReceiveChannel<T>) -> Unit,
) = coroutineScope {
    val events = Channel<T>(Channel.UNLIMITED)
    launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            coroutineScope { produce(events) }
        } finally {
            events.close()
        }
    }
    // cancellation stops producers, but completed events must reach storage.
    withContext(NonCancellable) { collect(events) }
}
