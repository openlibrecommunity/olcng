package io.nekohasekai.sagernet.group

import org.junit.Assert.*
import org.junit.Test

class BuiltinPoolMergeTest {
    @Test
    fun sharedProfileSurvivesRemovalFromOneSource() {
        val plan = BuiltinPoolMerge.plan(listOf(7L to "shared"), mapOf("a" to emptySet(), "b" to setOf("shared")))
        assertEquals(mapOf("shared" to 7L), plan.retained)
        assertTrue(plan.deleted.isEmpty())
        assertEquals(setOf("b"), plan.owners["shared"])
    }

    @Test
    fun deletesOnlyAfterAllSnapshotsDropProfile() {
        val plan = BuiltinPoolMerge.plan(listOf(7L to "old"), mapOf("a" to setOf("new"), "b" to emptySet()))
        assertEquals(setOf(7L), plan.deleted)
        assertEquals(setOf("new"), plan.added)
    }

    @Test
    fun failureKeepsLastGoodSnapshotAndIdentity() {
        val lastGood = mapOf("failed-source" to setOf("old"), "successful-source" to setOf("new"))
        val plan = BuiltinPoolMerge.plan(listOf(42L to "old"), lastGood)
        assertEquals(42L, plan.retained["old"])
        assertTrue(plan.deleted.isEmpty())
        assertEquals(setOf("new"), plan.added)
    }

    @Test
    fun globalDedupKeepsFirstIdentityAndAllOwners() {
        val plan = BuiltinPoolMerge.plan(listOf(9L to "same", 12L to "same"),
            mapOf("a" to setOf("same"), "b" to setOf("same")))
        assertEquals(mapOf("same" to 9L), plan.retained)
        assertEquals(setOf(12L), plan.deleted)
        assertEquals(setOf("a", "b"), plan.owners["same"])
        assertTrue(plan.added.isEmpty())
    }

    @Test
    fun registryHasExactlyEighteenStableSources() {
        assertEquals(18, BuiltinPoolSources.urls.size)
        assertEquals(18, BuiltinPoolSources.urls.values.toSet().size)
        assertTrue(BuiltinPoolSources.urls.keys.containsAll((1..9).map { "bypass-$it" }))
        assertTrue(BuiltinPoolSources.urls.getValue("igareck-black-mixed").endsWith("BLACK_SS%2BAll_RUS.txt"))
    }
}
