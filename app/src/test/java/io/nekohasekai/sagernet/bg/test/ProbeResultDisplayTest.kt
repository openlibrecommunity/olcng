package io.nekohasekai.sagernet.bg.test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeResultDisplayTest {
    private data class Profile(val id: Long, val status: Int, val ping: Int, val order: Long)
    private val comparator = pingComparator<Profile>({ it.status }, { it.ping }, { it.order }, { it.id })

    @Test
    fun zeroPingFailuresAndMissingPluginsAreVisible() {
        listOf(-1, 2, 3).forEach { status ->
            assertTrue(probeFailed(status, 0))
            assertEquals("Ошибка проверки", probeResultLabel(status, 0))
        }
        assertEquals("Ошибка проверки", probeResultLabel(0, -1))
        assertEquals("0ms", probeResultLabel(1, 0))
        assertNull(probeResultLabel(0, 0))
    }

    @Test
    fun successfulResultsSortFirstWithDeterministicTies() {
        val profiles = listOf(
            Profile(1, 3, 0, 1),
            Profile(2, 1, 80, 2),
            Profile(3, 0, 0, 3),
            Profile(4, 1, 20, 4),
            Profile(5, 1, 20, 4),
            Profile(6, -1, 0, 6),
        )
        val expected = listOf(4L, 5L, 2L, 1L, 3L, 6L)
        assertEquals(expected, profiles.sortedWith(comparator).map { it.id })
        assertEquals(expected, profiles.reversed().sortedWith(comparator).map { it.id })
    }

    @Test
    fun allFailedBatchNeverBecomesSuccessOrChangesOrder() {
        val profiles = (1L..2374L).map { Profile(it, 3, 0, it) }
        assertEquals(profiles, profiles.reversed().sortedWith(comparator))
        assertTrue(profiles.all { probeFailed(it.status, it.ping) })
    }
}
