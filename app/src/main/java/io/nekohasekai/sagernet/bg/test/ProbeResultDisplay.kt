package io.nekohasekai.sagernet.bg.test

internal fun probeFailed(status: Int, ping: Int): Boolean = status == -1 || status == 2 || status == 3 || ping < 0

internal fun probeResultLabel(status: Int, ping: Int): String? = when {
    probeFailed(status, ping) -> "Ошибка проверки"
    status == 1 || ping > 0 -> "${ping}ms"
    else -> null
}

internal fun <T> pingComparator(
    status: (T) -> Int,
    ping: (T) -> Int,
    order: (T) -> Long,
    id: (T) -> Long,
): Comparator<T> = compareBy<T> {
    if (status(it) == 1 && ping(it) >= 0) ping(it) else Int.MAX_VALUE
}.thenBy(order).thenBy(id)
