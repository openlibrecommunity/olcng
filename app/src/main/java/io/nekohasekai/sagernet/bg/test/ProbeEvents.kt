package io.nekohasekai.sagernet.bg.test

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <T> receiveProbeEvent(channel: ReceiveChannel<T>, timeoutMillis: Long): ChannelResult<T>? =
    select {
        channel.onReceiveCatching { it }
        onTimeout(timeoutMillis) { null }
    }
